package com.bks.feature.account

import com.bks.data.local.FundingAccountRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.categorization.AiCategorizationFailureReason
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.categorization.CloudAiSettingsGatewayResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AccountCloudSettingsState(
    val aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    val loadedToken: String? = null,
    val settingsSyncInFlight: Boolean = false
)

internal sealed interface AccountCloudSettingsOperationResult {
    data object Success : AccountCloudSettingsOperationResult
    data object CloudWritesBlocked : AccountCloudSettingsOperationResult
    data object InFlight : AccountCloudSettingsOperationResult
    data object Stale : AccountCloudSettingsOperationResult
    data class CloudFailure(
        val reason: AiCategorizationFailureReason
    ) : AccountCloudSettingsOperationResult
}

internal class AccountCloudSettingsCoordinator(
    private val accountSessionState: StateFlow<AccountSessionCoordinatorState>,
    private val preferencesRepository: LocalPreferencesRepository,
    private val fundingAccountRepository: FundingAccountRepository,
    private val cloudGateway: CloudAiSettingsGateway,
    private val applicationScope: CoroutineScope
) {
    private data class TokenScope(
        val generation: Long,
        val token: String,
        val accountKey: String?
    )

    private data class DefaultFundingMutationScope(
        val tokenScope: TokenScope,
        val generation: Long
    )

    private val started = AtomicBoolean(false)
    private val stateLock = Any()
    private val operationMutex = Mutex()
    private val defaultFundingMutex = Mutex()
    private var generation = 0L
    private var defaultFundingGeneration = 0L
    private var activeTokenScope: TokenScope? = null
    private val mutableState = MutableStateFlow(AccountCloudSettingsState())

    val state: StateFlow<AccountCloudSettingsState> = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            accountSessionState.collectLatest(::activateSession)
        }
    }

    suspend fun updateAiSettings(
        settings: AiCategorizationSettings
    ): AccountCloudSettingsOperationResult {
        if (mutableState.value.settingsSyncInFlight) {
            return AccountCloudSettingsOperationResult.InFlight
        }
        return operationMutex.withLock {
            val tokenScope = synchronized(stateLock) { activeTokenScope }
                ?: return@withLock AccountCloudSettingsOperationResult.CloudWritesBlocked
            setSyncInFlightIfCurrent(tokenScope, true)
            try {
                when (val result = cloudGateway.write(tokenScope.token, settings)) {
                    is CloudAiSettingsGatewayResult.Success -> {
                        if (persistAndPublishAiSettingsIfCurrent(tokenScope, result.settings)) {
                            AccountCloudSettingsOperationResult.Success
                        } else {
                            AccountCloudSettingsOperationResult.Stale
                        }
                    }
                    is CloudAiSettingsGatewayResult.Failure -> {
                        if (isCurrent(tokenScope)) {
                            AccountCloudSettingsOperationResult.CloudFailure(result.reason)
                        } else {
                            AccountCloudSettingsOperationResult.Stale
                        }
                    }
                }
            } finally {
                setSyncInFlightIfCurrent(tokenScope, false)
            }
        }
    }

    suspend fun setDefaultFundingAccount(
        fundingAccountId: Long?
    ): AccountCloudSettingsOperationResult {
        var syncId: String? = null
        val mutationScope = defaultFundingMutex.withLock {
            fundingAccountRepository.setDefaultFundingAccount(fundingAccountId)
            syncId = fundingAccountRepository.getDefaultFundingAccountSyncId()
            val tokenScope = synchronized(stateLock) {
                val current = activeTokenScope
                    ?: return@withLock null
                defaultFundingGeneration += 1
                DefaultFundingMutationScope(current, defaultFundingGeneration)
            }
            tokenScope.tokenScope.accountKey?.let { accountKey ->
                preferencesRepository.cacheDefaultFundingAccount(
                    accountKey = accountKey,
                    syncId = syncId,
                    pendingUpload = true
                )
            }
            tokenScope
        } ?: return AccountCloudSettingsOperationResult.CloudWritesBlocked

        return operationMutex.withLock {
            if (!isCurrent(mutationScope.tokenScope, mutationScope.generation)) {
                return@withLock AccountCloudSettingsOperationResult.Stale
            }
            val tokenScope = mutationScope.tokenScope
            val result = cloudGateway.writeDefaultFundingAccount(tokenScope.token, syncId)
            when (result) {
                is CloudAiSettingsGatewayResult.Success -> {
                    if (!isCurrent(tokenScope, mutationScope.generation)) {
                        AccountCloudSettingsOperationResult.Stale
                    } else {
                        if (tokenScope.accountKey != null && result.supportsDefaultFundingAccount) {
                            defaultFundingMutex.withLock {
                                if (isCurrent(tokenScope, mutationScope.generation)) {
                                    preferencesRepository.cacheDefaultFundingAccount(
                                        accountKey = tokenScope.accountKey,
                                        syncId = result.defaultFundingAccountSyncId,
                                        pendingUpload = false
                                    )
                                }
                            }
                        }
                        AccountCloudSettingsOperationResult.Success
                    }
                }
                is CloudAiSettingsGatewayResult.Failure -> {
                    if (isCurrent(tokenScope, mutationScope.generation)) {
                        AccountCloudSettingsOperationResult.CloudFailure(result.reason)
                    } else {
                        AccountCloudSettingsOperationResult.Stale
                    }
                }
            }
        }
    }

    fun clearLocalState() {
        synchronized(stateLock) {
            generation += 1
            defaultFundingGeneration += 1
            activeTokenScope = activeTokenScope?.copy(generation = generation)
            mutableState.value = AccountCloudSettingsState()
        }
    }

    private suspend fun activateSession(sessionState: AccountSessionCoordinatorState) {
        val tokenScope = synchronized(stateLock) {
            generation += 1
            defaultFundingGeneration += 1
            val signedIn = sessionState.session as? AccountSession.SignedIn
            activeTokenScope = signedIn
                ?.takeIf {
                    sessionState.runtimeState.cloudWritesAllowed &&
                        sessionState.deletionState.cloudWritesAllowed
                }
                ?.let {
                    TokenScope(
                        generation = generation,
                        token = it.token,
                        accountKey = it.cloudConfigAccountKey()
                    )
                }
            mutableState.value = AccountCloudSettingsState(
                settingsSyncInFlight = activeTokenScope != null
            )
            activeTokenScope
        } ?: return

        operationMutex.withLock {
            if (!isCurrent(tokenScope)) return@withLock
            load(tokenScope)
        }
    }

    private suspend fun load(tokenScope: TokenScope) {
        var defaultLoadGeneration = 0L
        val cached = defaultFundingMutex.withLock {
            val accountCache = tokenScope.accountKey?.let {
                preferencesRepository.getCachedDefaultFundingAccount(it)
            }
            if (!isCurrent(tokenScope)) return
            preferencesRepository.setDefaultFundingAccountSyncId(accountCache?.syncId)
            defaultLoadGeneration = synchronized(stateLock) { defaultFundingGeneration }
            accountCache
        }
        if (!isCurrent(tokenScope)) return
        val result = if (cached?.pendingUpload == true) {
            cloudGateway.writeDefaultFundingAccount(tokenScope.token, cached.syncId)
        } else {
            cloudGateway.read(tokenScope.token)
        }
        when (result) {
            is CloudAiSettingsGatewayResult.Success ->
                applyLoadedSettings(tokenScope, result, defaultLoadGeneration)
            is CloudAiSettingsGatewayResult.Failure -> setSyncInFlightIfCurrent(tokenScope, false)
        }
    }

    private suspend fun applyLoadedSettings(
        tokenScope: TokenScope,
        result: CloudAiSettingsGatewayResult.Success,
        defaultLoadGeneration: Long
    ) {
        if (!persistAndPublishAiSettingsIfCurrent(tokenScope, result.settings)) return
        if (tokenScope.accountKey != null && result.supportsDefaultFundingAccount) {
            defaultFundingMutex.withLock {
                if (isCurrent(tokenScope, defaultLoadGeneration)) {
                    preferencesRepository.cacheDefaultFundingAccount(
                        accountKey = tokenScope.accountKey,
                        syncId = result.defaultFundingAccountSyncId,
                        pendingUpload = false
                    )
                    preferencesRepository.setDefaultFundingAccountSyncId(result.defaultFundingAccountSyncId)
                }
            }
        }
    }

    private suspend fun persistAndPublishAiSettingsIfCurrent(
        tokenScope: TokenScope,
        settings: AiCategorizationSettings
    ): Boolean {
        if (!isCurrent(tokenScope)) return false
        val previousSettings = preferencesRepository.userPreferences.first().aiSettings
        if (!isCurrent(tokenScope)) return false
        preferencesRepository.updateAiSettings(settings)
        val published = synchronized(stateLock) {
            if (activeTokenScope != tokenScope) {
                false
            } else {
                mutableState.value = AccountCloudSettingsState(
                    aiSettings = settings,
                    loadedToken = tokenScope.token,
                    settingsSyncInFlight = false
                )
                true
            }
        }
        if (published) return true
        preferencesRepository.updateAiSettings(previousSettings)
        return false
    }

    private fun isCurrent(
        tokenScope: TokenScope,
        expectedDefaultGeneration: Long? = null
    ): Boolean = synchronized(stateLock) {
        activeTokenScope == tokenScope &&
            (expectedDefaultGeneration == null || defaultFundingGeneration == expectedDefaultGeneration)
    }

    private fun setSyncInFlightIfCurrent(tokenScope: TokenScope, inFlight: Boolean) {
        synchronized(stateLock) {
            if (activeTokenScope != tokenScope) return
            mutableState.value = mutableState.value.copy(settingsSyncInFlight = inFlight)
        }
    }

}
