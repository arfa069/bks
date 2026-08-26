package com.bks.feature.billsync

import android.view.accessibility.AccessibilityEvent
import com.bks.feature.diagnostics.DiagnosticSensitiveField
import com.bks.feature.diagnostics.InMemoryDiagnosticRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            capturedPackageName = ALIPAY_PACKAGE_NAME,
            readableCapture = ReadablePageCapture(
                pageText = "支付成功\n￥10.90\n订单 1",
                visitedNodeCount = 512,
                nodeLimitReached = true
            ),
            outcome = BillSyncCaptureOutcome.Inserted(1)
        )

        val event = recorder.events.single()
        assertEquals("WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6", event.metadata.triggeredEvents)
        assertEquals(512, event.metadata.nodeCount)
        assertTrue(event.metadata.nodeLimitReached)
        assertEquals(1, event.metadata.insertedCount)
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
            capturedPackageName = "com.example.chat",
            readableCapture = ReadablePageCapture("聊天秘密", 20, false),
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

    private fun batch(packageName: String) = BillSyncCaptureBatch(
        trigger = CaptureTrigger(packageName, 7),
        timestampEpochMillis = 1_787_704_343_000L,
        eventTypeCounts = mapOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to 6,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to 1
        )
    )
}
