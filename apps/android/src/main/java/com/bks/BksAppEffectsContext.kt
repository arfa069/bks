package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncScheduler
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.ui.BksAppState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class BksAppEffectsContext(
    val dependencies: BksAppDependencies,
    val bindings: BksAppBindings,
    val runtime: BksAppRuntimeState,
    val actions: BksAppActions,
    val appState: BksAppState,
    val lifecycleOwner: LifecycleOwner,
    val presentation: BksAppPresentation
)

@Composable
internal fun BksAppEffects(context: BksAppEffectsContext) {
    BksSyncStateEffects(context)
    BksSyncLifecycleEffects(context)
    BksLocalPersistenceEffects(context)
}

@Composable
private fun BksSyncStateEffects(context: BksAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    val appContext = dependencies.context
    DisposableEffect(
        dependencies.local.database,
        appContext,
        runtime.ledgerSyncUiState.enabled,
        runtime.accountSession,
        runtime.accountRuntimeState.status
    ) {
        val observer = object : androidx.room.InvalidationTracker.Observer(
            "categories",
            "funding_accounts",
            "ledger_books",
            "ledger_entries",
            "categorization_rules"
        ) {
            override fun onInvalidated(tables: Set<String>) {
                if (
                    runtime.ledgerSyncUiState.enabled &&
                    runtime.accountSession is AccountSession.SignedIn &&
                    runtime.accountRuntimeState.status == AccountRuntimeStatus.Verified
                ) {
                    LedgerSyncScheduler.enqueueNow(appContext)
                }
            }
        }
        dependencies.local.database.invalidationTracker.addObserver(observer)
        onDispose { dependencies.local.database.invalidationTracker.removeObserver(observer) }
    }

    LaunchedEffect(dependencies.sync.localStore, runtime.accountSession) {
        combine(
            dependencies.sync.localStore.state,
            dependencies.sync.localStore.outboxCount,
            dependencies.sync.localStore.conflicts
        ) { state, outboxCount, conflicts ->
            LedgerSyncUiState(
                signedIn = runtime.accountSession is AccountSession.SignedIn,
                enabled = state?.enabled == true,
                profileKey = state?.profileKey,
                lastSuccessAtMillis = state?.lastSuccessAtMillis,
                lastError = state?.lastError,
                pendingCount = outboxCount,
                conflicts = conflicts,
                insecureHttpTestMode = dependencies.sync.repository.insecureHttpTestMode
            )
        }.collect { runtime.ledgerSyncUiState = it }
    }
}

@Composable
private fun BksSyncLifecycleEffects(context: BksAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    val appContext = dependencies.context
    LaunchedEffect(runtime.accountSession, runtime.accountRuntimeState.status, runtime.ledgerSyncUiState.enabled) {
        val signedIn = runtime.accountSession as? AccountSession.SignedIn
        if (
            signedIn != null &&
            runtime.accountRuntimeState.status == AccountRuntimeStatus.Verified &&
            runtime.ledgerSyncUiState.enabled
        ) {
            when (val preview = dependencies.sync.coordinator.preview(signedIn.token)) {
                is LedgerSyncOperationResult.Success -> {
                    if (
                        runtime.ledgerSyncUiState.profileKey != null &&
                        runtime.ledgerSyncUiState.profileKey != preview.value.profileKey
                    ) {
                        LedgerSyncScheduler.cancel(appContext)
                        runtime.showLedgerSyncAccountSwitch = true
                    } else {
                        LedgerSyncScheduler.ensurePeriodic(appContext)
                        LedgerSyncScheduler.enqueueNow(appContext)
                    }
                }
                is LedgerSyncOperationResult.Failure -> Unit
            }
        }
    }

    DisposableEffect(
        context.lifecycleOwner,
        runtime.accountSession,
        runtime.accountRuntimeState.status,
        runtime.ledgerSyncUiState.enabled
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                shouldEnqueueLedgerSync(
                    event,
                    runtime.accountSession,
                    runtime.accountRuntimeState,
                    runtime.ledgerSyncUiState
                )
            ) {
                LedgerSyncScheduler.enqueueNow(appContext)
            }
        }
        context.lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { context.lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun BksLocalPersistenceEffects(context: BksAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    LaunchedEffect(dependencies.local.ledgerRepository) {
        dependencies.local.ledgerRepository.ensureDefaultLedgerBook()
        dependencies.local.ledgerRepository.seedSystemCategories()
        dependencies.local.ledgerRepository.purgeExpiredDeletedLedgerEntries()
    }
    LaunchedEffect(dependencies.local.reviewQueuePersistence) {
        dependencies.local.reviewQueuePersistence.observeState().collect { persistedState ->
            runtime.reviewState = persistedState.copy(
                confirmedEntries = runtime.reviewState.confirmedEntries,
                lastAction = runtime.reviewState.lastAction,
                undoEventSequence = runtime.reviewState.undoEventSequence
            )
        }
    }
    LaunchedEffect(dependencies.local.ledgerRepository) {
        dependencies.local.ledgerRepository.state.collect { state -> runtime.ledgerState = state }
    }
    LaunchedEffect(dependencies.local.preferencesRepository) {
        dependencies.local.preferencesRepository.categorizationRules.collect {
            runtime.categorizationRules = it
        }
    }
    LaunchedEffect(dependencies.local.preferencesRepository, runtime.accountSession) {
        dependencies.local.preferencesRepository.userPreferences.collect { preferences ->
            runtime.automaticBookkeepingEnabled = preferences.automaticBookkeepingEnabled
            if (runtime.accountSession !is AccountSession.SignedIn) {
                runtime.aiSettings = preferences.aiSettings
            }
        }
    }
}

internal fun shouldEnqueueLedgerSync(
    event: Lifecycle.Event,
    accountSession: AccountSession?,
    accountRuntimeState: AccountRuntimeState,
    ledgerSyncUiState: LedgerSyncUiState
): Boolean {
    if (event != Lifecycle.Event.ON_RESUME) return false
    if (accountSession !is AccountSession.SignedIn) return false
    if (accountRuntimeState.status != AccountRuntimeStatus.Verified) return false
    return ledgerSyncUiState.enabled
}
