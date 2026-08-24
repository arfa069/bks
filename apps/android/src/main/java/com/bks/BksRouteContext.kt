package com.bks

import androidx.compose.foundation.layout.PaddingValues
import com.bks.feature.account.AccountSession
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.monitoring.AutomaticBookkeepingFeatureState
import com.bks.feature.monitoring.AutomaticBookkeepingViewModel
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.review.ReviewQueueViewModel
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope

internal data class BksRouteContext(
    val dependencies: BksAppDependencies,
    val runtime: BksAppRuntimeState,
    val presentation: BksAppPresentation,
    val bindings: BksAppBindings,
    val actions: BksAppActions,
    val appState: BksAppState,
    val coroutineScope: CoroutineScope,
    val automaticBookkeepingViewModel: AutomaticBookkeepingViewModel,
    val automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    val ledgerViewModel: LedgerViewModel,
    val ledgerUiState: LedgerUiState,
    val reviewQueueViewModel: ReviewQueueViewModel,
    val reviewQueueState: ReviewQueueState,
    val ledgerSyncViewModel: com.bks.feature.sync.LedgerSyncViewModel,
    val ledgerSyncUiState: com.bks.feature.sync.LedgerSyncUiState
)

internal data class BksRouteContentContext(
    val context: BksRouteContext,
    val activeAccountSession: AccountSession,
    val innerPadding: PaddingValues,
    val onManualEntryClosed: () -> Unit,
    val onNavigateHome: () -> Unit
)
