package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.feature.account.AccountDeletionUiState
import com.bks.feature.account.AccountCloudSettingsOperationResult
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationRule
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.review.ReviewQueueState
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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
    var reviewState by mutableStateOf(ReviewQueueState())
    var reviewTransitionInFlight by mutableStateOf(false)
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
    private val ledgerViewModel: LedgerViewModel
) {
    fun persistReviewState(nextState: ReviewQueueState) {
        if (runtime.reviewTransitionInFlight) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar("上一项操作正在保存，请稍后重试")
            }
            return
        }
        val previousState = runtime.reviewState
        val previousConfirmedIds = previousState.confirmedEntries.mapTo(mutableSetOf()) { it.originPendingId }
        val addsConfirmation = nextState.confirmedEntries.any { it.originPendingId !in previousConfirmedIds }
        if (addsConfirmation && ledgerViewModel.localDataReplacementInFlight.value) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar("本机数据正在删除，请稍后重试")
            }
            return
        }
        val targetLedgerBookId = ledgerViewModel.uiState.value.activeLedgerBookId
        if (addsConfirmation && targetLedgerBookId == null) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar("当前账本尚未加载，请稍后重试")
            }
            return
        }
        runtime.reviewTransitionInFlight = true
        runtime.reviewState = nextState
        coroutineScope.launch {
            val failure = runCatching {
                dependencies.local.reviewQueuePersistence.persistTransition(
                    previous = previousState,
                    next = nextState,
                    targetLedgerBookId = targetLedgerBookId ?: DEFAULT_LEDGER_BOOK_ID
                )
            }.exceptionOrNull()
            if (failure != null) {
                val persistedState = runCatching {
                    dependencies.local.reviewQueuePersistence.observeState().first()
                }.getOrElse {
                    previousState.copy(confirmedEntries = emptyList(), lastAction = null)
                }
                runtime.reviewState = persistedState.copy(
                    undoEventSequence = maxOf(persistedState.undoEventSequence, previousState.undoEventSequence)
                )
            }
            runtime.reviewTransitionInFlight = false
            if (failure != null) {
                appState.snackbarHostState.showSnackbar("保存待确认操作失败，请重试")
            }
        }
    }

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
                dependencies.account.wechatAvatarCache.clear()
                runtime.reviewState = ReviewQueueState()
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
