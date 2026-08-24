package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.feature.account.AccountDeletionUiState
import com.bks.feature.account.AccountCloudSettingsOperationResult
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationRule
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.review.ReviewQueueViewModel
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BksAppRuntimeState {
    var automaticBookkeepingEnabled by mutableStateOf(false)
    var accountSession by mutableStateOf<AccountSession?>(null)
    var isRestoringAccountSession by mutableStateOf(true)
    var accountDeletionState by mutableStateOf(AccountDeletionUiState())
    var accountRuntimeState by mutableStateOf(AccountRuntimeState(AccountRuntimeStatus.LocalMode))
    var aiSettings by mutableStateOf(AiCategorizationSettings())
    var aiSettingsSyncInFlight by mutableStateOf(false)
    var cloudAiSettingsLoadedToken by mutableStateOf<String?>(null)
    var categorizationRules by mutableStateOf(emptyList<CategorizationRule>())
}

@Composable
internal fun rememberBksAppRuntimeState(): BksAppRuntimeState = remember {
    BksAppRuntimeState()
}

internal class BksAppActions(
    private val dependencies: BksAppDependencies,
    private val runtime: BksAppRuntimeState,
    private val appState: BksAppState,
    private val coroutineScope: CoroutineScope,
    private val ledgerViewModel: LedgerViewModel,
    private val reviewQueueViewModel: ReviewQueueViewModel
) {
    fun persistCategorizationRules(nextRules: List<CategorizationRule>) {
        runtime.categorizationRules = nextRules
        coroutineScope.launch {
            dependencies.local.preferencesRepository.replaceCategorizationRules(nextRules)
        }
    }

    fun setAutomaticBookkeepingEnabled(enabled: Boolean) {
        runtime.automaticBookkeepingEnabled = enabled
        coroutineScope.launch {
            dependencies.local.preferencesRepository.setAutomaticBookkeepingEnabled(enabled)
        }
    }

    fun persistAiSettings(nextSettings: AiCategorizationSettings) {
        coroutineScope.launch {
            when (
                val result = dependencies.account.cloudSettingsCoordinator.updateAiSettings(nextSettings)
            ) {
                is AccountCloudSettingsOperationResult.CloudFailure ->
                    appState.snackbarHostState.showSnackbar(result.reason.toAiSettingsMessage())
                AccountCloudSettingsOperationResult.CloudWritesBlocked ->
                    appState.snackbarHostState.showSnackbar(
                        if (runtime.accountSession !is AccountSession.SignedIn) {
                            "登录后才能同步云端 AI 设置"
                        } else {
                            "账号当前不允许写入云端设置"
                        }
                    )
                AccountCloudSettingsOperationResult.InFlight,
                AccountCloudSettingsOperationResult.Stale,
                AccountCloudSettingsOperationResult.Success -> Unit
            }
        }
    }

    suspend fun clearLocalData() = withContext(NonCancellable) {
        try {
            ledgerViewModel.clearLocalData {
                reviewQueueViewModel.awaitIdleAndResetForLocalDataReplacement()
                dependencies.account.wechatAvatarCache.clear()
                runtime.categorizationRules = emptyList()
                dependencies.account.cloudSettingsCoordinator.clearLocalState()
                dependencies.sync.runtime.disable()
            }
        } finally {
            dependencies.diagnosticLogs.clear(keepEnabledPreference = false)
        }
    }
}

internal data class BksAppPresentation(
    val effectiveAiSettings: AiCategorizationSettings
)

@Composable
internal fun rememberBksAppPresentation(
    runtime: BksAppRuntimeState
): BksAppPresentation {
    return BksAppPresentation(
        effectiveAiSettings = (runtime.accountSession as? AccountSession.SignedIn)
            ?.takeIf { signedIn ->
                signedIn.token == runtime.cloudAiSettingsLoadedToken &&
                    runtime.accountRuntimeState.cloudWritesAllowed &&
                    runtime.accountDeletionState.cloudWritesAllowed
            }
            ?.let { runtime.aiSettings }
            ?: AiCategorizationSettings()
    )
}
