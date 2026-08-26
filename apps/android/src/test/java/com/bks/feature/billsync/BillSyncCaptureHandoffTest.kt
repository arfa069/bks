package com.bks.feature.billsync

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncCaptureHandoffTest {
    @Test
    fun sanitizedCaptureIsHandedOffOnceAndSuccessfulWindowRemainsSuppressed() {
        val root = node("safe")
        shadowOf(root).addChild(node("password").apply { isPassword = true })
        val capture = SanitizedBillSyncCapture(PAYMENT_PACKAGE, WINDOW_ID, root.collectReadableText())
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(recognized = true)

        handoff(capture, acceptedWindows, coordinator)
        handoff(capture, acceptedWindows, coordinator)

        assertEquals(listOf("safe", "safe"), coordinator.recognizedPageTexts)
        assertEquals(1, coordinator.persistedResults.size)
        assertFalse(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun unrecognizedCaptureReleasesPreviouslySuccessfulWindow() {
        val acceptedWindows = AcceptedWindowMemory()
        assertTrue(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        acceptedWindows.persistenceSucceeded(PAYMENT_PACKAGE, WINDOW_ID)
        val coordinator = FakeCaptureCoordinator(recognized = false)

        handoff(capture(), acceptedWindows, coordinator)

        assertTrue(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        assertTrue(coordinator.persistedResults.isEmpty())
    }

    @Test
    fun unrecognizedCaptureDuringPersistenceReleasesAfterPersistenceCompletes() {
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        var pendingPersistence: (suspend () -> Unit)? = null

        handoffSanitizedCapture(
            capture = capture(),
            acceptedWindows = acceptedWindows,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> pendingPersistence = block }
        )
        coordinator.recognized = false
        handoff(capture(), acceptedWindows, coordinator)

        runBlocking { requireNotNull(pendingPersistence).invoke() }

        assertTrue(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        assertEquals(1, coordinator.persistedResults.size)
    }

    @Test
    fun recognitionReturningDuringPersistenceKeepsWindowSuppressed() {
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        var pendingPersistence: (suspend () -> Unit)? = null

        handoffSanitizedCapture(
            capture = capture(),
            acceptedWindows = acceptedWindows,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> pendingPersistence = block }
        )
        coordinator.recognized = false
        handoff(capture(), acceptedWindows, coordinator)
        coordinator.recognized = true
        handoff(capture(), acceptedWindows, coordinator)

        runBlocking { requireNotNull(pendingPersistence).invoke() }

        assertEquals(1, coordinator.persistedResults.size)
        assertFalse(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun processorFailureReleasesWindowForRetry() {
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(
            recognized = true,
            persistFailure = IllegalStateException("expected")
        )

        handoff(capture(), acceptedWindows, coordinator)

        assertTrue(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun cancellationPropagatesWithoutReleasingSuccessfulWindow() {
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(
            recognized = true,
            persistFailure = CancellationException("expected")
        )

        val cancellation = runCatching {
            handoff(capture(), acceptedWindows, coordinator)
        }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertFalse(acceptedWindows.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun outcomeReportsActualInsertDuplicateAndParserFailureReasons() {
        val acceptedWindows = AcceptedWindowMemory()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        val outcomes = mutableListOf<BillSyncCaptureOutcome>()

        handoff(capture(), acceptedWindows, coordinator, outcomes)
        handoff(capture(), acceptedWindows, coordinator, outcomes)
        coordinator.recognized = false
        handoff(capture(), acceptedWindows, coordinator, outcomes)

        assertEquals(BillSyncCaptureOutcome.Inserted(1), outcomes[0])
        assertEquals(
            BillSyncCaptureOutcome.NotInserted("already_captured_window"),
            outcomes[1]
        )
        assertEquals(
            BillSyncCaptureOutcome.Failed(BillSyncFailureReason.CompletedStatusMissing.code),
            outcomes[2]
        )
    }

    private fun handoff(
        capture: SanitizedBillSyncCapture,
        acceptedWindows: AcceptedWindowMemory,
        coordinator: FakeCaptureCoordinator,
        outcomes: MutableList<BillSyncCaptureOutcome>? = null
    ) {
        handoffSanitizedCapture(
            capture = capture,
            acceptedWindows = acceptedWindows,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> runBlocking { block() } },
            onOutcome = { outcome -> outcomes?.add(outcome) }
        )
    }

    private fun capture(): SanitizedBillSyncCapture = SanitizedBillSyncCapture(
        packageName = PAYMENT_PACKAGE,
        windowId = WINDOW_ID,
        pageText = "支付成功\n￥4.99\n订单 1"
    )

    @Suppress("DEPRECATION")
    private fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isVisibleToUser = true
        this.text = text
    }

    private class FakeCaptureCoordinator(
        var recognized: Boolean,
        private val persistFailure: Throwable? = null
    ) {
        val recognizedPageTexts = mutableListOf<String>()
        val persistedResults = mutableListOf<BillSyncResult>()

        fun recognize(pageText: String): BillSyncResult {
            recognizedPageTexts += pageText
            return BillSyncResult(
                recognized = recognized,
                createdEntries = emptyList(),
                failureReason = BillSyncFailureReason.CompletedStatusMissing.takeIf { !recognized }
            )
        }

        suspend fun persist(result: BillSyncResult): BillSyncResult {
            persistFailure?.let { throw it }
            val persisted = result.copy(insertedCount = 1)
            persistedResults += persisted
            return persisted
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = "com.example.pay"
        const val WINDOW_ID = 7
    }
}
