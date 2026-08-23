package com.bks.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncPayloadContract
import com.bks.data.local.AccountSyncConflictEntity
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionCoordinatorState
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

    suspend fun preview(): LedgerSyncOperationResult<LedgerSyncPreview> = withBusy(
        setBusy = { busy -> operations.update { it.copy(previewBusy = busy) } }
    ) {
        val session = accountSessionState.value.session as? AccountSession.SignedIn
            ?: return@withBusy LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
        coordinator.preview(session.token).also { result ->
            if (
                result is LedgerSyncOperationResult.Success &&
                accountSessionState.value.session == session
            ) {
                operations.update { it.copy(preview = result.value) }
            }
        }
    }

    fun dismissPreview() {
        if (!operations.value.syncBusy) operations.update { it.copy(preview = null) }
    }

    suspend fun enable(mode: LedgerSyncInitialMode): LedgerSyncOperationResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(syncBusy = busy) } }
    ) {
        withToken { token -> coordinator.enable(token, mode) }.also { result ->
            if (result is LedgerSyncOperationResult.Success) {
                operations.update { it.copy(preview = null) }
                runtime.onSyncOperationSucceeded()
            }
        }
    }

    suspend fun synchronize(): LedgerSyncOperationResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(syncBusy = busy) } }
    ) {
        withToken { token -> coordinator.synchronize(token) }
    }

    suspend fun disable() {
        runtime.disable()
        operations.update { it.copy(preview = null) }
    }

    suspend fun resolveConflict(
        conflictId: String,
        expectedVersion: Long,
        choice: LedgerSyncConflictChoiceContract
    ): LedgerSyncOperationResult<Unit> {
        operations.update { it.copy(resolvingConflictIds = it.resolvingConflictIds + conflictId) }
        return try {
            withToken { token -> coordinator.resolveConflict(token, conflictId, expectedVersion, choice) }
        } finally {
            operations.update { it.copy(resolvingConflictIds = it.resolvingConflictIds - conflictId) }
        }
    }

    suspend fun switchAccount(): LedgerSyncOperationResult<Unit> = withBusy(
        setBusy = { busy -> operations.update { it.copy(accountSwitchBusy = busy) } }
    ) {
        withToken { token -> coordinator.switchAccount(token) }.also { result ->
            if (result is LedgerSyncOperationResult.Success) runtime.onSyncOperationSucceeded()
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
        operation: suspend () -> LedgerSyncOperationResult<T>
    ): LedgerSyncOperationResult<T> {
        setBusy(true)
        return try {
            operation()
        } finally {
            setBusy(false)
        }
    }

    private data class LedgerSyncOperationState(
        val preview: LedgerSyncPreview? = null,
        val previewBusy: Boolean = false,
        val syncBusy: Boolean = false,
        val accountSwitchBusy: Boolean = false,
        val resolvingConflictIds: Set<String> = emptySet()
    )
}

private fun AccountSyncConflictEntity.toUiModel(resolving: Boolean) = LedgerSyncConflictUiModel(
    conflictId = conflictId,
    canonicalVersion = canonicalVersion,
    title = syncEntityLabel(entityType),
    cloudSummary = syncPayloadSummary(entityType, canonicalPayload, canonicalDeleted),
    localSummary = syncPayloadSummary(entityType, candidatePayload, candidateDeleted),
    resolving = resolving
)

private fun syncEntityLabel(entityType: String): String = when (
    runCatching { LedgerSyncEntityTypeContract.valueOf(entityType) }.getOrNull()
) {
    LedgerSyncEntityTypeContract.CATEGORY -> "分类冲突"
    LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> "资金账户冲突"
    LedgerSyncEntityTypeContract.LEDGER_BOOK -> "账本冲突"
    LedgerSyncEntityTypeContract.LEDGER_ENTRY -> "账目冲突"
    LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> "分类规则冲突"
    null -> "同步冲突"
}

private fun syncPayloadSummary(entityType: String, payload: String?, deleted: Boolean): String {
    if (deleted || payload == null) return "已删除"
    val type = runCatching { LedgerSyncEntityTypeContract.valueOf(entityType) }.getOrNull()
        ?: return "数据已更新"
    return runCatching {
        when (val parsed = LedgerSyncJsonContracts.parsePayload(type, payload)) {
            is LedgerSyncPayloadContract.Category -> parsed.name
            is LedgerSyncPayloadContract.FundingAccount -> parsed.label
            is LedgerSyncPayloadContract.LedgerBook -> parsed.name
            is LedgerSyncPayloadContract.LedgerEntry ->
                "${parsed.merchantTitle} · ${parsed.amountMinor / 100.0} ${parsed.currency}"
            is LedgerSyncPayloadContract.CategorizationRule ->
                "${parsed.merchantContains.ifBlank { parsed.titleContains }} → ${parsed.category}"
        }
    }.getOrDefault("数据已更新")
}
