package com.bks.model.data.local.settings

import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategorizationRuleEntity
import com.bks.model.data.local.database.DefaultFundingAccountCacheDao
import com.bks.model.data.local.database.DefaultFundingAccountCacheEntity
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.database.LocalSettingsDao
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.ledger.TransactionKind
import androidx.room.withTransaction
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.settings.CachedDefaultFundingAccount
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.domain.settings.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalPreferencesRepository(
    private val database: BksDatabase
) : PreferencesRepository {
    override val categorizationRules: Flow<List<CategorizationRule>> =
        database.categorizationRuleDao().observeRules().map { rules ->
            rules.map { it.toDomain() }
        }

    override val userPreferences: Flow<UserPreferences> =
        database.localSettingsDao().observeById().map { settings ->
            settings?.toDomain() ?: UserPreferences()
        }

    override suspend fun updateCategorizationRules(update: CategorizationRulesUpdate) = database.withTransaction {
        val previous = database.categorizationRuleDao().listRules()
        val rules = update(previous.map { it.toDomain() })
        val recorder = LocalSyncMutationRecorder(database, System::currentTimeMillis)
        val entities = rules.map { it.toEntity() }
        database.categorizationRuleDao().deleteAll()
        database.categorizationRuleDao().upsertAll(entities)
        previous.filter { old -> entities.none { it.id == old.id } }.forEach { old ->
            recorder.recordDelete(
                com.bks.api.LedgerSyncEntityTypeContract.CATEGORIZATION_RULE,
                old.id
            )
        }
        entities.forEach { recorder.record(it) }
    }

    override suspend fun seedDefaultCategorizationRules() = database.withTransaction {
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        val recorder = LocalSyncMutationRecorder(database, System::currentTimeMillis)
        DefaultCategorizationRules.rules.forEach { rule ->
            database.categorizationRuleDao().getById(rule.id)?.let { recorder.record(it) }
        }
    }

    override suspend fun updateAiSettings(aiSettings: AiCategorizationSettings) = database.withTransaction {
        val validSettings = if (aiSettings.aiConsentGranted) {
            aiSettings
        } else {
            AiCategorizationSettings()
        }
        val current = currentSettingsEntity()
        database.localSettingsDao().upsert(
            current.copy(
                aiConsentGranted = validSettings.aiConsentGranted,
                enhancedContextGranted = validSettings.enhancedContextGranted
            )
        )
    }

    override suspend fun setAutomaticBookkeepingEnabled(enabled: Boolean) = database.withTransaction {
        database.localSettingsDao().upsert(
            currentSettingsEntity().copy(automaticBookkeepingEnabled = enabled)
        )
    }

    override suspend fun clearLocalData() = database.withTransaction {
        database.categorizationRuleDao().deleteAll()
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().upsert(
            currentSettingsEntity().copy(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                defaultFundingAccountSyncId = null,
                automaticBookkeepingEnabled = false
            )
        )
        database.defaultFundingAccountCacheDao().deleteAll()
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }

    override suspend fun setDefaultFundingAccountSyncId(syncId: String?) = database.withTransaction {
        database.localSettingsDao().upsert(currentSettingsEntity().copy(defaultFundingAccountSyncId = syncId))
    }

    override suspend fun getDefaultFundingAccountSyncId(): String? =
        currentSettingsEntity().defaultFundingAccountSyncId

    override suspend fun cacheDefaultFundingAccount(accountKey: String, syncId: String?, pendingUpload: Boolean) =
        database.defaultFundingAccountCacheDao().upsert(
            DefaultFundingAccountCacheEntity(accountKey, syncId, pendingUpload, System.currentTimeMillis())
        )

    override suspend fun getCachedDefaultFundingAccount(accountKey: String): CachedDefaultFundingAccount? =
        database.defaultFundingAccountCacheDao().get(accountKey)?.toDomain()

    private suspend fun currentSettingsEntity(): LocalSettingsEntity =
        database.localSettingsDao().getById() ?: LocalSettingsEntity(
            aiConsentGranted = false,
            enhancedContextGranted = false
        )
}

private fun CategorizationRuleEntity.toDomain(): CategorizationRule = CategorizationRule(
    id = id,
    merchantContains = merchantContains,
    titleContains = titleContains,
    sourceLabel = sourceLabel,
    transactionKind = transactionKind,
    category = category,
    priority = priority,
    enabled = enabled,
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun CategorizationRule.toEntity(): CategorizationRuleEntity = CategorizationRuleEntity(
    id = id,
    merchantContains = merchantContains,
    titleContains = titleContains,
    sourceLabel = sourceLabel,
    transactionKind = transactionKind,
    category = category,
    priority = priority,
    enabled = enabled,
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun LocalSettingsEntity.toDomain(): UserPreferences = UserPreferences(
    aiSettings = AiCategorizationSettings(
        aiConsentGranted = aiConsentGranted,
        enhancedContextGranted = aiConsentGranted && enhancedContextGranted
    ),
    automaticBookkeepingEnabled = automaticBookkeepingEnabled
)

private fun DefaultFundingAccountCacheEntity.toDomain() = CachedDefaultFundingAccount(
    accountKey = accountKey,
    syncId = syncId,
    pendingUpload = pendingUpload,
    updatedAtEpochMillis = updatedAtEpochMillis
)
