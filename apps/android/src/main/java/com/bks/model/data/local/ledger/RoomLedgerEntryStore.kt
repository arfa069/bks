package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.DEFAULT_CATEGORY_ID
import com.bks.model.domain.ledger.DELETED_LEDGER_ENTRY_RETENTION_MILLIS
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.data.local.backup.validated
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

internal class RoomLedgerEntryStore(
    private val database: BksDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val ledgerBookStore: RoomLedgerBookStore,
    private val fundingAccountStore: RoomFundingAccountStore
) {
    private val syncRecorder = LocalSyncMutationRecorder(database, clock, idGenerator)
    fun ledgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeLedgerEntriesForBook(ledgerBookId)

    fun deletedLedgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeDeletedLedgerEntriesForBook(ledgerBookId)

    suspend fun createManualEntry(
        ledgerBookId: String,
        input: LedgerEntryCommand
    ): LedgerEntryEntity = database.withTransaction {
        val targetLedgerBook = ledgerBookStore.requireForWrite(ledgerBookId)
        val validated = input.validated(clock())
        val fundingAccountId = fundingAccountStore.resolve(validated)
        val now = clock()
        val entry = LedgerEntryEntity(
            id = idGenerator(),
            ledgerBookId = targetLedgerBook.id,
            paymentSource = validated.paymentSource,
            originalCaptureSource = null,
            entryOrigin = EntryOrigin.MANUAL,
            originPendingEntryId = null,
            flowDirection = validated.flowDirection,
            transactionKind = validated.transactionKind,
            amountMinor = validated.amountMinor,
            currency = SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: DEFAULT_CATEGORY_ID,
            fundingAccountId = fundingAccountId,
            note = validated.note?.trim()?.ifBlank { null },
            evidenceSummary = null,
            parsedFieldsText = null,
            confirmedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            deletedAtEpochMillis = null
        )
        database.ledgerEntryDao().upsert(entry)
        syncRecorder.record(entry)
        entry
    }

    suspend fun updateLedgerEntry(
        ledgerEntryId: String,
        input: LedgerEntryCommand
    ): LedgerEntryEntity = database.withTransaction {
        val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
            "Ledger entry not found: $ledgerEntryId"
        }
        require(existing.deletedAtEpochMillis == null) { "Deleted ledger entry cannot be edited" }
        val validated = input.validated(clock())
        val updated = existing.copy(
            paymentSource = validated.paymentSource,
            flowDirection = validated.flowDirection,
            transactionKind = validated.transactionKind,
            amountMinor = validated.amountMinor,
            currency = SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: DEFAULT_CATEGORY_ID,
            fundingAccountId = fundingAccountStore.resolve(validated),
            note = validated.note?.trim()?.ifBlank { null },
            updatedAtEpochMillis = clock()
        )
        database.ledgerEntryDao().upsert(updated)
        syncRecorder.record(updated)
        updated
    }

    suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            require(existing.deletedAtEpochMillis == null) { "Ledger entry is already deleted" }
            val deletedAt = clock()
            check(database.ledgerEntryDao().moveToDeleted(ledgerEntryId, deletedAt) == 1)
            existing.copy(deletedAtEpochMillis = deletedAt).also { syncRecorder.record(it) }
        }

    suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            val deletedAt = requireNotNull(existing.deletedAtEpochMillis) {
                "Ledger entry is not deleted"
            }
            require(deletedAt > clock() - DELETED_LEDGER_ENTRY_RETENTION_MILLIS) {
                "Ledger entry recovery period has expired"
            }
            check(database.ledgerEntryDao().restoreDeleted(ledgerEntryId) == 1)
            existing.copy(deletedAtEpochMillis = null).also { syncRecorder.record(it) }
        }

    suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) = database.withTransaction {
        check(database.ledgerEntryDao().permanentlyDelete(ledgerEntryId) == 1) {
            "Only a deleted ledger entry can be permanently deleted"
        }
        syncRecorder.recordDelete(
            com.bks.api.LedgerSyncEntityTypeContract.LEDGER_ENTRY,
            ledgerEntryId
        )
    }

    suspend fun purgeExpiredDeletedLedgerEntries(nowEpochMillis: Long): Int =
        database.withTransaction {
            val cutoff = nowEpochMillis - DELETED_LEDGER_ENTRY_RETENTION_MILLIS
            val expired = database.ledgerEntryDao().listDeletedBefore(cutoff)
            syncRecorder.recordDeletes(
                com.bks.api.LedgerSyncEntityTypeContract.LEDGER_ENTRY,
                expired.map(LedgerEntryEntity::id)
            )
            database.ledgerEntryDao().purgeDeletedBefore(cutoff)
        }
}

private fun LedgerEntryCommand.validated(now: Long): LedgerEntryCommand {
    require(amountMinor > 0) { "Amount must be greater than zero" }
    require(transactionTimeEpochMillis <= now) { "Transaction time cannot be in the future" }
    return this
}

private const val SUPPORTED_CURRENCY = "CNY"
