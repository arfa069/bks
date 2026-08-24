package com.bks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.feature.account.AccountEntryViewModel
import com.bks.feature.account.AccountScreenDependencies
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountScreen
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAvatarCache
import com.bks.feature.compliance.ComplianceMaterialsScreen
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.ledger.ManualLedgerEntryScreen
import com.bks.feature.profile.ProfileDestination
import com.bks.ui.BksAppState
import com.bks.ui.AccountEntryReturnTarget
import com.bks.ui.components.AppBottomNavigationBar
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.visual.AppWallpaper

@Composable
@Suppress("LongParameterList")
internal fun BksAccountEntry(
    appState: BksAppState,
    accountSession: AccountSession?,
    viewModelFactory: ViewModelProvider.Factory,
    wechatAuthCallback: WechatAuthCallback?,
    onWechatAuthCallbackConsumed: () -> Unit,
    avatarCache: WechatAvatarCache
) {
    val returnTarget = appState.accountEntryReturnTarget.value
    var showComplianceMaterials by remember { mutableStateOf(false) }
    val accountEntryViewModel: AccountEntryViewModel = viewModel(
        factory = viewModelFactory
    )

    LaunchedEffect(accountSession, appState.accountEntryMode.value) {
        if (accountSession is AccountSession.SignedIn && appState.accountEntryMode.value != null) {
            accountEntryViewModel.closeEntry()
            appState.closeAccountEntry()
            appState.selectedTab.value = null
            appState.profileDestination.value = null
        }
    }

    AppWallpaper(R.drawable.aa_bg_account) {
        if (showComplianceMaterials) {
            BackHandler { showComplianceMaterials = false }
            ComplianceMaterialsScreen(onBack = { showComplianceMaterials = false })
        } else {
            AccountScreen(
                dependencies = AccountScreenDependencies(
                    viewModel = accountEntryViewModel,
                    wechatAuthCallback = wechatAuthCallback,
                    onWechatAuthCallbackConsumed = onWechatAuthCallbackConsumed,
                    avatarCacheOverride = avatarCache
                ),
                onComplianceClick = { showComplianceMaterials = true },
                onBack = returnTarget?.let {
                    {
                        accountEntryViewModel.closeEntry()
                        appState.closeAccountEntry()
                        when (it) {
                            AccountEntryReturnTarget.AccountManagement -> {
                                appState.selectedTab.value = AppTab.Profile
                                appState.profileDestination.value = ProfileDestination.AccountManagement
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
internal fun BksRouteHost(
    appState: BksAppState,
    content: @Composable (
        targetRoute: AppRoute,
        innerPadding: PaddingValues,
        onManualEntryClosed: () -> Unit,
        onNavigateHome: () -> Unit
    ) -> Unit
) {
    var selectedTab by appState.selectedTab
    var manualEntryOpen by appState.manualEntryOpen
    var profileDestination by appState.profileDestination

    BackHandler(
        enabled = selectedTab != null &&
            (selectedTab != AppTab.Profile || profileDestination == null) &&
            !manualEntryOpen
    ) {
        selectedTab = null
    }

    BackHandler(enabled = selectedTab == AppTab.Profile && profileDestination != null) {
        profileDestination = null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(appState.snackbarHostState) },
            bottomBar = {
                if (selectedTab == null && !manualEntryOpen) {
                    AppBottomNavigationBar(
                        items = appState.bottomNavigationItems,
                        selectedKey = null,
                        onItemSelected = { key ->
                            if (!manualEntryOpen) {
                                selectedTab = appState.tabs.first { it.name == key }
                                profileDestination = null
                            }
                        },
                        onAddEntry = {
                            if (!manualEntryOpen) manualEntryOpen = true
                        },
                        enabled = !manualEntryOpen
                    )
                }
            }
        ) { innerPadding ->
            val route = remember(selectedTab, profileDestination, manualEntryOpen) {
                AppRoute(
                    tab = selectedTab,
                    profileDestination = profileDestination.takeIf { selectedTab == AppTab.Profile },
                    manualEntryOpen = manualEntryOpen
                )
            }
            val wallpaperRes = when {
                route.manualEntryOpen -> R.drawable.aa_bg_ledger
                route.tab == null -> R.drawable.aa_bg_account
                route.profileDestination != null -> R.drawable.aa_bg_neutral
                else -> route.tab.backgroundRes
            }
            AppWallpaper(wallpaperRes) {
                SlidePageTransition(
                    targetState = route,
                    modifier = Modifier.fillMaxSize()
                ) { targetRoute ->
                    content(
                        targetRoute,
                        innerPadding,
                        { manualEntryOpen = false },
                        {
                            selectedTab = null
                            profileDestination = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun BksManualEntryRoute(
    ledgerState: LedgerUiState,
    ledgerViewModel: LedgerViewModel,
    innerPadding: PaddingValues,
    onManualEntryClosed: () -> Unit,
    onEntryCreated: () -> Unit
) {
    ManualLedgerEntryScreen(
        categories = ledgerState.categories,
        fundingAccounts = ledgerState.fundingAccounts,
        onExit = onManualEntryClosed,
        onCreateEntry = { input ->
            ledgerViewModel.createManualEntry(input)
            onManualEntryClosed()
            onEntryCreated()
        },
        modifier = Modifier.fillMaxSize().padding(innerPadding)
    )
}
