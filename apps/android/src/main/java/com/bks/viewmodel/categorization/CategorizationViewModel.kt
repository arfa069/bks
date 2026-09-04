package com.bks.viewmodel.categorization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountCloudSettingsOperationResult
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.application.account.cloudWritesPaused
import com.bks.model.application.account.effectiveAiCategorizationSettings
import com.bks.model.application.account.settingsSyncInFlight
import com.bks.model.application.categorization.CategorizationRulesCoordinator
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.settings.PreferencesRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class CategorizationViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val accountSessionState: StateFlow<AccountSessionCoordinatorState>,
    private val cloudSettingsCoordinator: AccountCloudSettingsCoordinator,
    private val categorizationRulesCoordinator: CategorizationRulesCoordinator
) : ViewModel() {
    private val noticesChannel = Channel<String>(Channel.BUFFERED)
    val notices = noticesChannel.receiveAsFlow()

    val uiState: StateFlow<CategorizationUiState> = combine(
        preferencesRepository.categorizationRules,
        accountSessionState,
        cloudSettingsCoordinator.state
    ) { rules, account, cloud ->
        CategorizationUiState(
            rules = rules,
            ai = CategorizationAiUiState(
                settings = effectiveAiCategorizationSettings(account, cloud),
                signedIn = account.session is AccountSession.SignedIn,
                cloudWritesPaused = cloudWritesPaused(account),
                settingsSyncInFlight = settingsSyncInFlight(account, cloud)
            )
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CategorizationUiState())

    init {
        viewModelScope.launch {
            categorizationRulesCoordinator.applyCurrentRulesToUnclassified()
        }
    }

    fun updateRules(update: CategorizationRulesUpdate) {
        viewModelScope.launch { categorizationRulesCoordinator.updateRules(update) }
    }

    suspend fun updateRulesAndAwait(update: CategorizationRulesUpdate) {
        categorizationRulesCoordinator.updateRules(update)
    }

    fun updateAiSettings(settings: AiCategorizationSettings) {
        viewModelScope.launch {
            when (val result = cloudSettingsCoordinator.updateAiSettings(settings)) {
                is AccountCloudSettingsOperationResult.CloudFailure ->
                    noticesChannel.send(result.reason.userMessage())
                AccountCloudSettingsOperationResult.CloudWritesBlocked -> noticesChannel.send(
                    if (accountSessionState.value.session is AccountSession.SignedIn) {
                        "账号当前不允许写入云端设置"
                    } else {
                        "登录后才能同步云端 AI 设置"
                    }
                )
                AccountCloudSettingsOperationResult.InFlight,
                AccountCloudSettingsOperationResult.Stale,
                AccountCloudSettingsOperationResult.Success -> Unit
            }
        }
    }
}

private fun com.bks.model.domain.categorization.AiCategorizationFailureReason.userMessage(): String =
    when (this) {
        com.bks.model.domain.categorization.AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED ->
            "云端后端地址尚未配置"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.INVALID_SESSION ->
            "登录状态已失效，请重新登录"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING ->
            "账号注销冷静期内，云端设置已暂停"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.AI_CONSENT_REQUIRED ->
            "请先开启云端 AI 分类"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.ENHANCED_CONTEXT_NOT_AUTHORIZED ->
            "增强上下文授权已失效，请重新确认设置"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED ->
            "暂无可用分类"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.RATE_LIMITED ->
            "请求过于频繁，请稍后重试"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.SERVICE_UNAVAILABLE ->
            "云端服务暂时不可用，请稍后重试"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.NETWORK_FAILURE ->
            "网络连接失败，请检查网络后重试"
        com.bks.model.domain.categorization.AiCategorizationFailureReason.INVALID_RESPONSE ->
            "云端响应异常，请稍后重试"
    }
