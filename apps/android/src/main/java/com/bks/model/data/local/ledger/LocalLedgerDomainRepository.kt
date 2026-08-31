package com.bks.model.data.local.ledger

import com.bks.model.domain.ledger.FundingAccountRemovalResult
import com.bks.model.domain.ledger.LedgerBookRemovalResult
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.ledger.LedgerSnapshot
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.flow.map

class LocalLedgerDomainRepository(
    private val local: LocalLedgerRepository
) : LedgerRepository {
    override val snapshot = local.state.map { state ->
        LedgerSnapshot(
            ledgerBooks = state.ledgerBooks.map { it.toDomain() },
            activeLedgerBook = state.activeLedgerBook?.toDomain(),
            ledgerEntries = state.ledgerEntries.map { it.toDomain() },
            deletedLedgerEntries = state.deletedLedgerEntries.map { it.toDomain() },
            categories = state.categories.map { it.toDomain() },
            fundingAccounts = state.fundingAccounts.map { it.toDomain() },
            defaultFundingAccountSyncId = state.defaultFundingAccountSyncId
        )
    }

    override suspend fun ensureDefaultLedgerBook() = local.ensureDefaultLedgerBook().toDomain()
    override suspend fun seedSystemCategories() = local.seedSystemCategories()
    override suspend fun purgeExpiredDeletedLedgerEntries() = local.purgeExpiredDeletedLedgerEntries()
    override suspend fun createManualEntry(ledgerBookId: String, command: LedgerEntryCommand) =
        local.createManualEntry(ledgerBookId, command.toLocalInput()).toDomain()
    override suspend fun updateLedgerEntry(ledgerEntryId: String, command: LedgerEntryCommand) =
        local.updateLedgerEntry(ledgerEntryId, command.toLocalInput()).toDomain()
    override suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String) =
        local.moveLedgerEntryToDeleted(ledgerEntryId).toDomain()
    override suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String) =
        local.restoreDeletedLedgerEntry(ledgerEntryId).toDomain()
    override suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) =
        local.permanentlyDeleteLedgerEntry(ledgerEntryId)
    override suspend fun createLedgerBook(name: String) = local.createLedgerBook(name).toDomain()
    override suspend fun selectLedgerBook(ledgerBookId: String) {
        local.selectLedgerBook(ledgerBookId)
    }
    override suspend fun deleteLedgerBook(ledgerBookId: String) = when (val result = local.deleteLedgerBook(ledgerBookId)) {
        LedgerBookDeleteResult.Deleted -> LedgerBookRemovalResult.Deleted
        LedgerBookDeleteResult.NotFound -> LedgerBookRemovalResult.NotFound
        LedgerBookDeleteResult.LastLedgerBook -> LedgerBookRemovalResult.LastLedgerBook
        is LedgerBookDeleteResult.NotEmpty -> LedgerBookRemovalResult.NotEmpty(
            result.activeEntryCount,
            result.deletedEntryCount
        )
    }
    override suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?) =
        local.createFundingAccount(label, paymentSource).toDomain()
    override suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ) = local.updateFundingAccount(fundingAccountId, label, paymentSource).toDomain()
    override suspend fun deleteFundingAccount(fundingAccountId: Long) = when (
        val result = local.deleteFundingAccount(fundingAccountId)
    ) {
        FundingAccountDeleteResult.Deleted -> FundingAccountRemovalResult.Deleted
        FundingAccountDeleteResult.NotFound -> FundingAccountRemovalResult.NotFound
        is FundingAccountDeleteResult.Referenced -> FundingAccountRemovalResult.Referenced(
            result.activeLedgerEntryCount,
            result.deletedLedgerEntryCount,
            result.pendingEntryCount,
            result.ignoredEntryCount
        )
    }
    override suspend fun clearLocalData() = local.clearLocalData()
}

private fun LedgerEntryCommand.toLocalInput() = LedgerEntryInput(
    flowDirection, transactionKind, amountMinor, transactionTimeEpochMillis, merchantTitle,
    categoryId, fundingAccountId, newFundingAccountLabel, note, paymentSource
)
