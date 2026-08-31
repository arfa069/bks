package com.bks.viewmodel.review

import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.IgnoredEntry
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.review.ReviewQueueSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun ReviewQueueSnapshot.toUiState(zoneId: ZoneId): ReviewQueueState = ReviewQueueState(
    pendingEntries = pendingEntries.map { it.toUiModel(zoneId) },
    ignoredEntries = ignoredEntries.map { it.toUiModel(zoneId) },
    nowEpochMillis = nowEpochMillis,
    todayStartEpochMillis = todayStartEpochMillis
)

internal fun PendingEntry.toUiModel(zoneId: ZoneId): ReviewQueueEntry = ReviewQueueEntry(
    id = id,
    title = merchantTitle,
    amountMinor = amountMinor,
    transactionTimeText = formatReviewDateTime(transactionTimeEpochMillis, zoneId),
    categoryId = suggestedCategoryId,
    category = suggestedCategoryLabel
        ?: suggestedCategoryId?.let(DefaultCategories::nameForId)
        ?: suggestedCategoryId.orEmpty(),
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel ?: fundingAccountId?.let { "账户 $it" }.orEmpty(),
    sourceLabel = source.reviewLabel(),
    kindLabel = transactionKind.reviewLabel(),
    captureReasonLabel = captureReason.reviewLabel(),
    confidence = confidence,
    capturedAtEpochMillis = capturedAtEpochMillis,
    captureTimeText = formatReviewDateTime(capturedAtEpochMillis, zoneId),
    note = note.withoutLegacyDedupeMessage(confidence, captureReason),
    rawEvidenceText = evidenceSummary.orEmpty(),
    parsedFields = parsedFieldsText?.takeIf(String::isNotBlank)?.split("\n").orEmpty()
)

internal fun IgnoredEntry.toUiModel(zoneId: ZoneId) = ReviewQueueIgnoredEntry(
    id = id,
    originalPendingId = originalPendingEntryId,
    entry = PendingEntry(
        originalPendingEntryId, source, captureReason, confidence, transactionKind,
        amountMinor, currency, merchantTitle, transactionTimeEpochMillis, capturedAtEpochMillis,
        suggestedCategoryId, fundingAccountId, fundingAccountLabel, note, evidenceSummary,
        parsedFieldsText, suggestedCategoryLabel
    ).toUiModel(zoneId),
    ignoredAtEpochMillis = ignoredAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis
)

internal fun formatReviewDateTime(epochMillis: Long, zoneId: ZoneId): String =
    REVIEW_DATE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

internal fun parseReviewDateTime(text: String, zoneId: ZoneId): Long? = runCatching {
    LocalDateTime.parse(text.trim(), REVIEW_DATE_TIME_FORMATTER)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}.getOrNull()

internal fun String.toTransactionKind(): TransactionKind = when (trim()) {
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

internal fun TransactionKind.reviewLabel(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

private fun PaymentSource.reviewLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
}

private fun CaptureReason.reviewLabel(): String = when (this) {
    CaptureReason.NOTIFICATION -> "通知捕获"
    CaptureReason.ACCESSIBILITY_AUTO -> "支付结果自动捕获"
    CaptureReason.BILL_SYNC -> "补录账单"
    CaptureReason.DUPLICATE_MERGE -> "重复合并"
    CaptureReason.MANUAL_SAMPLE -> "手动样例"
}

private fun String?.withoutLegacyDedupeMessage(
    confidence: com.bks.model.domain.ledger.ConfidenceState,
    captureReason: CaptureReason
): String? {
    val value = this ?: return null
    val isLegacySuspectWarning = confidence ==
        com.bks.model.domain.ledger.ConfidenceState.DUPLICATE_SUSPECT &&
        value.startsWith("可能与 ") &&
        value.endsWith(" 重复，请确认后再入账")
    val isLegacyMergeMessage = captureReason == CaptureReason.DUPLICATE_MERGE &&
        value.startsWith("已合并") &&
        value.endsWith("证据")
    return value.takeUnless { isLegacySuspectWarning || isLegacyMergeMessage }
}

private val REVIEW_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
