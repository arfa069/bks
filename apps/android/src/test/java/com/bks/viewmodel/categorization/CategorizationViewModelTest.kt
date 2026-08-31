package com.bks.viewmodel.categorization

import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.model.domain.categorization.CloudAiSettingsGatewayResult
import com.bks.model.domain.ledger.FundingAccountSettingsRepository
import com.bks.model.domain.settings.CachedDefaultFundingAccount
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.domain.settings.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategorizationViewModelTest {
    @Test
    fun ownsRuleUpdatesAndProjectsSignedOutAiState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val preferences = FakePreferencesRepository()
            val accountState = MutableStateFlow(
                AccountSessionCoordinatorState(isRestoring = false)
            )
            val cloudCoordinator = AccountCloudSettingsCoordinator(
                accountSessionState = accountState,
                preferencesRepository = preferences,
                fundingAccountRepository = FakeFundingAccountSettingsRepository(),
                cloudGateway = FakeCloudSettingsGateway(),
                applicationScope = this
            )
            val viewModel = CategorizationViewModel(
                preferencesRepository = preferences,
                accountSessionState = accountState,
                cloudSettingsCoordinator = cloudCoordinator
            )
            val rule = CategorizationRule(id = "lunch", merchantContains = "食堂", category = "餐饮")

            viewModel.updateRules { rules -> rules + rule }
            advanceUntilIdle()

            val state = viewModel.uiState.first { it.rules.isNotEmpty() }
            assertEquals(listOf(rule), state.rules)
            assertFalse(state.ai.signedIn)
            assertEquals(AiCategorizationSettings(), state.ai.settings)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val mutableRules = MutableStateFlow<List<CategorizationRule>>(emptyList())
        private val mutablePreferences = MutableStateFlow(UserPreferences())
        override val categorizationRules: Flow<List<CategorizationRule>> = mutableRules
        override val userPreferences: Flow<UserPreferences> = mutablePreferences

        override suspend fun updateCategorizationRules(update: CategorizationRulesUpdate) {
            mutableRules.value = update(mutableRules.value)
        }

        override suspend fun seedDefaultCategorizationRules() = Unit

        override suspend fun updateAiSettings(aiSettings: AiCategorizationSettings) {
            mutablePreferences.value = mutablePreferences.value.copy(aiSettings = aiSettings)
        }

        override suspend fun setAutomaticBookkeepingEnabled(enabled: Boolean) {
            mutablePreferences.value = mutablePreferences.value.copy(automaticBookkeepingEnabled = enabled)
        }

        override suspend fun clearLocalData() = Unit
        override suspend fun setDefaultFundingAccountSyncId(syncId: String?) = Unit
        override suspend fun getDefaultFundingAccountSyncId(): String? = null
        override suspend fun cacheDefaultFundingAccount(
            accountKey: String,
            syncId: String?,
            pendingUpload: Boolean
        ) = Unit

        override suspend fun getCachedDefaultFundingAccount(
            accountKey: String
        ): CachedDefaultFundingAccount? = null
    }

    private class FakeFundingAccountSettingsRepository : FundingAccountSettingsRepository {
        override suspend fun setDefaultFundingAccount(fundingAccountId: Long?) = Unit
        override suspend fun getDefaultFundingAccountSyncId(): String? = null
    }

    private class FakeCloudSettingsGateway : CloudAiSettingsGateway {
        override suspend fun read(token: String): CloudAiSettingsGatewayResult =
            CloudAiSettingsGatewayResult.Failure(AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)

        override suspend fun write(
            token: String,
            settings: AiCategorizationSettings
        ): CloudAiSettingsGatewayResult =
            CloudAiSettingsGatewayResult.Failure(AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
    }
}
