package com.bks.model.domain.ledger

data class Category(
    val id: String,
    val name: String,
    val kind: TransactionKind?,
    val sortOrder: Int,
    val isSystem: Boolean,
    val createdAtEpochMillis: Long
)

data class FundingAccount(
    val id: Long,
    val syncId: String?,
    val sourceScope: FundingAccountSourceScope,
    val paymentSource: PaymentSource?,
    val label: String,
    val createdAtEpochMillis: Long
)

data class LedgerBook(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long
)

data class LedgerBookSummary(
    val id: String,
    val name: String,
    val activeEntryCount: Int,
    val deletedEntryCount: Int
)

data class LedgerEntry(
    val id: String,
    val ledgerBookId: String,
    val paymentSource: PaymentSource?,
    val originalCaptureSource: PaymentSource?,
    val entryOrigin: EntryOrigin,
    val originPendingEntryId: String?,
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val currency: String,
    val merchantTitle: String,
    val transactionTimeEpochMillis: Long,
    val categoryId: String?,
    val fundingAccountId: Long?,
    val note: String?,
    val evidenceSummary: String?,
    val parsedFieldsText: String?,
    val confirmedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?
)

data class PendingEntry(
    val id: String,
    val source: PaymentSource,
    val captureReason: CaptureReason,
    val confidence: ConfidenceState,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val currency: String,
    val merchantTitle: String,
    val transactionTimeEpochMillis: Long,
    val capturedAtEpochMillis: Long,
    val suggestedCategoryId: String?,
    val fundingAccountId: Long?,
    val fundingAccountLabel: String?,
    val note: String?,
    val evidenceSummary: String?,
    val parsedFieldsText: String?,
    val suggestedCategoryLabel: String?
)

data class IgnoredEntry(
    val id: String,
    val originalPendingEntryId: String,
    val source: PaymentSource,
    val captureReason: CaptureReason,
    val confidence: ConfidenceState,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val currency: String,
    val merchantTitle: String,
    val transactionTimeEpochMillis: Long,
    val capturedAtEpochMillis: Long,
    val suggestedCategoryId: String?,
    val fundingAccountId: Long?,
    val fundingAccountLabel: String?,
    val note: String?,
    val evidenceSummary: String?,
    val parsedFieldsText: String?,
    val ignoredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val reason: IgnoreReason,
    val suggestedCategoryLabel: String?
)

data class LedgerEntryCommand(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String?,
    val fundingAccountId: Long?,
    val newFundingAccountLabel: String?,
    val note: String?,
    val paymentSource: PaymentSource?
)

data class LedgerSnapshot(
    val ledgerBooks: List<LedgerBookSummary> = emptyList(),
    val activeLedgerBook: LedgerBook? = null,
    val ledgerEntries: List<LedgerEntry> = emptyList(),
    val deletedLedgerEntries: List<LedgerEntry> = emptyList(),
    val categories: List<Category> = emptyList(),
    val fundingAccounts: List<FundingAccount> = emptyList(),
    val defaultFundingAccountSyncId: String? = null
)

sealed interface LedgerBookRemovalResult {
    data object Deleted : LedgerBookRemovalResult
    data object NotFound : LedgerBookRemovalResult
    data object LastLedgerBook : LedgerBookRemovalResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookRemovalResult
}

sealed interface FundingAccountRemovalResult {
    data object Deleted : FundingAccountRemovalResult
    data object NotFound : FundingAccountRemovalResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountRemovalResult
}

const val DEFAULT_LEDGER_NAME = "默认账本"
const val DEFAULT_LEDGER_ID = "default-ledger"
const val DEFAULT_CATEGORY_ID = "uncategorized"
const val DELETED_LEDGER_ENTRY_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
