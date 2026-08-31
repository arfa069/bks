package com.bks.viewmodel.ledger

data class LedgerBookUiModel(
    val id: String,
    val name: String,
    val activeEntryCount: Int = 0,
    val deletedEntryCount: Int = 0,
    val isActive: Boolean = false
) {
    val totalEntryCount: Int
        get() = activeEntryCount + deletedEntryCount
}

sealed interface LedgerBookDeleteResult {
    data object Deleted : LedgerBookDeleteResult
    data object LastLedger : LedgerBookDeleteResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookDeleteResult
}

sealed interface FundingAccountDeleteResult {
    data object Deleted : FundingAccountDeleteResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountDeleteResult
}
