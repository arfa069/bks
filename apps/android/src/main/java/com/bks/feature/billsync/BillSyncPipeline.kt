package com.bks.feature.billsync

import com.bks.data.local.CaptureReason
import com.bks.data.local.ConfidenceState
import com.bks.data.local.PaymentSource
import com.bks.feature.toReviewCaptureReasonLabel
import com.bks.feature.toReviewSourceLabel
import com.bks.feature.review.ReviewQueueEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BillSyncResult(
    val recognized: Boolean,
    val createdEntries: List<ReviewQueueEntry>,
    val failureReason: BillSyncFailureReason? = null,
    val insertedCount: Int = 0
)

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    fun sync(
        pageText: String,
        capturedAtEpochMillis: Long
    ): BillSyncResult {
        val parseResult = parser.parseWithDiagnostics(
            pageText,
            captureTimeFormatter(capturedAtEpochMillis)
        )
        if (parseResult.entries.isEmpty()) {
            return BillSyncResult(
                recognized = false,
                createdEntries = emptyList(),
                failureReason = requireNotNull(parseResult.failureReason)
            )
        }

        return BillSyncResult(
            recognized = true,
            createdEntries = parseResult.entries.map { it.toPendingEntry(capturedAtEpochMillis) }
        )
    }

    private fun ParsedBillEntry.toPendingEntry(capturedAtEpochMillis: Long): ReviewQueueEntry =
        ReviewQueueEntry(
            id = stableId(capturedAtEpochMillis),
            title = merchantTitle,
            amountMinor = amountMinor,
            transactionTimeText = transactionTimeText,
            category = "",
            fundingAccountLabel = "",
            sourceLabel = PaymentSource.OTHER.toReviewSourceLabel(),
            kindLabel = transactionKindLabel,
            captureReasonLabel = CaptureReason.ACCESSIBILITY_AUTO.toReviewCaptureReasonLabel(),
            confidence = ConfidenceState.NEEDS_REVIEW,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureTimeText = captureTimeFormatter(capturedAtEpochMillis),
            note = "商户未识别，请人工确认".takeIf { merchantTitleFromFallback },
            rawEvidenceText = "",
            parsedFields = parsedFields
        )

    private fun ParsedBillEntry.stableId(capturedAtEpochMillis: Long): String =
        "bill-$capturedAtEpochMillis-$transactionTimeText-$amountMinor-$transactionKindLabel-$merchantTitle".hashCode()
            .let { "bill-$it" }
}

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
