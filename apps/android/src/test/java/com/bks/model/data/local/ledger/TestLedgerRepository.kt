package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.data.local.review.RoomReviewQueueRepository
import com.bks.model.domain.ledger.IgnoreReason
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.toFundingAccountSourceScope
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewPersistenceUndo
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Test-only database fixture. Production code only exposes domain repositories. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class TestLedgerRepository(
    private val database: BksDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val repository = RoomLedgerRepository(database, clock, idGenerator)
    val domainRepository: LedgerRepository
        get() = repository
    private val reviewRepository = RoomReviewQueueRepository(
        database = database,
        nowProvider = clock,
        zoneId = ZoneId.systemDefault(),
        idGenerator = idGenerator
    )
    val state = repository.snapshot
    val ledgerBooks = database.ledgerBookDao().observeAll()
    val activeLedgerBook = database.ledgerBookDao().observeActive()
    val ledgerEntries = activeLedgerBook.flatMapLatest { book ->
        book?.let { database.ledgerEntryDao().observeLedgerEntriesForBook(it.id) }
            ?: flowOf(emptyList())
    }
    val deletedLedgerEntries = activeLedgerBook.flatMapLatest { book ->
        book?.let { database.ledgerEntryDao().observeDeletedLedgerEntriesForBook(it.id) }
            ?: flowOf(emptyList())
    }
    val categories = database.categoryDao().observeCategories()
    val fundingAccounts = database.fundingAccountDao().observeFundingAccounts()
    val pendingEntries = database.pendingEntryDao().observePendingEntries()

    fun ledgerEntries(ledgerBookId: String) =
        database.ledgerEntryDao().observeLedgerEntriesForBook(ledgerBookId)

    fun deletedLedgerEntries(ledgerBookId: String) =
        database.ledgerEntryDao().observeDeletedLedgerEntriesForBook(ledgerBookId)

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

    suspend fun seedSystemCategories() = repository.seedSystemCategories()

    suspend fun ensureDefaultLedgerBook() = repository.ensureDefaultLedgerBook().let { domain ->
        requireNotNull(database.ledgerBookDao().getById(domain.id))
    }

    suspend fun createLedgerBook(name: String) = repository.createLedgerBook(name).let { domain ->
        requireNotNull(database.ledgerBookDao().getById(domain.id))
    }

    suspend fun selectLedgerBook(ledgerBookId: String) = repository.selectLedgerBook(ledgerBookId)

    suspend fun deleteLedgerBook(ledgerBookId: String) = repository.deleteLedgerBook(ledgerBookId)

    suspend fun createManualEntry(input: LedgerEntryCommand): LedgerEntryEntity {
        val ledgerBook = repository.ensureDefaultLedgerBook()
        return createManualEntry(ledgerBook.id, input)
    }

    suspend fun createManualEntry(
        ledgerBookId: String,
        input: LedgerEntryCommand
    ): LedgerEntryEntity = repository.createManualEntry(ledgerBookId, input).toEntity()

    suspend fun updateLedgerEntry(
        ledgerEntryId: String,
        input: LedgerEntryCommand
    ): LedgerEntryEntity = repository.updateLedgerEntry(ledgerEntryId, input).toEntity()

    suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String) =
        repository.moveLedgerEntryToDeleted(ledgerEntryId).toEntity()

    suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String) =
        repository.restoreDeletedLedgerEntry(ledgerEntryId).toEntity()

    suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) =
        repository.permanentlyDeleteLedgerEntry(ledgerEntryId)

    suspend fun purgeExpiredDeletedLedgerEntries() = repository.purgeExpiredDeletedLedgerEntries()

    suspend fun purgeExpiredDeletedLedgerEntries(nowEpochMillis: Long) =
        RoomLedgerRepository(database, { nowEpochMillis }, idGenerator)
            .purgeExpiredDeletedLedgerEntries()

    suspend fun listLedgerEntries() = database.ledgerEntryDao().listLedgerEntries()

    suspend fun listAllLedgerEntries() = database.ledgerEntryDao().listAllLedgerEntries()

    suspend fun listLedgerEntriesBetween(startEpochMillis: Long, endEpochMillis: Long) =
        database.ledgerEntryDao().listLedgerEntriesBetween(startEpochMillis, endEpochMillis)

    suspend fun getLedgerEntry(ledgerEntryId: String) = database.ledgerEntryDao().getById(ledgerEntryId)

    suspend fun ensureFundingAccount(source: PaymentSource, label: String): FundingAccountEntity {
        val normalizedLabel = label.trim()
        val existing = database.fundingAccountDao().findByScopeAndLabel(
            sourceScope = source.toFundingAccountSourceScope(),
            label = normalizedLabel
        )
        if (existing != null) return existing
        val created = repository.createFundingAccount(normalizedLabel, source)
        return requireNotNull(database.fundingAccountDao().getById(created.id))
    }

    suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?) =
        repository.createFundingAccount(label, paymentSource)

    suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ) = repository.updateFundingAccount(fundingAccountId, label, paymentSource)

    suspend fun deleteFundingAccount(fundingAccountId: Long) =
        repository.deleteFundingAccount(fundingAccountId)

    suspend fun setDefaultFundingAccount(fundingAccountId: Long?) =
        repository.setDefaultFundingAccount(fundingAccountId)

    suspend fun getDefaultFundingAccountSyncId() = repository.getDefaultFundingAccountSyncId()

    suspend fun upsertPending(entry: PendingEntryEntity) = database.pendingEntryDao().upsert(entry)

    suspend fun upsertIgnored(entry: IgnoredEntryEntity) = database.ignoredEntryDao().upsert(entry)

    suspend fun deletePending(pendingEntryId: String) = database.pendingEntryDao().deleteById(pendingEntryId)

    suspend fun deleteIgnored(ignoredEntryId: String) = database.ignoredEntryDao().deleteById(ignoredEntryId)

    suspend fun confirmPending(
        pendingEntryId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity {
        val ledgerBookId = ensureDefaultLedgerBook().id
        return confirmPending(pendingEntryId, ledgerBookId, categoryId, note, confirmedAtEpochMillis)
    }

    suspend fun confirmPending(
        pendingEntryId: String,
        ledgerBookId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity {
        val pending = requireNotNull(database.pendingEntryDao().getById(pendingEntryId))
        val edit = if (categoryId != null || note != null) {
            com.bks.model.domain.review.ReviewEntryEdit(
                title = pending.merchantTitle,
                amountMinor = pending.amountMinor,
                transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
                transactionKind = pending.transactionKind,
                categoryId = categoryId ?: pending.suggestedCategoryId,
                category = pending.suggestedCategoryLabel.orEmpty(),
                fundingAccountId = pending.fundingAccountId,
                fundingAccountLabel = pending.fundingAccountLabel.orEmpty(),
                note = note ?: pending.note
            )
        } else {
            null
        }
        val persistence = RoomReviewQueueRepository(
            database = database,
            nowProvider = { confirmedAtEpochMillis },
            zoneId = ZoneId.systemDefault(),
            idGenerator = idGenerator
        )
        val result = persistence.execute(
            ReviewPersistenceCommand.Confirm(pendingEntryId, ledgerBookId, edit)
        ) as ReviewPersistenceResult.Applied
        val undo = result.undo as ReviewPersistenceUndo.Confirmed
        return undo.receipt.ledgerEntry.toEntity()
    }

    suspend fun ignorePending(
        pendingEntryId: String,
        reason: IgnoreReason = IgnoreReason.USER_IGNORED,
        ignoredAtEpochMillis: Long = clock()
    ): IgnoredEntryEntity {
        require(reason == IgnoreReason.USER_IGNORED)
        val persistence = RoomReviewQueueRepository(
            database = database,
            nowProvider = { ignoredAtEpochMillis },
            zoneId = ZoneId.systemDefault(),
            idGenerator = idGenerator
        )
        val result = persistence.execute(ReviewPersistenceCommand.Ignore(pendingEntryId))
        check(result is ReviewPersistenceResult.Applied)
        return database.ignoredEntryDao().listByOriginalPendingEntryId(pendingEntryId).single()
    }

    suspend fun recoverIgnored(ignoredEntryId: String): PendingEntryEntity {
        val ignored = requireNotNull(database.ignoredEntryDao().getById(ignoredEntryId))
        val result = reviewRepository.execute(
            ReviewPersistenceCommand.Recover(ignoredEntryId, ignored.originalPendingEntryId)
        )
        check(result is ReviewPersistenceResult.Applied)
        return requireNotNull(database.pendingEntryDao().getById(ignored.originalPendingEntryId))
    }

    suspend fun clearLocalData() = repository.clearLocalData()
}
