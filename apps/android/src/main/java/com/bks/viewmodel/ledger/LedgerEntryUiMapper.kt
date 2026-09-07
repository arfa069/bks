package com.bks.viewmodel.ledger

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.FundingAccount
import com.bks.model.domain.ledger.LedgerEntry
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun LedgerEntry.toLedgerEntryUiModel(zoneId: ZoneId = ZoneId.systemDefault()): LedgerEntryUiModel {
    val transactionTimeText = formatLedgerDateTime(transactionTimeEpochMillis, zoneId)
    return LedgerEntryUiModel(
        id = id,
        title = merchantTitle,
        amountMinor = amountMinor,
        monthKey = transactionTimeText.take(7),
        transactionTimeText = transactionTimeText,
        category = categoryId?.let { DefaultCategories.nameForId(it) ?: it } ?: "未分类",
        sourceLabel = paymentSource?.toLabel() ?: "未指定",
        kindLabel = transactionKind.toLabel(),
        flowType = when (flowDirection) {
            FlowDirection.INFLOW -> LedgerFlowType.INCOME
            FlowDirection.OUTFLOW -> LedgerFlowType.EXPENSE
            FlowDirection.NEUTRAL -> LedgerFlowType.NEUTRAL
        },
        note = note,
        paymentSource = paymentSource,
        originalCaptureSource = originalCaptureSource,
        entryOrigin = entryOrigin,
        originPendingEntryId = originPendingEntryId,
        flowDirection = flowDirection,
        transactionKind = transactionKind,
        transactionTimeEpochMillis = transactionTimeEpochMillis,
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        evidenceSummary = evidenceSummary,
        parsedFieldsText = parsedFieldsText,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis
    )
}

internal fun Category.toLedgerCategoryUiModel() = LedgerCategoryUiModel(
    id = id,
    name = name,
    kind = kind
)

internal fun FundingAccount.toFundingAccountUiModel() = FundingAccountUiModel(
    id = id,
    syncId = syncId,
    paymentSource = paymentSource,
    label = label
)

private fun PaymentSource.toLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
}

private fun TransactionKind.toLabel(): String = when (this) {
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

private fun formatLedgerDateTime(epochMillis: Long, zoneId: ZoneId): String =
    LEDGER_DATE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
