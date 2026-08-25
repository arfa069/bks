package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.feature.account.AccountCloudSettingsOperationResult
import com.bks.feature.account.AccountCloudSettingsState
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionCoordinatorState
import com.bks.feature.account.effectiveAiCategorizationSettings
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationRule
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class BksAppRuntimeState {
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
    private val coroutineScope: CoroutineScope
) {
    fun persistCategorizationRules(nextRules: List<CategorizationRule>) {
        runtime.categorizationRules = nextRules
        coroutineScope.launch {
            dependencies.local.preferencesRepository.replaceCategorizationRules(nextRules)
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
                        if (
                            dependencies.account.sessionCoordinator.state.value.session !is
                            AccountSession.SignedIn
                        ) {
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
}

internal data class BksAppPresentation(
    val accountToken: String?,
    val signedIn: Boolean,
    val effectiveAiSettings: AiCategorizationSettings,
    val cloudWritesPaused: Boolean,
    val settingsSyncInFlight: Boolean
)

internal fun bksAppPresentation(
    accountState: AccountSessionCoordinatorState,
    cloudSettingsState: AccountCloudSettingsState
): BksAppPresentation {
    val signedIn = accountState.session as? AccountSession.SignedIn
    val cloudWritesAllowed = accountState.runtimeState.cloudWritesAllowed &&
        accountState.deletionState.cloudWritesAllowed
    return BksAppPresentation(
        accountToken = signedIn?.token,
        signedIn = signedIn != null,
        effectiveAiSettings = effectiveAiCategorizationSettings(
            accountState = accountState,
            cloudSettingsState = cloudSettingsState
        ),
        cloudWritesPaused = !cloudWritesAllowed,
        settingsSyncInFlight = signedIn != null &&
            cloudWritesAllowed &&
            cloudSettingsState.settingsSyncInFlight
    )
}
