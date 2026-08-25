package com.bks.feature.billsync

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.ven.assists.AssistsCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BillSyncCaptureCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun repeatedStartCreatesOneEnabledObserverAndStopCancelsIt() = runTest {
        val enabled = MutableStateFlow(false)
        var collections = 0
        val observedEnabled = flow {
            collections += 1
            emitAll(enabled)
        }
        val coordinator = coordinator(FakeCaptureProcessor(), observedEnabled)

        try {
            coordinator.start()
            coordinator.start()
            runCurrent()

            assertEquals(1, collections)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun disabledCaptureIsIgnoredAndConcurrentEnabledHandoffPersistsOnce() = runTest {
        val enabled = MutableStateFlow(false)
        val processor = FakeCaptureProcessor()
        var allWindowsLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = enabled,
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.AllWindows -> {
                        allWindowsLookups += 1
                        listOf(root())
                    }
                    AssistsCore.NodeLookupScope.ActiveWindow -> emptyList()
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            assertTrue(processor.recognizedPageTexts.isEmpty())

            enabled.value = true
            runCurrent()
            repeat(10) { coordinator.onAccessibilityEvent(event()) }
            assertEquals(1, allWindowsLookups)
            runCurrent()

            assertEquals(listOf(PAGE_TEXT, PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(1, processor.persistedResults.size)
            assertEquals(2, allWindowsLookups)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun processorFailureReleasesWindowForRetry() = runTest {
        val processor = FakeCaptureProcessor(persistFailure = IllegalStateException("expected"))
        val coordinator = coordinator(processor, MutableStateFlow(true))
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runCurrent()
            processor.persistFailure = null
            coordinator.onAccessibilityEvent(event())
            runCurrent()

            assertEquals(2, processor.persistAttempts)
            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun serviceDestroyCancelsInFlightPersistenceAndNewSessionCanCapture() = runTest {
        val persistenceStarted = CompletableDeferred<Unit>()
        val processor = FakeCaptureProcessor(
            persistBlock = {
                persistenceStarted.complete(Unit)
                awaitCancellation()
            }
        )
        val coordinator = coordinator(processor, MutableStateFlow(true))
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runCurrent()
            assertTrue(persistenceStarted.isCompleted)

            coordinator.onServiceDestroyed()
            runCurrent()
            assertEquals(1, processor.cancelledPersistences)

            processor.persistBlock = null
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runCurrent()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun deferredLatestPageIsCheckedAfterPersistenceAndReleasesWindow() = runTest {
        val releasePersistence = CompletableDeferred<Unit>()
        var pageText = PAGE_TEXT
        var allWindowsLookups = 0
        val processor = FakeCaptureProcessor(
            recognized = { it == PAGE_TEXT },
            persistBlock = { releasePersistence.await() }
        )
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.AllWindows -> {
                        allWindowsLookups += 1
                        listOf(root(pageText))
                    }
                    AssistsCore.NodeLookupScope.ActiveWindow -> emptyList()
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runCurrent()
            pageText = "处理中"
            repeat(10) { coordinator.onAccessibilityEvent(event()) }
            assertEquals(1, allWindowsLookups)

            releasePersistence.complete(Unit)
            runCurrent()
            assertEquals(listOf(PAGE_TEXT, "处理中"), processor.recognizedPageTexts)
            assertEquals(2, allWindowsLookups)

            processor.persistBlock = null
            pageText = PAGE_TEXT
            coordinator.onAccessibilityEvent(event())
            runCurrent()

            assertEquals(2, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun destroyedSessionCannotWriteSuppressionStateAfterRootLookup() = runTest {
        val processor = FakeCaptureProcessor()
        var destroyDuringLookup = true
        lateinit var coordinator: BillSyncCaptureCoordinator
        coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.AllWindows -> {
                        if (destroyDuringLookup) coordinator.onServiceDestroyed()
                        listOf(root())
                    }
                    AssistsCore.NodeLookupScope.ActiveWindow -> emptyList()
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            assertTrue(processor.recognizedPageTexts.isEmpty())

            destroyDuringLookup = false
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runCurrent()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        processor: FakeCaptureProcessor,
        enabled: Flow<Boolean>,
        rootsProvider: (AssistsCore.NodeLookupScope) -> List<AccessibilityNodeInfo> = { scope ->
            when (scope) {
                AssistsCore.NodeLookupScope.AllWindows -> listOf(root())
                AssistsCore.NodeLookupScope.ActiveWindow -> emptyList()
            }
        }
    ): BillSyncCaptureCoordinator = BillSyncCaptureCoordinator(
        context = context,
        enabledIntent = enabled,
        processor = BillSyncCaptureOperations(
            recognize = processor::recognize,
            persist = processor::persist
        ),
        applicationScope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        rootsProvider = rootsProvider
    )

    @Suppress("DEPRECATION")
    private fun event(): AccessibilityEvent = AccessibilityEvent.obtain().apply {
        packageName = PAYMENT_PACKAGE
        shadowOf(this).setWindowId(WINDOW_ID)
    }

    @Suppress("DEPRECATION")
    private fun root(pageText: String = PAGE_TEXT): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            packageName = PAYMENT_PACKAGE
            text = pageText
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(WINDOW_ID)
            shadowOf(this).setAccessibilityWindowInfo(window)
        }

    private class FakeCaptureProcessor(
        private val recognized: (String) -> Boolean = { true },
        var persistFailure: Throwable? = null,
        var persistBlock: (suspend () -> Unit)? = null
    ) {
        val recognizedPageTexts = mutableListOf<String>()
        val persistedResults = mutableListOf<BillSyncResult>()
        var persistAttempts = 0
        var cancelledPersistences = 0

        fun recognize(pageText: String): BillSyncResult {
            recognizedPageTexts += pageText
            return BillSyncResult(recognized = recognized(pageText), createdEntries = emptyList())
        }

        suspend fun persist(result: BillSyncResult): BillSyncResult {
            persistAttempts += 1
            try {
                persistBlock?.invoke()
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelledPersistences += 1
                throw error
            }
            persistFailure?.let { throw it }
            persistedResults += result
            return result
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = "com.example.pay"
        const val WINDOW_ID = 7
        const val PAGE_TEXT = "支付成功\n￥4.99\n订单 1"
    }
}
