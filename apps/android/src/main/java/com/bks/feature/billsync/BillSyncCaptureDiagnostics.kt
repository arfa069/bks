package com.bks.feature.billsync

import android.view.accessibility.AccessibilityEvent
import com.bks.feature.diagnostics.BILL_SYNC_CAPTURE_BATCH_EVENT
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticRecorder
import com.bks.feature.diagnostics.DiagnosticSensitiveField
import com.bks.feature.diagnostics.DiagnosticSensitivePayload
import com.bks.feature.diagnostics.DiagnosticSource

internal data class BillSyncCaptureBatch(
    val trigger: CaptureTrigger,
    val timestampEpochMillis: Long,
    val eventTypeCounts: Map<Int, Int>
) {
    fun merge(other: BillSyncCaptureBatch): BillSyncCaptureBatch {
        val mergedCounts = eventTypeCounts.toMutableMap()
        other.eventTypeCounts.forEach { (eventType, count) ->
            mergedCounts[eventType] = mergedCounts.getOrDefault(eventType, 0) + count
        }
        return copy(
            timestampEpochMillis = minOf(timestampEpochMillis, other.timestampEpochMillis),
            eventTypeCounts = mergedCounts
        )
    }
}

internal sealed interface BillSyncCaptureOutcome {
    data class Inserted(val insertedCount: Int) : BillSyncCaptureOutcome
    data class NotInserted(val reason: String) : BillSyncCaptureOutcome
    data class Failed(val reason: String) : BillSyncCaptureOutcome
}

internal fun formatAccessibilityEventCounts(eventTypeCounts: Map<Int, Int>): String =
    eventTypeCounts.toSortedMap().entries.joinToString(",") { (eventType, count) ->
        val name = AccessibilityEvent.eventTypeToString(eventType).removePrefix("TYPE_")
        "$name×$count"
    }

internal fun DiagnosticRecorder.recordBillSyncCaptureBatch(
    batch: BillSyncCaptureBatch,
    capturedPackageName: String?,
    readableCapture: ReadablePageCapture?,
    outcome: BillSyncCaptureOutcome
) {
    runCatching {
        val result = when (outcome) {
            is BillSyncCaptureOutcome.Inserted -> DiagnosticCaptureResult(
                level = DiagnosticLevel.Info,
                outcome = "inserted",
                reason = null,
                insertedCount = outcome.insertedCount
            )
            is BillSyncCaptureOutcome.NotInserted -> DiagnosticCaptureResult(
                level = DiagnosticLevel.Info,
                outcome = "not_inserted",
                reason = outcome.reason,
                insertedCount = 0
            )
            is BillSyncCaptureOutcome.Failed -> DiagnosticCaptureResult(
                level = DiagnosticLevel.Warning,
                outcome = "failed",
                reason = outcome.reason,
                insertedCount = null
            )
        }
        val pageText = readableCapture?.pageText.orEmpty()
            .takeIf { capturedPackageName == ALIPAY_PACKAGE_NAME }
            .orEmpty()
        record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    timestampEpochMillis = batch.timestampEpochMillis,
                    level = result.level,
                    component = DiagnosticComponent.AutomaticBookkeeping,
                    event = BILL_SYNC_CAPTURE_BATCH_EVENT,
                    source = DiagnosticSource.Accessibility,
                    outcome = result.outcome,
                    reason = result.reason,
                    triggeredEvents = formatAccessibilityEventCounts(batch.eventTypeCounts),
                    nodeCount = readableCapture?.visitedNodeCount,
                    nodeLimitReached = readableCapture?.nodeLimitReached == true,
                    insertedCount = result.insertedCount
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(DiagnosticSensitiveField.PageText to pageText)
                )
            )
        )
    }
}

private data class DiagnosticCaptureResult(
    val level: DiagnosticLevel,
    val outcome: String,
    val reason: String?,
    val insertedCount: Int?
)

internal const val ALIPAY_PACKAGE_NAME = "com.eg.android.AlipayGphone"
