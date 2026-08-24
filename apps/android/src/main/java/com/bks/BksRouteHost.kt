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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.feature.account.AccountEntryViewModel
import com.bks.feature.account.AccountScreenDependencies
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountScreen
import com.bks.feature.compliance.ComplianceMaterialsScreen
import com.bks.feature.home.HomeScreen
import com.bks.feature.ledger.ManualLedgerEntryScreen
import com.bks.feature.profile.ProfileDestination
import com.bks.ui.AccountEntryReturnTarget
import com.bks.ui.components.AppBottomNavigationBar
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.visual.AppWallpaper

@Composable
internal fun BksAccountEntry(context: BksRouteContext) {
    val appState = context.appState
    val dependencies = context.dependencies
    val sessionCoordinator = dependencies.account.sessionCoordinator
    val returnTarget = appState.accountEntryReturnTarget.value
    val accountState by sessionCoordinator.state.collectAsStateWithLifecycle()
    var showComplianceMaterials by remember { mutableStateOf(false) }
    val accountEntryViewModel: AccountEntryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountEntryViewModel(
                accountRepository = dependencies.account.accountRepository,
                commitSession = sessionCoordinator::commit,
                clearPersistedSession = sessionCoordinator::clearPersistedSession,
                enterLocalMode = sessionCoordinator::enterLocalMode,
                wechatAuthGateway = dependencies.account.wechatAuthGateway
            ) as T
        }
    )

    LaunchedEffect(accountState.session, appState.accountEntryMode.value) {
        if (accountState.session is AccountSession.SignedIn && appState.accountEntryMode.value != null) {
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
                    wechatAuthCallback = context.bindings.wechatAuthCallback,
                    onWechatAuthCallbackConsumed = context.bindings.onWechatAuthCallbackConsumed,
                    avatarCacheOverride = dependencies.account.wechatAvatarCache
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
internal fun BksRouteHost(context: BksRouteContext) {
    val runtime = context.runtime
    val bindings = context.bindings
    val dependencies = context.dependencies
    val actions = context.actions
    val appState = context.appState
    val activeAccountSession = runtime.accountSession ?: return
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
                    BksRouteContent(
                        contentContext = BksRouteContentContext(
                            context = context,
                            activeAccountSession = activeAccountSession,
                            innerPadding = innerPadding,
                            onManualEntryClosed = { manualEntryOpen = false },
                            onNavigateHome = {
                                selectedTab = null
                                profileDestination = null
                            }
                        ),
                        targetRoute = targetRoute,
                    )
                }
            }
        }
    }
}

@Composable
private fun BksRouteContent(
    contentContext: BksRouteContentContext,
    targetRoute: AppRoute
) {
    val context = contentContext.context
    if (targetRoute.manualEntryOpen) {
        BksManualEntryRoute(
            context = context,
            innerPadding = contentContext.innerPadding,
            onManualEntryClosed = contentContext.onManualEntryClosed
        )
        return
    }

    BksPrimaryTabRoute(
        context = context,
        targetRoute = targetRoute,
        activeAccountSession = contentContext.activeAccountSession,
        innerPadding = contentContext.innerPadding,
        onNavigateHome = contentContext.onNavigateHome
    )
}

@Composable
private fun BksManualEntryRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onManualEntryClosed: () -> Unit
) {
    val ledgerState = context.ledgerUiState
    val ledgerViewModel = context.ledgerViewModel
    val appState = context.appState
    ManualLedgerEntryScreen(
        categories = ledgerState.categories,
        fundingAccounts = ledgerState.fundingAccounts,
        onExit = onManualEntryClosed,
        onCreateEntry = { input ->
            ledgerViewModel.createManualEntry(input)
            onManualEntryClosed()
            appState.selectedTab.value = AppTab.Ledger
            appState.profileDestination.value = null
        },
        modifier = Modifier.fillMaxSize().padding(innerPadding)
    )
}

@Composable
internal fun BksPrimaryTabRoute(
    context: BksRouteContext,
    targetRoute: AppRoute,
    activeAccountSession: AccountSession,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    when (targetRoute.tab) {
        null -> HomeScreen(modifier = Modifier.padding(innerPadding))
        AppTab.Review -> BksReviewRoute(context, innerPadding, onNavigateHome)
        AppTab.Ledger -> BksLedgerRoute(context, innerPadding, onNavigateHome)
        AppTab.Reports -> BksReportsRoute(context, innerPadding, onNavigateHome)
        AppTab.Profile -> BksProfileRoute(
            context = context,
            activeAccountSession = activeAccountSession,
            innerPadding = innerPadding,
            destination = targetRoute.profileDestination,
            onNavigateHome = onNavigateHome
        )
    }
}
