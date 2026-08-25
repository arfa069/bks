package com.bks.feature.ledger

import com.bks.data.local.CategoryEntity
import com.bks.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.bks.data.local.EntryOrigin
import com.bks.data.local.FlowDirection
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import com.bks.ui.entry.FundingAccountUiModel
import com.bks.ui.entry.LedgerCategoryUiModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

internal data class LedgerUiState(
    val entries: List<LedgerUiEntry> = emptyList(),
    val deletedEntries: List<LedgerUiEntry> = emptyList(),
    val categories: List<LedgerCategoryUiModel> = emptyList(),
    val fundingAccounts: List<FundingAccountUiModel> = emptyList(),
    val ledgerBooks: List<LedgerBookUiModel> = emptyList(),
    val activeLedgerBookId: String? = null,
    val activeLedgerName: String = DEFAULT_LEDGER_BOOK_NAME,
    val defaultFundingAccountSyncId: String? = null,
    val report: LedgerReportUiModel = LedgerReportUiModel(),
    val localDataReplacementInFlight: Boolean = false
)

internal fun CategoryEntity.toLedgerCategoryUiModel() = LedgerCategoryUiModel(
    id = id,
    name = name,
    kind = kind
)

internal fun FundingAccountEntity.toFundingAccountUiModel() = FundingAccountUiModel(
    id = id,
    syncId = syncId,
    paymentSource = paymentSource,
    label = label
)

internal enum class LedgerView {
    LIST,
    EDIT,
    DELETED,
    LEDGER_BOOKS,
    FUNDING_ACCOUNTS
}

internal data class LedgerPage(
    val view: LedgerView,
    val selectedEntryId: String?
)

internal fun LedgerUiEntry.remainingRetentionDays(nowEpochMillis: Long): Long {
    val deletedAt = deletedAtEpochMillis ?: return 0
    val remaining = deletedAt + LocalLedgerRepository.DELETED_RETENTION_MILLIS - nowEpochMillis
    return max(0, (remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY)
}

internal fun formatLedgerEpoch(epochMillis: Long): String =
    if (epochMillis <= 0) "—" else LEDGER_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

internal fun PaymentSource?.labelOrNone(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
    null -> "未指定"
}

internal fun FlowDirection.label(): String = when (this) {
    FlowDirection.INFLOW -> "流入"
    FlowDirection.OUTFLOW -> "流出"
    FlowDirection.NEUTRAL -> "不计收支"
}

internal fun TransactionKind.label(): String = when (this) {
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

internal fun EntryOrigin.label(): String = when (this) {
    EntryOrigin.MANUAL -> "手动录入"
    EntryOrigin.NOTIFICATION -> "通知捕获"
    EntryOrigin.ACCESSIBILITY_AUTO -> "历史采集"
    EntryOrigin.BILL_SYNC -> "补录账单"
    EntryOrigin.DUPLICATE_MERGE -> "重复合并"
    EntryOrigin.LEGACY_CAPTURE -> "旧版采集（方式未知）"
}

internal fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"

private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
