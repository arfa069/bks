package com.bks.model.domain.ledger

import kotlinx.coroutines.flow.Flow

interface LedgerRepository : LedgerBookRepository, LedgerEntryRepository, FundingAccountRepository {
    val snapshot: Flow<LedgerSnapshot>

    suspend fun clearLocalData()
}

interface LedgerBookRepository {
    suspend fun ensureDefaultLedgerBook(): LedgerBook
    suspend fun seedSystemCategories()
    suspend fun createLedgerBook(name: String): LedgerBook
    suspend fun selectLedgerBook(ledgerBookId: String)
    suspend fun deleteLedgerBook(ledgerBookId: String): LedgerBookRemovalResult
}

interface LedgerEntryRepository {
    suspend fun purgeExpiredDeletedLedgerEntries(): Int
    suspend fun createManualEntry(ledgerBookId: String, command: LedgerEntryCommand): LedgerEntry
    suspend fun updateLedgerEntry(ledgerEntryId: String, command: LedgerEntryCommand): LedgerEntry
    suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String): LedgerEntry
    suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String): LedgerEntry
    suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String)
}

interface FundingAccountRepository : FundingAccountSettingsRepository {
    suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?): FundingAccount
    suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccount
    suspend fun deleteFundingAccount(fundingAccountId: Long): FundingAccountRemovalResult
}

interface FundingAccountSettingsRepository {
    suspend fun setDefaultFundingAccount(fundingAccountId: Long?)
    suspend fun getDefaultFundingAccountSyncId(): String?
}
