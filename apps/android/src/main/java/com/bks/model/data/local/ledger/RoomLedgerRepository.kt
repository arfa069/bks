package com.bks.model.data.local.ledger

import androidx.room.withTransaction
import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.data.local.settings.DefaultCategorizationRules
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.FundingAccountRemovalResult
import com.bks.model.domain.ledger.LedgerBookRemovalResult
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.ledger.LedgerSnapshot
import com.bks.model.domain.ledger.PaymentSource
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class RoomLedgerRepository private constructor(
    private val database: BksDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val ledgerBookStore: RoomLedgerBookStore,
    private val ledgerEntryStore: RoomLedgerEntryStore,
    private val fundingAccountStore: RoomFundingAccountStore
) : LedgerRepository {
    constructor(
        database: BksDatabase,
        clock: () -> Long = { System.currentTimeMillis() },
        idGenerator: () -> String = { UUID.randomUUID().toString() }
    ) : this(
        database = database,
        clock = clock,
        idGenerator = idGenerator,
        ledgerBookStore = RoomLedgerBookStore(database, clock, idGenerator),
        fundingAccountStore = RoomFundingAccountStore(database, clock, idGenerator)
    )

    private constructor(
        database: BksDatabase,
        clock: () -> Long,
        idGenerator: () -> String,
        ledgerBookStore: RoomLedgerBookStore,
        fundingAccountStore: RoomFundingAccountStore
    ) : this(
        database = database,
        clock = clock,
        idGenerator = idGenerator,
        ledgerBookStore = ledgerBookStore,
        ledgerEntryStore = RoomLedgerEntryStore(
            database = database,
            clock = clock,
            idGenerator = idGenerator,
            ledgerBookStore = ledgerBookStore,
            fundingAccountStore = fundingAccountStore
        ),
        fundingAccountStore = fundingAccountStore
    )

    override val snapshot = ledgerBookStore.activeLedgerBook.flatMapLatest { activeLedgerBook ->
        combine(
            database.ledgerBookDao().observeEntryCounts(),
            activeLedgerBook?.let { ledgerEntryStore.ledgerEntries(it.id) } ?: flowOf(emptyList()),
            activeLedgerBook?.let { ledgerEntryStore.deletedLedgerEntries(it.id) } ?: flowOf(emptyList()),
            database.categoryDao().observeCategories(),
            fundingAccountStore.fundingAccounts
        ) { ledgerBooks, ledgerEntries, deletedLedgerEntries, categories, fundingAccounts ->
            LedgerSnapshot(
                ledgerBooks = ledgerBooks.map { it.toDomain() },
                activeLedgerBook = activeLedgerBook?.toDomain(),
                ledgerEntries = ledgerEntries.map { it.toDomain() },
                deletedLedgerEntries = deletedLedgerEntries.map { it.toDomain() },
                categories = categories.map { it.toDomain() },
                fundingAccounts = fundingAccounts.map { it.toDomain() }
            )
        }.combine(database.localSettingsDao().observeById()) { ledgerSnapshot, settings ->
            ledgerSnapshot.copy(defaultFundingAccountSyncId = settings?.defaultFundingAccountSyncId)
        }
    }

    override suspend fun ensureDefaultLedgerBook() = ledgerBookStore.ensureDefaultLedgerBook().toDomain()

    override suspend fun seedSystemCategories() {
        val defaults = DefaultCategories.systemDefaults(clock()).map(Category::toEntity)
        database.withTransaction {
            val recorder = LocalSyncMutationRecorder(database, clock, idGenerator)
            database.categoryDao().insertIgnore(defaults)
            defaults.forEach { category ->
                database.categoryDao().updateSystemCategory(
                    id = category.id,
                    name = category.name,
                    kind = category.kind,
                    sortOrder = category.sortOrder
                )
                database.categoryDao().getCategory(category.id)?.let { recorder.record(it) }
            }
        }
    }

    override suspend fun createLedgerBook(name: String) = ledgerBookStore.createLedgerBook(name).toDomain()

    override suspend fun selectLedgerBook(ledgerBookId: String) {
        ledgerBookStore.selectLedgerBook(ledgerBookId)
    }

    override suspend fun deleteLedgerBook(ledgerBookId: String): LedgerBookRemovalResult =
        when (val result = ledgerBookStore.deleteLedgerBook(ledgerBookId)) {
            RoomLedgerBookRemovalResult.Deleted -> LedgerBookRemovalResult.Deleted
            RoomLedgerBookRemovalResult.NotFound -> LedgerBookRemovalResult.NotFound
            RoomLedgerBookRemovalResult.LastLedgerBook -> LedgerBookRemovalResult.LastLedgerBook
            is RoomLedgerBookRemovalResult.NotEmpty -> LedgerBookRemovalResult.NotEmpty(
                activeEntryCount = result.activeEntryCount,
                deletedEntryCount = result.deletedEntryCount
            )
        }

    override suspend fun purgeExpiredDeletedLedgerEntries() =
        ledgerEntryStore.purgeExpiredDeletedLedgerEntries(clock())

    override suspend fun createManualEntry(ledgerBookId: String, command: LedgerEntryCommand) =
        ledgerEntryStore.createManualEntry(ledgerBookId, command).toDomain()

    override suspend fun updateLedgerEntry(ledgerEntryId: String, command: LedgerEntryCommand) =
        ledgerEntryStore.updateLedgerEntry(ledgerEntryId, command).toDomain()

    override suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String) =
        ledgerEntryStore.moveLedgerEntryToDeleted(ledgerEntryId).toDomain()

    override suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String) =
        ledgerEntryStore.restoreDeletedLedgerEntry(ledgerEntryId).toDomain()

    override suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) =
        ledgerEntryStore.permanentlyDeleteLedgerEntry(ledgerEntryId)

    override suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?) =
        fundingAccountStore.createFundingAccount(label, paymentSource).toDomain()

    override suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ) = fundingAccountStore.updateFundingAccount(fundingAccountId, label, paymentSource).toDomain()

    override suspend fun deleteFundingAccount(fundingAccountId: Long): FundingAccountRemovalResult =
        fundingAccountStore.deleteFundingAccount(fundingAccountId)

    override suspend fun setDefaultFundingAccount(fundingAccountId: Long?) =
        fundingAccountStore.setDefaultFundingAccount(fundingAccountId)

    override suspend fun getDefaultFundingAccountSyncId(): String? =
        fundingAccountStore.getDefaultFundingAccountSyncId()

    override suspend fun clearLocalData() = database.withTransaction {
        database.ledgerEntryDao().deleteAll()
        database.pendingEntryDao().deleteAll()
        database.ignoredEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.localSettingsDao().deleteAll()
        database.defaultFundingAccountCacheDao().deleteAll()
        database.ledgerBookDao().insert(ledgerBookStore.defaultLedgerBook())
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()).map(Category::toEntity))
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            )
        )
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }
}
