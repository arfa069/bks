package com.bks.feature.billsync

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.bks.feature.diagnostics.DiagnosticSensitiveField
import com.bks.feature.diagnostics.InMemoryDiagnosticRecorder
import com.ven.assists.AssistsCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    fun disabledCaptureIsIgnoredAndSameWindowBurstRunsOnceAfterSettle() = runTest {
        val enabled = MutableStateFlow(false)
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        var pageText = "处理中"
        var allWindowsLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = enabled,
            diagnosticRecorder = diagnostics,
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
            advanceTimeBy(CAPTURE_SETTLE_MILLIS)
            runCurrent()
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertEquals(0, allWindowsLookups)

            enabled.value = true
            runCurrent()
            coordinator.onAccessibilityEvent(event())
            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 100)
            pageText = PAGE_TEXT
            repeat(9) { coordinator.onAccessibilityEvent(event()) }
            runCurrent()
            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 1)
            runCurrent()
            assertEquals(0, allWindowsLookups)

            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf(PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(1, processor.persistedResults.size)
            assertEquals(1, allWindowsLookups)
            val diagnostic = diagnostics.events.single()
            assertEquals("WINDOW_CONTENT_CHANGED×10", diagnostic.metadata.triggeredEvents)
            assertEquals(1, diagnostic.metadata.nodeCount)
            assertEquals(1, diagnostic.metadata.insertedCount)
            assertEquals(
                PAGE_TEXT,
                diagnostic.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
            )
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun differentWindowsKeepIndependentPendingCaptures() = runTest {
        val processor = FakeCaptureProcessor()
        var allWindowsLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.AllWindows -> {
                        allWindowsLookups += 1
                        listOf(
                            root(pageText = PAGE_TEXT, windowId = WINDOW_ID),
                            root(pageText = SECOND_PAGE_TEXT, windowId = SECOND_WINDOW_ID)
                        )
                    }
                    AssistsCore.NodeLookupScope.ActiveWindow -> emptyList()
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event(WINDOW_ID))
            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()
            coordinator.onAccessibilityEvent(event(SECOND_WINDOW_ID))
            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()

            assertEquals(listOf(PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(1, allWindowsLookups)

            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()

            assertEquals(listOf(PAGE_TEXT, SECOND_PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(2, processor.persistedResults.size)
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
            runSettledCapture()
            processor.persistFailure = null
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

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
            runSettledCapture()
            assertTrue(persistenceStarted.isCompleted)

            coordinator.onServiceDestroyed()
            runCurrent()
            assertEquals(1, processor.cancelledPersistences)

            processor.persistBlock = null
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun serviceDestroyCancelsPendingCaptureBeforeWindowLookup() = runTest {
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        var allWindowsLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
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
            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 1)
            runCurrent()
            coordinator.onServiceDestroyed()
            advanceTimeBy(1)
            runCurrent()

            assertEquals(0, allWindowsLookups)
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertTrue(diagnostics.events.isEmpty())
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun missingRootProducesOneConciseFailureBatch() = runTest {
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = FakeCaptureProcessor(),
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = { emptyList() }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event(eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            runSettledCapture()

            val diagnostic = diagnostics.events.single()
            assertEquals("WINDOWS_CHANGED×1", diagnostic.metadata.triggeredEvents)
            assertEquals("root_missing", diagnostic.metadata.reason)
            assertEquals(null, diagnostic.metadata.nodeCount)
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
            runSettledCapture()
            pageText = "处理中"
            repeat(10) { coordinator.onAccessibilityEvent(event()) }
            runSettledCapture()
            assertEquals(1, allWindowsLookups)

            releasePersistence.complete(Unit)
            runCurrent()
            assertEquals(listOf(PAGE_TEXT, "处理中"), processor.recognizedPageTexts)
            assertEquals(2, allWindowsLookups)

            processor.persistBlock = null
            pageText = PAGE_TEXT
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

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
            runSettledCapture()
            assertTrue(processor.recognizedPageTexts.isEmpty())

            destroyDuringLookup = false
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        processor: FakeCaptureProcessor,
        enabled: Flow<Boolean>,
        diagnosticRecorder: InMemoryDiagnosticRecorder = InMemoryDiagnosticRecorder(),
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
        diagnosticRecorder = diagnosticRecorder,
        clock = { CAPTURE_TIME },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        rootsProvider = rootsProvider
    )

    private fun kotlinx.coroutines.test.TestScope.runSettledCapture() {
        advanceTimeBy(CAPTURE_SETTLE_MILLIS)
        runCurrent()
    }

    @Suppress("DEPRECATION")
    private fun event(
        windowId: Int = WINDOW_ID,
        eventType: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    ): AccessibilityEvent = AccessibilityEvent.obtain().apply {
        packageName = PAYMENT_PACKAGE
        this.eventType = eventType
        shadowOf(this).setWindowId(windowId)
    }

    @Suppress("DEPRECATION")
    private fun root(
        pageText: String = PAGE_TEXT,
        windowId: Int = WINDOW_ID
    ): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            packageName = PAYMENT_PACKAGE
            text = pageText
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(windowId)
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
            val persisted = result.copy(insertedCount = 1)
            persistedResults += persisted
            return persisted
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = ALIPAY_PACKAGE_NAME
        const val WINDOW_ID = 7
        const val SECOND_WINDOW_ID = 8
        const val PAGE_TEXT = "支付成功\n￥4.99\n订单 1"
        const val SECOND_PAGE_TEXT = "支付成功\n￥8.88\n订单 2"
        const val CAPTURE_TIME = 1_787_704_343_000L
    }
}
