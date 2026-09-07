package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_NAME
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import androidx.room.withTransaction

internal sealed interface RoomLedgerBookRemovalResult {
    data object Deleted : RoomLedgerBookRemovalResult
    data object NotFound : RoomLedgerBookRemovalResult
    data object LastLedgerBook : RoomLedgerBookRemovalResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : RoomLedgerBookRemovalResult
}

internal class RoomLedgerBookStore(
    private val database: BksDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) {
    private val syncRecorder = LocalSyncMutationRecorder(database, clock, idGenerator)
    val activeLedgerBook = database.ledgerBookDao().observeActive()

    suspend fun ensureDefaultLedgerBook(): LedgerBookEntity = database.withTransaction {
        ensureLedgerBookState()
    }

    suspend fun createLedgerBook(name: String): LedgerBookEntity = database.withTransaction {
        ensureLedgerBookState()
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Ledger book name is required" }
        require(database.ledgerBookDao().findByName(normalizedName) == null) {
            "Ledger book name already exists"
        }
        val ledgerBook = LedgerBookEntity(
            id = idGenerator(),
            name = normalizedName,
            createdAtEpochMillis = clock()
        )
        database.ledgerBookDao().insert(ledgerBook)
        database.localSettingsDao().upsert(
            currentSettingsEntity(ledgerBook.id).copy(activeLedgerId = ledgerBook.id)
        )
        syncRecorder.record(ledgerBook)
        ledgerBook
    }

    suspend fun selectLedgerBook(ledgerBookId: String): LedgerBookEntity =
        database.withTransaction {
            val ledgerBook = requireNotNull(database.ledgerBookDao().getById(ledgerBookId)) {
                "Ledger book not found: $ledgerBookId"
            }
            database.localSettingsDao().upsert(
                currentSettingsEntity(ledgerBook.id).copy(activeLedgerId = ledgerBook.id)
            )
            ledgerBook
        }

    suspend fun deleteLedgerBook(ledgerBookId: String): RoomLedgerBookRemovalResult =
        database.withTransaction {
            val ledgerBooks = database.ledgerBookDao().getAll()
            val target = ledgerBooks.firstOrNull { it.id == ledgerBookId }
                ?: return@withTransaction RoomLedgerBookRemovalResult.NotFound
            if (ledgerBooks.size <= 1) {
                return@withTransaction RoomLedgerBookRemovalResult.LastLedgerBook
            }
            val activeEntryCount =
                database.ledgerEntryDao().countActiveByLedgerBookId(target.id)
            val deletedEntryCount =
                database.ledgerEntryDao().countDeletedByLedgerBookId(target.id)
            if (activeEntryCount > 0 || deletedEntryCount > 0) {
                return@withTransaction RoomLedgerBookRemovalResult.NotEmpty(
                    activeEntryCount = activeEntryCount,
                    deletedEntryCount = deletedEntryCount
                )
            }

            val settings = database.localSettingsDao().getById()
            val currentActiveId = ledgerBooks
                .firstOrNull { it.id == settings?.activeLedgerId }
                ?.id
                ?: ledgerBooks.first().id
            val nextActiveId = if (currentActiveId == target.id) {
                ledgerBooks.first { it.id != target.id }.id
            } else {
                currentActiveId
            }
            if (settings == null || settings.activeLedgerId != nextActiveId) {
                database.localSettingsDao().upsert(
                    currentSettingsEntity(nextActiveId).copy(activeLedgerId = nextActiveId)
                )
            }
            check(database.ledgerBookDao().deleteById(target.id) == 1)
            syncRecorder.recordDelete(
                com.bks.api.LedgerSyncEntityTypeContract.LEDGER_BOOK,
                target.id
            )
            RoomLedgerBookRemovalResult.Deleted
        }

    internal suspend fun requireForWrite(ledgerBookId: String): LedgerBookEntity {
        database.ledgerBookDao().getById(ledgerBookId)?.let { return it }
        if (
            ledgerBookId == DEFAULT_LEDGER_BOOK_ID &&
            database.ledgerBookDao().count() == 0
        ) {
            val defaultLedgerBook = defaultLedgerBook()
            database.ledgerBookDao().insert(defaultLedgerBook)
            database.localSettingsDao().upsert(
                currentSettingsEntity(defaultLedgerBook.id).copy(
                    activeLedgerId = defaultLedgerBook.id
                )
            )
            syncRecorder.record(defaultLedgerBook)
            return defaultLedgerBook
        }
        error("Ledger book not found: $ledgerBookId")
    }

    internal fun defaultLedgerBook(): LedgerBookEntity = LedgerBookEntity(
        id = DEFAULT_LEDGER_BOOK_ID,
        name = DEFAULT_LEDGER_BOOK_NAME,
        createdAtEpochMillis = clock()
    )

    private suspend fun ensureLedgerBookState(): LedgerBookEntity {
        val ledgerBooks = database.ledgerBookDao().getAll()
        val fallback = if (ledgerBooks.isEmpty()) {
            defaultLedgerBook().also {
                database.ledgerBookDao().insert(it)
                syncRecorder.record(it)
            }
        } else {
            ledgerBooks.first()
        }
        val settings = database.localSettingsDao().getById()
        val activeLedgerBook = ledgerBooks
            .firstOrNull { it.id == settings?.activeLedgerId }
            ?: fallback
        if (settings == null || settings.activeLedgerId != activeLedgerBook.id) {
            database.localSettingsDao().upsert(
                currentSettingsEntity(activeLedgerBook.id).copy(
                    activeLedgerId = activeLedgerBook.id
                )
            )
        }
        return activeLedgerBook
    }

    private suspend fun currentSettingsEntity(activeLedgerId: String): LocalSettingsEntity =
        database.localSettingsDao().getById() ?: LocalSettingsEntity(
            aiConsentGranted = false,
            enhancedContextGranted = false,
            activeLedgerId = activeLedgerId
        )
}
