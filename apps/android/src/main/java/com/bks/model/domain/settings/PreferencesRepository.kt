package com.bks.model.domain.settings

import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import kotlinx.coroutines.flow.Flow

data class UserPreferences(
    val aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    val automaticBookkeepingEnabled: Boolean = false
)

data class CachedDefaultFundingAccount(
    val accountKey: String,
    val syncId: String?,
    val pendingUpload: Boolean,
    val updatedAtEpochMillis: Long
)

interface PreferencesRepository {
    val categorizationRules: Flow<List<CategorizationRule>>
    val userPreferences: Flow<UserPreferences>
    suspend fun updateCategorizationRules(update: CategorizationRulesUpdate)
    suspend fun seedDefaultCategorizationRules()
    suspend fun updateAiSettings(aiSettings: AiCategorizationSettings)
    suspend fun setAutomaticBookkeepingEnabled(enabled: Boolean)
    suspend fun clearLocalData()
    suspend fun setDefaultFundingAccountSyncId(syncId: String?)
    suspend fun getDefaultFundingAccountSyncId(): String?
    suspend fun cacheDefaultFundingAccount(accountKey: String, syncId: String?, pendingUpload: Boolean)
    suspend fun getCachedDefaultFundingAccount(accountKey: String): CachedDefaultFundingAccount?
}
