package com.bks.viewmodel.ledger

import com.bks.viewmodel.ledger.LedgerBookUiModel
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DEFAULT_LEDGER_NAME
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.DELETED_LEDGER_ENTRY_RETENTION_MILLIS
import com.bks.model.domain.ledger.FundingAccount
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
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
    val activeLedgerName: String = DEFAULT_LEDGER_NAME,
    val defaultFundingAccountSyncId: String? = null,
    val report: LedgerReportUiModel = LedgerReportUiModel(),
    val localDataReplacementInFlight: Boolean = false
)

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
    val remaining = deletedAt + DELETED_LEDGER_ENTRY_RETENTION_MILLIS - nowEpochMillis
    return max(0, (remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY)
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

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
