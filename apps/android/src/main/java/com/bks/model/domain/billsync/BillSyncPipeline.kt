package com.bks.model.domain.billsync

import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.ledger.categorizationLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BillSyncResult(
    val recognized: Boolean,
    val createdEntries: List<PendingEntry>,
    val failureReason: BillSyncFailureReason? = null,
    val insertedCount: Int = 0
)

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    fun sync(
        pageText: String,
        capturedAtEpochMillis: Long,
        paymentSource: PaymentSource
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
            createdEntries = parseResult.entries.map {
                it.toPendingEntry(capturedAtEpochMillis, paymentSource)
            }
        )
    }

    private fun ParsedBillEntry.toPendingEntry(
        capturedAtEpochMillis: Long,
        paymentSource: PaymentSource
    ): PendingEntry =
        PendingEntry(
            id = stableId(capturedAtEpochMillis),
            source = paymentSource,
            captureReason = CaptureReason.ACCESSIBILITY_AUTO,
            confidence = ConfidenceState.NEEDS_REVIEW,
            transactionKind = transactionKindLabel.toTransactionKind(),
            amountMinor = amountMinor,
            currency = "CNY",
            merchantTitle = merchantTitle,
            transactionTimeEpochMillis = parseTransactionTime(transactionTimeText) ?: capturedAtEpochMillis,
            capturedAtEpochMillis = capturedAtEpochMillis,
            suggestedCategoryId = null,
            fundingAccountId = null,
            fundingAccountLabel = null,
            note = "商户未识别，请人工确认".takeIf { merchantTitleFromFallback },
            evidenceSummary = null,
            parsedFieldsText = (listOf("来源=${paymentSource.categorizationLabel()}") + parsedFields)
                .joinToString("\n"),
            suggestedCategoryLabel = null
        )

    private fun ParsedBillEntry.stableId(capturedAtEpochMillis: Long): String =
        "bill-$capturedAtEpochMillis-$transactionTimeText-$amountMinor-$transactionKindLabel-$merchantTitle".hashCode()
            .let { "bill-$it" }
}

private fun String.toTransactionKind(): TransactionKind = when (trim()) {
    "收入" -> TransactionKind.INCOME
    "退款" -> TransactionKind.REFUND
    "转账" -> TransactionKind.TRANSFER
    "红包" -> TransactionKind.RED_PACKET
    "还款" -> TransactionKind.REPAYMENT
    "理财" -> TransactionKind.INVESTMENT
    "手续费" -> TransactionKind.FEE
    "其他" -> TransactionKind.OTHER
    else -> TransactionKind.EXPENSE
}

private fun parseTransactionTime(value: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(value)?.time
}.getOrNull()

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
