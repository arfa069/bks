package com.bks.viewmodel.sync

import com.bks.viewmodel.ledger.label
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncInitialMode
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.application.sync.LedgerSyncPreview
import com.bks.model.application.sync.LedgerSyncRuntime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.model.domain.sync.LedgerSyncConflict
import com.bks.model.domain.sync.LedgerSyncConflictChoice
import com.bks.model.domain.sync.LedgerSyncEntityType
import com.bks.model.domain.sync.LedgerSyncPayload
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LedgerSyncViewModel(
    private val runtime: LedgerSyncRuntime,
    private val coordinator: LedgerSyncCoordinator,
    private val accountSessionState: StateFlow<AccountSessionCoordinatorState>
) : ViewModel() {
    private val operations = MutableStateFlow(LedgerSyncOperationState())
    private var operationSession: AccountSession? = accountSessionState.value.session

    init {
        viewModelScope.launch {
            accountSessionState.collect { state ->
                if (state.session != operationSession) {
                    operationSession = state.session
                    operations.value = LedgerSyncOperationState()
                }
            }
        }
    }

    val uiState: StateFlow<LedgerSyncUiState> = combine(runtime.state, operations) { state, operation ->
        LedgerSyncUiState(
            signedIn = state.signedIn,
            enabled = state.enabled,
            profileKey = state.profileKey,
            lastSuccessAtMillis = state.lastSuccessAtMillis,
            lastError = state.lastError,
            pendingCount = state.pendingCount,
            conflicts = state.conflicts.map { it.toUiModel(it.conflictId in operation.resolvingConflictIds) },
            insecureHttpTestMode = state.insecureHttpTestMode,
            preview = operation.preview,
            previewBusy = operation.previewBusy,
            syncBusy = operation.syncBusy,
            showAccountSwitch = state.accountSwitchRequired,
            accountSwitchBusy = operation.accountSwitchBusy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LedgerSyncUiState(insecureHttpTestMode = runtime.state.value.insecureHttpTestMode)
    )

    suspend fun preview(): LedgerSyncActionResult<LedgerSyncPreviewUiModel> = withBusy(
        setBusy = { busy -> operations.update { it.copy(previewBusy = busy) } }
    ) {
        val session = accountSessionState.value.session as? AccountSession.SignedIn
            ?: return@withBusy LedgerSyncActionResult.Failure("请先登录账户")
        coordinator.preview(session.token).toUiResult(LedgerSyncPreview::toUiModel).also { result ->
            if (
                result is LedgerSyncActionResult.Success &&
                accountSessionState.value.session == session
            ) {
                operations.update { it.copy(preview = result.value) }
            }
        }
    }

    fun dismissPreview() {
        if (!operations.value.syncBusy) operations.update { it.copy(preview = null) }
    }

    suspend fun enable(mode: LedgerSyncEnableMode): LedgerSyncActionResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(syncBusy = busy) } }
    ) {
        withToken { token -> coordinator.enable(token, mode.toApplicationMode()) }
            .toUiResult { Unit }
            .also { result ->
                if (result is LedgerSyncActionResult.Success) {
                    operations.update { it.copy(preview = null) }
                    runtime.onSyncOperationSucceeded()
                }
            }
    }

    suspend fun synchronize(): LedgerSyncActionResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(syncBusy = busy) } }
    ) {
        withToken { token -> coordinator.synchronize(token) }.toUiResult { Unit }
    }

    suspend fun disable() {
        runtime.disable()
        operations.update { it.copy(preview = null) }
    }

    suspend fun resolveConflict(
        conflictId: String,
        expectedVersion: Long,
        choice: LedgerSyncConflictChoice
    ): LedgerSyncActionResult<Unit> {
        operations.update { it.copy(resolvingConflictIds = it.resolvingConflictIds + conflictId) }
        return try {
            withToken { token -> coordinator.resolveConflict(token, conflictId, expectedVersion, choice) }
                .toUiResult { Unit }
        } finally {
            operations.update { it.copy(resolvingConflictIds = it.resolvingConflictIds - conflictId) }
        }
    }

    suspend fun switchAccount(): LedgerSyncActionResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(accountSwitchBusy = busy) } }
    ) {
        withToken { token -> coordinator.switchAccount(token) }.toUiResult { Unit }.also { result ->
            if (result is LedgerSyncActionResult.Success) runtime.onSyncOperationSucceeded()
        }
    }

    private suspend fun <T> withToken(
        operation: suspend (String) -> LedgerSyncOperationResult<T>
    ): LedgerSyncOperationResult<T> {
        val signedIn = accountSessionState.value.session as? AccountSession.SignedIn
            ?: return LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
        return operation(signedIn.token)
    }

    private suspend fun <T> withBusy(
        setBusy: (Boolean) -> Unit,
        operation: suspend () -> T
    ): T {
        setBusy(true)
        return try {
            operation()
        } finally {
            setBusy(false)
        }
    }

    private data class LedgerSyncOperationState(
        val preview: LedgerSyncPreviewUiModel? = null,
        val previewBusy: Boolean = false,
        val syncBusy: Boolean = false,
        val accountSwitchBusy: Boolean = false,
        val resolvingConflictIds: Set<String> = emptySet()
    )
}

private fun LedgerSyncPreview.toUiModel() = LedgerSyncPreviewUiModel(
    localRecordCount = localRecordCount,
    cloudRecordCount = cloudRecordCount,
    insecureHttpTestMode = insecureHttpTestMode
)

private fun LedgerSyncEnableMode.toApplicationMode(): LedgerSyncInitialMode = when (this) {
    LedgerSyncEnableMode.MERGE -> LedgerSyncInitialMode.MERGE
    LedgerSyncEnableMode.REPLACE_LOCAL -> LedgerSyncInitialMode.REPLACE_LOCAL
}

private fun <T, R> LedgerSyncOperationResult<T>.toUiResult(
    transform: (T) -> R
): LedgerSyncActionResult<R> = when (this) {
    is LedgerSyncOperationResult.Success -> LedgerSyncActionResult.Success(transform(value))
    is LedgerSyncOperationResult.Failure -> LedgerSyncActionResult.Failure(message)
}

private fun LedgerSyncConflict.toUiModel(resolving: Boolean) = LedgerSyncConflictUiModel(
    conflictId = conflictId,
    canonicalVersion = canonicalVersion,
    title = syncEntityLabel(entityType),
    cloudSummary = syncPayloadSummary(canonicalPayload, canonicalDeleted),
    localSummary = syncPayloadSummary(candidatePayload, candidateDeleted),
    resolving = resolving
)

private fun syncEntityLabel(entityType: LedgerSyncEntityType): String = when (entityType) {
    LedgerSyncEntityType.CATEGORY -> "分类冲突"
    LedgerSyncEntityType.FUNDING_ACCOUNT -> "资金账户冲突"
    LedgerSyncEntityType.LEDGER_BOOK -> "账本冲突"
    LedgerSyncEntityType.LEDGER_ENTRY -> "账目冲突"
    LedgerSyncEntityType.CATEGORIZATION_RULE -> "分类规则冲突"
}

private fun syncPayloadSummary(payload: LedgerSyncPayload?, deleted: Boolean): String {
    if (deleted || payload == null) return "已删除"
    return when (payload) {
        is LedgerSyncPayload.Category -> payload.name
        is LedgerSyncPayload.FundingAccount -> payload.label
        is LedgerSyncPayload.LedgerBook -> payload.name
        is LedgerSyncPayload.LedgerEntry ->
            "${payload.merchantTitle} · ${payload.amountMinor / 100.0} ${payload.currency}"
        is LedgerSyncPayload.CategorizationRule ->
            "${payload.merchantContains.ifBlank { payload.titleContains }} → ${payload.category}"
    }
}
