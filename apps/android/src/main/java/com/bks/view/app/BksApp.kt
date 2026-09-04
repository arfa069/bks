package com.bks.view.app

import com.bks.view.navigation.AppTab
import com.bks.view.navigation.BksRouteHost
import com.bks.view.account.WechatAvatarCache
import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.viewmodel.account.AccountManagementViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.R
import com.bks.model.platform.app.BksAppContainer
import com.bks.model.platform.app.bksAppContainer
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountCloudSettingsOperationResult
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinator
import com.bks.model.application.account.AccountSessionNotice
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthGateway
import com.bks.model.application.account.cloudWritesPaused
import com.bks.model.application.account.effectiveAiCategorizationSettings
import com.bks.model.application.account.settingsSyncInFlight
import com.bks.model.domain.categorization.AiCategorizationGateway
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.view.home.HomeScreen
import com.bks.viewmodel.ledger.LedgerViewModel
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.monitoring.AutomaticBookkeepingFeatureState
import com.bks.viewmodel.monitoring.AutomaticBookkeepingViewModel
import com.bks.view.profile.ProfileDestination
import com.bks.viewmodel.review.ReviewQueueState
import com.bks.viewmodel.review.ReviewQueueViewModel
import com.bks.viewmodel.settings.DataAndBackupUiState
import com.bks.viewmodel.settings.DataAndBackupViewModel
import com.bks.model.application.sync.LedgerSyncRuntime
import com.bks.viewmodel.sync.LedgerSyncUiState
import com.bks.viewmodel.sync.LedgerSyncViewModel
import com.bks.view.common.AccountEntryMode
import com.bks.view.common.AccountEntryReturnTarget
import com.bks.view.common.components.SlidePageTransition
import com.bks.view.common.rememberBksAppState
import com.bks.view.common.theme.BksTheme
import com.bks.view.common.visual.AppWallpaper
import kotlinx.coroutines.launch

data class BksAppBindings(
    val billSyncAccessibilityAccessGranted: Boolean = false,
    val billSyncAccessibilityServiceConnected: Boolean = false,
    val billSyncBatteryOptimizationIgnored: Boolean? = null,
    val onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    val onOpenBillSyncAutoStartSettings: () -> Unit = {},
    val onOpenBillSyncPowerPolicySettings: () -> Unit = {},
    val wechatAuthCallback: WechatAuthCallback? = null,
    val onWechatAuthCallbackConsumed: () -> Unit = {}
)

data class BksAppOverrides(
    val accountRepository: AccountRepository? = null,
    val aiCategorizationGateway: AiCategorizationGateway? = null,
    val cloudAiSettingsGateway: CloudAiSettingsGateway? = null,
    val persistAccountSession: ((AccountCredentials) -> Boolean)? = null,
    val wechatAuthGateway: WechatAuthGateway? = null
)

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun BksApp(
    bindings: BksAppBindings = BksAppBindings(),
    overrides: BksAppOverrides = BksAppOverrides()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val appState = rememberBksAppState()
    val applicationContext = LocalContext.current.applicationContext
    val container = remember(applicationContext) { applicationContext.bksAppContainer }
    val avatarCache = remember(applicationContext) { com.bks.view.account.WechatAvatarCache(applicationContext) }
    val accountRepository = overrides.accountRepository ?: container.accountRepository
    val wechatAuthGateway = overrides.wechatAuthGateway ?: container.wechatAuthGateway
    val aiCategorizationGateway = overrides.aiCategorizationGateway ?: container.aiCategorizationGateway
    val applicationOwners = rememberBksApplicationOwners(container, overrides)
    val accountSessionCoordinator = applicationOwners.accountSessionCoordinator
    val ledgerSyncRuntime = applicationOwners.ledgerSyncRuntime
    val owners = rememberBksFeatureOwners(
        container = container,
        aiCategorizationGateway = aiCategorizationGateway,
        applicationOwners = applicationOwners,
        clearAvatarCache = avatarCache::clear
    )
    val accountState by accountSessionCoordinator.state.collectAsStateWithLifecycle()
    val signedIn = accountState.session is AccountSession.SignedIn

    LaunchedEffect(accountState.session) {
        when (val session = accountState.session) {
            is AccountSession.SignedIn -> avatarCache.prepareUrl(session.avatarUrl)
            AccountSession.LocalMode -> avatarCache.clear()
            null -> Unit
        }
    }

    BksAccountSessionNotices(accountSessionCoordinator, appState)
    BksFeatureNotices(owners, appState)

    DisposableEffect(lifecycleOwner, ledgerSyncRuntime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ledgerSyncRuntime.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BksTheme {
        if (accountState.isRestoring) {
            AppWallpaper(R.drawable.aa_bg_account) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("account-session-restoring"))
                }
            }
        } else {
            SlidePageTransition(
                targetState = accountState.session to appState.accountEntryMode.value,
                modifier = Modifier.fillMaxSize()
            ) { (activeAccountSession, accountEntryMode) ->
                if (activeAccountSession == null || accountEntryMode != null) {
                    BksAccountEntry(
                        appState = appState,
                        accountSession = activeAccountSession,
                        viewModelFactory = accountEntryFactory(
                            accountRepository = accountRepository,
                            wechatAuthGateway = wechatAuthGateway,
                            sessionCoordinator = accountSessionCoordinator
                        ),
                        wechatAuthCallback = bindings.wechatAuthCallback,
                        onWechatAuthCallbackConsumed = bindings.onWechatAuthCallbackConsumed,
                        avatarCache = avatarCache
                    )
                } else {
                    BksRouteHost(appState) { targetRoute, innerPadding, onManualEntryClosed, onNavigateHome ->
                        if (targetRoute.manualEntryOpen) {
                            BksManualEntryRoute(
                                ledgerState = owners.ledgerUiState,
                                ledgerViewModel = owners.ledgerViewModel,
                                innerPadding = innerPadding,
                                onManualEntryClosed = onManualEntryClosed,
                                onEntryCreated = {
                                    appState.selectedTab.value = AppTab.Ledger
                                    appState.profileDestination.value = null
                                }
                            )
                        } else {
                            when (targetRoute.tab) {
                                null -> HomeScreen(modifier = Modifier.padding(innerPadding))
                                AppTab.Review -> BksReviewRoute(
                                    reviewState = owners.reviewQueueState,
                                    reviewViewModel = owners.reviewQueueViewModel,
                                    ledgerState = owners.ledgerUiState,
                                    onCategorizationRulesChange = owners.categorizationViewModel::updateRulesAndAwait,
                                    innerPadding = innerPadding,
                                    onNavigateHome = onNavigateHome
                                )
                                AppTab.Ledger -> BksLedgerRoute(
                                    state = owners.ledgerUiState,
                                    viewModel = owners.ledgerViewModel,
                                    entryListState = appState.ledgerEntryListState,
                                    snackbarHostState = appState.snackbarHostState,
                                    innerPadding = innerPadding,
                                    onNavigateHome = onNavigateHome
                                )
                                AppTab.Reports -> BksReportsRoute(
                                    state = owners.ledgerUiState,
                                    categoryRankingListState = appState.reportCategoryRankingListState,
                                    innerPadding = innerPadding,
                                    onNavigateHome = onNavigateHome
                                )
                                AppTab.Profile -> BksProfileRoute(
                                    activeAccountSession = activeAccountSession,
                                    destination = targetRoute.profileDestination,
                                    accountManagementViewModelFactory = accountManagementFactory(
                                        accountRepository = accountRepository,
                                        wechatAuthGateway = wechatAuthGateway,
                                        sessionCoordinator = accountSessionCoordinator
                                    ),
                                    onAccountManagementSessionEnded = {
                                        appState.profileDestination.value = null
                                    }
                                ) { destination, accountManagementViewModel ->
                                    when (destination) {
                                        null -> BksProfileOverviewRoute(
                                            session = activeAccountSession,
                                            ledgerSyncEnabled = owners.ledgerSyncUiState.enabled,
                                            innerPadding = innerPadding,
                                            onDestinationSelected = {
                                                appState.profileDestination.value = it
                                            },
                                            onNavigateHome = onNavigateHome
                                        )
                                        ProfileDestination.AccountManagement -> BksAccountManagementRoute(
                                            session = activeAccountSession,
                                            runtimeState = accountState.runtimeState,
                                            deletionState = accountState.deletionState,
                                            viewModel = accountManagementViewModel,
                                            wechatAuthCallback = bindings.wechatAuthCallback,
                                            onWechatAuthCallbackConsumed =
                                                bindings.onWechatAuthCallbackConsumed,
                                            avatarCache = avatarCache,
                                            onSignInOrRegister = {
                                                appState.openAccountEntry(
                                                    mode = AccountEntryMode.SignInOrRegister,
                                                    returnTarget = AccountEntryReturnTarget.AccountManagement
                                                )
                                                appState.profileDestination.value = null
                                            },
                                            onBack = {
                                                accountManagementViewModel.closeManagement()
                                                appState.profileDestination.value = null
                                            },
                                            innerPadding = innerPadding
                                        )
                                        ProfileDestination.AutomaticBookkeeping ->
                                            BksAutomaticBookkeepingRoute(
                                                state = owners.automaticBookkeepingState,
                                                viewModel = owners.automaticBookkeepingViewModel,
                                                accessibilityAccessGranted =
                                                    bindings.billSyncAccessibilityAccessGranted,
                                                accessibilityServiceConnected =
                                                    bindings.billSyncAccessibilityServiceConnected,
                                                batteryOptimizationIgnored =
                                                    bindings.billSyncBatteryOptimizationIgnored,
                                                onOpenAccessibilitySettings =
                                                    bindings.onOpenBillSyncAccessibilitySettings,
                                                onOpenAutoStartSettings =
                                                    bindings.onOpenBillSyncAutoStartSettings,
                                                onOpenPowerPolicySettings =
                                                    bindings.onOpenBillSyncPowerPolicySettings,
                                                onBack = {
                                                    appState.profileDestination.value = null
                                                },
                                                innerPadding = innerPadding
                                            )
                                        ProfileDestination.CategorizationRules -> BksCategorizationRoute(
                                            state = owners.categorizationUiState,
                                            categories = owners.ledgerUiState.categories,
                                            viewModel = owners.categorizationViewModel,
                                            onBack = {
                                                appState.profileDestination.value = null
                                            },
                                            innerPadding = innerPadding
                                        )
                                        ProfileDestination.DataAndBackup -> BksDataAndBackupRoute(
                                            ledgerState = owners.ledgerUiState,
                                            dataAndBackupState = owners.dataAndBackupUiState,
                                            dataAndBackupViewModel = owners.dataAndBackupViewModel,
                                            snackbarHostState = appState.snackbarHostState,
                                            ledgerSyncState = owners.ledgerSyncUiState,
                                            ledgerSyncViewModel = owners.ledgerSyncViewModel,
                                            onBack = {
                                                appState.profileDestination.value = null
                                            },
                                            innerPadding = innerPadding
                                        )
                                        ProfileDestination.ComplianceAndPrivacy -> BksComplianceRoute(
                                            onBack = {
                                                appState.profileDestination.value = null
                                            },
                                            innerPadding = innerPadding
                                        )
                                        ProfileDestination.DiagnosticLogs -> BksDiagnosticLogsRoute(
                                            viewModel = owners.diagnosticLogsViewModel,
                                            onBack = {
                                                appState.profileDestination.value = null
                                            },
                                            innerPadding = innerPadding
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val signedInSession = accountState.session as? AccountSession.SignedIn
    BksLedgerSyncAccountSwitchDialog(
        signedIn = signedInSession != null,
        syncState = owners.ledgerSyncUiState,
        onSwitchAccount = {
            coroutineScope.launch {
                runLedgerSyncAccountSwitchAction(
                    switchAccount = owners.ledgerSyncViewModel::switchAccount,
                    showMessage = { message ->
                        appState.snackbarHostState.showSnackbar(message)
                    }
                )
            }
        },
        onCancelAndSignOut = {
            cancelLedgerSyncAccountSwitch(
                signedInToken = signedInSession?.token,
                launchSignOut = { token ->
                    coroutineScope.launch {
                        accountRepository.signOut(token)
                    }
                },
                moveToLocalMode = accountSessionCoordinator::moveToLocalMode
            )
        }
    )
}

private data class BksFeatureOwners(
    val diagnosticLogsViewModel: com.bks.viewmodel.diagnostics.DiagnosticLogsViewModel,
    val categorizationViewModel: com.bks.viewmodel.categorization.CategorizationViewModel,
    val categorizationUiState: com.bks.viewmodel.categorization.CategorizationUiState,
    val automaticBookkeepingViewModel: AutomaticBookkeepingViewModel,
    val automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    val ledgerViewModel: LedgerViewModel,
    val ledgerUiState: LedgerUiState,
    val reviewQueueViewModel: ReviewQueueViewModel,
    val reviewQueueState: ReviewQueueState,
    val dataAndBackupViewModel: DataAndBackupViewModel,
    val dataAndBackupUiState: DataAndBackupUiState,
    val ledgerSyncViewModel: LedgerSyncViewModel,
    val ledgerSyncUiState: LedgerSyncUiState
)

internal data class BksApplicationOwners(
    val accountSessionCoordinator: AccountSessionCoordinator,
    val cloudSettingsCoordinator: AccountCloudSettingsCoordinator,
    val ledgerSyncRuntime: LedgerSyncRuntime
)

@Composable
private fun rememberBksApplicationOwners(
    container: BksAppContainer,
    overrides: BksAppOverrides
): BksApplicationOwners = remember(container, overrides) {
    val accountSessionCoordinator = if (
        overrides.accountRepository != null || overrides.persistAccountSession != null
    ) {
        container.createAccountSessionCoordinator(
            repository = overrides.accountRepository ?: container.accountRepository,
            persistSession = overrides.persistAccountSession
        ).also { it.start() }
    } else {
        container.accountSessionCoordinator
    }
    val cloudSettingsCoordinator = if (
        overrides.accountRepository != null || overrides.persistAccountSession != null ||
        overrides.cloudAiSettingsGateway != null
    ) {
        container.createAccountCloudSettingsCoordinator(
            sessionCoordinator = accountSessionCoordinator,
            gateway = overrides.cloudAiSettingsGateway ?: container.cloudAiSettingsGateway
        ).also { it.start() }
    } else {
        container.accountCloudSettingsCoordinator
    }
    val ledgerSyncRuntime = if (
        overrides.accountRepository != null || overrides.persistAccountSession != null
    ) {
        container.createLedgerSyncRuntime(accountSessionCoordinator).also { it.start() }
    } else {
        container.ledgerSyncRuntime
    }
    BksApplicationOwners(accountSessionCoordinator, cloudSettingsCoordinator, ledgerSyncRuntime)
}

@Composable
private fun BksFeatureNotices(
    owners: BksFeatureOwners,
    appState: com.bks.view.common.BksAppState
) {
    BksReviewQueueNotices(owners.reviewQueueViewModel, appState)
    BksAutomaticBookkeepingNotices(owners.automaticBookkeepingViewModel, appState)
    LaunchedEffect(owners.categorizationViewModel) {
        owners.categorizationViewModel.notices.collect { message ->
            appState.snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun rememberBksFeatureOwners(
    container: BksAppContainer,
    aiCategorizationGateway: AiCategorizationGateway,
    applicationOwners: BksApplicationOwners,
    clearAvatarCache: () -> Unit
): BksFeatureOwners {
    val accountSessionCoordinator = applicationOwners.accountSessionCoordinator
    val cloudSettingsCoordinator = applicationOwners.cloudSettingsCoordinator
    val ledgerSyncRuntime = applicationOwners.ledgerSyncRuntime
    val diagnosticLogsViewModel: com.bks.viewmodel.diagnostics.DiagnosticLogsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                com.bks.viewmodel.diagnostics.DiagnosticLogsViewModel(container.diagnosticLogs) as T
        }
    )
    val categorizationViewModel: com.bks.viewmodel.categorization.CategorizationViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                com.bks.viewmodel.categorization.CategorizationViewModel(
                    preferencesRepository = container.preferencesRepository,
                    accountSessionState = accountSessionCoordinator.state,
                    cloudSettingsCoordinator = cloudSettingsCoordinator,
                    categorizationRulesCoordinator = container.categorizationRulesCoordinator
                ) as T
        }
    )
    val ledgerViewModel: LedgerViewModel = viewModel(
        factory = ledgerFactory(container.ledgerRepository, cloudSettingsCoordinator)
    )
    val reviewQueueViewModel: ReviewQueueViewModel = viewModel(
        factory = reviewQueueFactory(
            persistence = container.reviewQueuePersistence,
            aiCategorizationGateway = aiCategorizationGateway,
            ledgerViewModel = ledgerViewModel,
            accountSessionCoordinator = accountSessionCoordinator,
            cloudSettingsCoordinator = cloudSettingsCoordinator
        )
    )
    val dataAndBackupViewModel: DataAndBackupViewModel = viewModel(
        factory = dataAndBackupFactory(
            container = container,
            applicationOwners = applicationOwners,
            ledgerViewModel = ledgerViewModel,
            reviewQueueViewModel = reviewQueueViewModel,
            clearAvatarCache = clearAvatarCache
        )
    )
    val ledgerSyncViewModel: LedgerSyncViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerSyncViewModel(
                runtime = ledgerSyncRuntime,
                coordinator = container.ledgerSyncCoordinator,
                accountSessionState = accountSessionCoordinator.state
            ) as T
        }
    )
    val automaticBookkeepingViewModel: AutomaticBookkeepingViewModel = viewModel(
        factory = automaticBookkeepingFactory(container.preferencesRepository)
    )
    val ledgerUiState by ledgerViewModel.uiState.collectAsStateWithLifecycle()
    val reviewQueueState by reviewQueueViewModel.uiState.collectAsStateWithLifecycle()
    val dataAndBackupUiState by dataAndBackupViewModel.uiState.collectAsStateWithLifecycle()
    val ledgerSyncUiState by ledgerSyncViewModel.uiState.collectAsStateWithLifecycle()
    val automaticBookkeepingState by automaticBookkeepingViewModel.uiState
        .collectAsStateWithLifecycle()
    val categorizationUiState by categorizationViewModel.uiState.collectAsStateWithLifecycle()
    return BksFeatureOwners(
        diagnosticLogsViewModel = diagnosticLogsViewModel,
        categorizationViewModel = categorizationViewModel,
        categorizationUiState = categorizationUiState,
        automaticBookkeepingViewModel = automaticBookkeepingViewModel,
        automaticBookkeepingState = automaticBookkeepingState,
        ledgerViewModel = ledgerViewModel,
        ledgerUiState = ledgerUiState,
        reviewQueueViewModel = reviewQueueViewModel,
        reviewQueueState = reviewQueueState,
        dataAndBackupViewModel = dataAndBackupViewModel,
        dataAndBackupUiState = dataAndBackupUiState,
        ledgerSyncViewModel = ledgerSyncViewModel,
        ledgerSyncUiState = ledgerSyncUiState
    )
}

@Composable
private fun BksAutomaticBookkeepingNotices(
    viewModel: AutomaticBookkeepingViewModel,
    appState: com.bks.view.common.BksAppState
) {
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { message ->
            appState.snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun BksReviewQueueNotices(
    reviewQueueViewModel: ReviewQueueViewModel,
    appState: com.bks.view.common.BksAppState
) {
    LaunchedEffect(reviewQueueViewModel) {
        reviewQueueViewModel.notices.collect { message ->
            appState.snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun BksAccountSessionNotices(
    accountSessionCoordinator: AccountSessionCoordinator,
    appState: com.bks.view.common.BksAppState
) {
    LaunchedEffect(accountSessionCoordinator) {
        accountSessionCoordinator.notices.collect { notice ->
            appState.snackbarHostState.showSnackbar(
                when (notice) {
                    AccountSessionNotice.RefreshedCredentialsNotPersisted ->
                        "账号已验证，但最新资料未能保存到本机"
                    AccountSessionNotice.InvalidSessionMovedToLocalMode ->
                        "登录状态已失效，已切换到本地模式"
                }
            )
        }
    }
}
