package com.bks.model.platform.billsync

import com.bks.model.platform.billsync.ALIPAY_PACKAGE_NAME
import com.bks.model.platform.billsync.BillSyncCaptureBatch
import com.bks.model.platform.billsync.BillSyncCaptureCause
import com.bks.model.platform.billsync.BillSyncCaptureDiagnosticDetails
import com.bks.model.platform.billsync.BillSyncCaptureOutcome
import com.bks.model.platform.billsync.CaptureTrigger
import com.bks.model.platform.billsync.classifyBillSyncDispatchDelay
import com.bks.model.platform.billsync.DISPATCH_OUTCOME_DEVICE_SLEEP
import com.bks.model.platform.billsync.DISPATCH_OUTCOME_MAIN_THREAD_PAUSED
import com.bks.model.platform.billsync.DISPATCH_OUTCOME_PROCESS_PAUSED
import com.bks.model.platform.billsync.MAIN_DELAY_WARNING_MILLIS
import com.bks.model.platform.billsync.ReadablePageCapture
import com.bks.model.platform.billsync.recordBillSyncCaptureBatch
import android.view.accessibility.AccessibilityEvent
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.InMemoryDiagnosticRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncCaptureDiagnosticsTest {
    @Test
    fun alipayBatchRecordsAggregatedEventsNodesPlaintextAndActualInsertCount() {
        val recorder = InMemoryDiagnosticRecorder()
        val batch = batch(ALIPAY_PACKAGE_NAME)

        recorder.recordBillSyncCaptureBatch(
            batch = batch,
            readableCapture = ReadablePageCapture(
                pageText = "支付成功\n￥10.90\n订单 1",
                visitedNodeCount = 512,
                nodeLimitReached = true
            ),
            details = details(ALIPAY_PACKAGE_NAME, 7),
            outcome = BillSyncCaptureOutcome.Inserted(1)
        )

        val event = recorder.events.single()
        assertEquals("WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6", event.metadata.triggeredEvents)
        assertEquals(512, event.metadata.nodeCount)
        assertTrue(event.metadata.nodeLimitReached)
        assertEquals(1, event.metadata.insertedCount)
        assertEquals("9", event.metadata.sessionId)
        assertEquals("capture", event.metadata.attributes["stage"])
        assertEquals("deadline", event.metadata.attributes["trigger"])
        assertEquals("35", event.metadata.attributes["deliveryDelayMs"])
        assertEquals("25", event.metadata.attributes["frameworkDeliveryMs"])
        assertEquals("4", event.metadata.attributes["superDurationMs"])
        assertEquals("6", event.metadata.attributes["coordinatorDelayMs"])
        assertEquals("12", event.metadata.attributes["queueDelayMs"])
        assertEquals("window", event.metadata.attributes["rootBy"])
        assertEquals("6", event.metadata.attributes["lookupMs"])
        assertEquals("14", event.metadata.attributes["captureMs"])
        assertEquals(
            "支付成功\n￥10.90\n订单 1",
            event.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
        )
    }

    @Test
    fun unsupportedPackageKeepsMetadataButDoesNotPersistUnrelatedPageText() {
        val recorder = InMemoryDiagnosticRecorder()

        recorder.recordBillSyncCaptureBatch(
            batch = batch("com.example.chat"),
            readableCapture = ReadablePageCapture("聊天秘密", 20, false),
            details = details("com.example.chat", 7),
            outcome = BillSyncCaptureOutcome.Failed("completed_status_missing")
        )

        val event = recorder.events.single()
        assertEquals(20, event.metadata.nodeCount)
        assertFalse(
            event.sensitivePayload.fields.getValue(DiagnosticSensitiveField.PageText)
                .contains("聊天秘密")
        )
        assertTrue(event.sensitivePayload.fields.getValue(DiagnosticSensitiveField.PageText).isEmpty())
    }

    @Test
    fun dispatchDelayClassificationSeparatesProcessMainAndDeviceSleep() {
        assertEquals(
            DISPATCH_OUTCOME_PROCESS_PAUSED,
            classifyBillSyncDispatchDelay(
                workerDelayMillis = MAIN_DELAY_WARNING_MILLIS,
                workerElapsedDelayMillis = MAIN_DELAY_WARNING_MILLIS,
                mainPostDelayMillis = 0L
            )
        )
        assertEquals(
            DISPATCH_OUTCOME_MAIN_THREAD_PAUSED,
            classifyBillSyncDispatchDelay(
                workerDelayMillis = 0L,
                workerElapsedDelayMillis = 0L,
                mainPostDelayMillis = MAIN_DELAY_WARNING_MILLIS
            )
        )
        assertEquals(
            DISPATCH_OUTCOME_DEVICE_SLEEP,
            classifyBillSyncDispatchDelay(
                workerDelayMillis = 0L,
                workerElapsedDelayMillis = MAIN_DELAY_WARNING_MILLIS,
                mainPostDelayMillis = 0L
            )
        )
        assertNull(
            classifyBillSyncDispatchDelay(
                workerDelayMillis = 1L,
                workerElapsedDelayMillis = 1L,
                mainPostDelayMillis = 1L
            )
        )
    }

    private fun batch(packageName: String) = BillSyncCaptureBatch(
        trigger = CaptureTrigger(packageName, 7),
        timestampEpochMillis = 1_787_704_343_000L,
        eventTypeCounts = mapOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to 6,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to 1
        ),
        sessionId = 9,
        captureCause = BillSyncCaptureCause.Deadline,
        eventAtFirstEpochMillis = 1_787_704_342_975L,
        eventAtLastEpochMillis = 1_787_704_343_000L,
        callbackEnterAtFirstEpochMillis = 1_787_704_343_000L,
        callbackEnterAtLastEpochMillis = 1_787_704_343_025L,
        receivedAtFirstEpochMillis = 1_787_704_343_005L,
        receivedAtLastEpochMillis = 1_787_704_343_035L,
        maxDeliveryDelayMillis = 35L,
        maxFrameworkDeliveryDelayMillis = 25L,
        maxSuperDurationMillis = 4L,
        maxCoordinatorDelayMillis = 6L
    )

    private fun details(packageName: String, windowId: Int) = BillSyncCaptureDiagnosticDetails(
        captureAtEpochMillis = 1_787_704_343_050L,
        captureEndAtEpochMillis = 1_787_704_343_064L,
        queueDelayMillis = 12L,
        capturedPackageName = packageName,
        capturedWindowId = windowId,
        rootSelection = "window",
        allRootCount = 3,
        activeRootCount = 0,
        lookupMillis = 6L,
        collectMillis = 3L,
        recognizeMillis = 2L,
        persistMillis = 3L,
        captureMillis = 14L
    )
}
