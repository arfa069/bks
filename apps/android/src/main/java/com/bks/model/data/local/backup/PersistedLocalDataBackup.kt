package com.bks.model.data.local.backup

import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategoryDao
import com.bks.model.data.local.database.FundingAccountDao
import com.bks.model.data.local.database.IgnoredEntryDao
import com.bks.model.data.local.database.LedgerBookDao
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.database.LocalSettingsDao
import com.bks.model.data.local.database.PendingEntryDao
import androidx.room.withTransaction
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.CategorizationRuleEntity
import com.bks.model.data.local.database.CategoryEntity
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bks.model.domain.settings.DataBackupRepository

data class PersistedLocalDataSnapshot(
    val categories: List<CategoryEntity>,
    val fundingAccounts: List<FundingAccountEntity>,
    val pendingEntries: List<PendingEntryEntity>,
    val ledgerEntries: List<LedgerEntryEntity>,
    val ignoredEntries: List<IgnoredEntryEntity>,
    val categorizationRules: List<CategorizationRuleEntity>,
    val settings: LocalSettingsEntity?,
    val ledgerBooks: List<LedgerBookEntity> = listOf(defaultBackupLedgerBook())
)

class LocalDataBackupRepository(
    private val database: BksDatabase
) : DataBackupRepository {
    override suspend fun exportEncryptedBackup(passphrase: String): String = withContext(Dispatchers.Default) {
        val snapshot = database.withTransaction {
            val ledgerBooks = database.ledgerBookDao().getAll()
                .ifEmpty { listOf(defaultBackupLedgerBook()) }
            PersistedLocalDataSnapshot(
                categories = database.categoryDao().getAllCategories(),
                fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
                pendingEntries = database.pendingEntryDao().listPendingEntries(),
                ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries(),
                ignoredEntries = database.ignoredEntryDao().listAll(),
                categorizationRules = database.categorizationRuleDao().listRules(),
                settings = (database.localSettingsDao().getById()
                    ?: defaultBackupSettings(ledgerBooks.first().id))
                    .copy(automaticBookkeepingEnabled = false),
                ledgerBooks = ledgerBooks
            )
        }
        encryptPersistedLocalData(snapshot, passphrase)
    }

    override suspend fun importEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        val snapshot = decodeAndValidate(backupText, passphrase)
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.ledgerEntryDao().deleteAll()
                database.pendingEntryDao().deleteAll()
                database.ignoredEntryDao().deleteAll()
                database.fundingAccountDao().deleteAll()
                database.categoryDao().deleteAll()
                database.ledgerBookDao().deleteAll()
                database.categorizationRuleDao().deleteAll()
                database.localSettingsDao().deleteAll()

                database.categoryDao().upsertAll(snapshot.categories)
                database.fundingAccountDao().upsertAll(snapshot.fundingAccounts)
                database.ledgerBookDao().insertAll(snapshot.ledgerBooks)
                database.pendingEntryDao().upsertAll(snapshot.pendingEntries)
                database.ledgerEntryDao().upsertAll(snapshot.ledgerEntries)
                database.ignoredEntryDao().upsertAll(snapshot.ignoredEntries)
                database.categorizationRuleDao().upsertAll(snapshot.categorizationRules)
                database.localSettingsDao().upsert(
                    requireNotNull(snapshot.settings).copy(automaticBookkeepingEnabled = false)
                )
                database.ledgerSyncDao().deleteAllConflicts()
                database.ledgerSyncDao().unblockAllMetadata()
                database.ledgerSyncDao().deleteAllOutbox()
                LocalSyncMutationRecorder(database, System::currentTimeMillis).reconcileAll()
            }
        }
    }

    override suspend fun validateEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        decodeAndValidate(backupText, passphrase)
    }

    private suspend fun decodeAndValidate(
        backupText: String,
        passphrase: String
    ): PersistedLocalDataSnapshot = withContext(Dispatchers.Default) {
        decryptPersistedLocalData(backupText, passphrase).validated()
    }
}

internal fun defaultBackupLedgerBook(): LedgerBookEntity = LedgerBookEntity(
    id = DEFAULT_LEDGER_BOOK_ID,
    name = DEFAULT_LEDGER_BOOK_NAME,
    createdAtEpochMillis = 0
)

internal fun defaultBackupSettings(activeLedgerId: String): LocalSettingsEntity =
    LocalSettingsEntity(
        aiConsentGranted = false,
        enhancedContextGranted = false,
        activeLedgerId = activeLedgerId
    )
