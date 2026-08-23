package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bks.feature.account.AccountSession
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.review.ReviewQueueState
import com.bks.ui.BksAppState
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
    BksSyncLifecycleEffects(context)
    BksLocalPersistenceEffects(context)
}

@Composable
private fun BksSyncLifecycleEffects(context: BksAppEffectsContext) {
    DisposableEffect(context.lifecycleOwner, context.dependencies.sync.runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                context.dependencies.sync.runtime.onResume()
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
