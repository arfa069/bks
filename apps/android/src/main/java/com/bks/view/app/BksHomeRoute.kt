package com.bks.view.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.bks.view.common.BksAppState
import com.bks.view.home.HomeScreen
import com.bks.view.home.HomeScreenActions
import com.bks.view.navigation.AppTab
import com.bks.view.profile.ProfileDestination
import com.bks.viewmodel.home.buildHomeUiState
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.LedgerViewModel
import com.bks.viewmodel.ledger.userMessage
import com.bks.viewmodel.monitoring.AutomaticBookkeepingFeatureState
import com.bks.viewmodel.review.ReviewQueueUiState
import com.bks.viewmodel.sync.LedgerSyncUiState
import kotlinx.coroutines.launch

@Composable
@Suppress("LongParameterList")
internal fun BksHomeRoute(
    ledgerState: LedgerUiState,
    reviewState: ReviewQueueUiState,
    automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    ledgerSyncState: LedgerSyncUiState,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    batteryOptimizationIgnored: Boolean?,
    ledgerViewModel: LedgerViewModel,
    appState: BksAppState,
    innerPadding: PaddingValues
) {
    val scope = rememberCoroutineScope()
    val homeState = remember(
        ledgerState,
        reviewState,
        automaticBookkeepingState,
        ledgerSyncState,
        accessibilityAccessGranted,
        accessibilityServiceConnected,
        batteryOptimizationIgnored
    ) {
        buildHomeUiState(
            ledgerState = ledgerState,
            reviewState = reviewState,
            automaticBookkeepingState = automaticBookkeepingState,
            accessibilityAccessGranted = accessibilityAccessGranted,
            accessibilityServiceConnected = accessibilityServiceConnected,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            ledgerSyncState = ledgerSyncState
        )
    }
    HomeScreen(
        state = homeState,
        actions = HomeScreenActions(
            onSelectLedger = { ledgerId ->
                scope.launch {
                    runCatching { ledgerViewModel.selectLedger(ledgerId) }
                        .onFailure { appState.snackbarHostState.showSnackbar(it.userMessage()) }
                }
            },
            onOpenReports = { appState.openTab(AppTab.Reports) },
            onOpenReview = { appState.openTab(AppTab.Review) },
            onOpenAutomaticBookkeeping = {
                appState.openProfileDestination(ProfileDestination.AutomaticBookkeeping)
            },
            onOpenLedgerSync = {
                appState.openProfileDestination(ProfileDestination.DataAndBackup)
            },
            onOpenLedger = { appState.openTab(AppTab.Ledger) },
            onOpenLedgerEntry = appState::openLedgerEntry
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

private fun BksAppState.openTab(tab: AppTab) {
    selectedTab.value = tab
    profileDestination.value = null
}

private fun BksAppState.openProfileDestination(destination: ProfileDestination) {
    selectedTab.value = AppTab.Profile
    profileDestination.value = destination
}
