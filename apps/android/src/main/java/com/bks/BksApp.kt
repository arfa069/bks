package com.bks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.feature.account.AccountCloudSettingsCoordinator
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionCoordinator
import com.bks.feature.account.AccountSessionNotice
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.home.HomeScreen
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.monitoring.AutomaticBookkeepingFeatureState
import com.bks.feature.monitoring.AutomaticBookkeepingViewModel
import com.bks.feature.profile.ProfileDestination
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.review.ReviewQueueViewModel
import com.bks.feature.settings.DataAndBackupUiState
import com.bks.feature.settings.DataAndBackupViewModel
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.feature.sync.LedgerSyncViewModel
import com.bks.ui.AccountEntryMode
import com.bks.ui.AccountEntryReturnTarget
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.rememberBksAppState
import com.bks.ui.theme.BksTheme
import com.bks.ui.visual.AppWallpaper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class BksAppBindings(
    val billSyncAccessibilityAccessGranted: Boolean = false,
    val billSyncAccessibilityServiceConnected: Boolean = true,
    val onOpenBillSyncAccessibilitySettings: () -> Unit = {},
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
    val dependencies = remember(container, overrides) {
        container.createAppDependencies(overrides)
    }
    val runtime = rememberBksAppRuntimeState()
    val accountSessionCoordinator = dependencies.account.sessionCoordinator
    val cloudSettingsCoordinator = dependencies.account.cloudSettingsCoordinator
    val owners = rememberBksFeatureOwners(
        dependencies = dependencies,
        accountSessionCoordinator = accountSessionCoordinator,
        cloudSettingsCoordinator = cloudSettingsCoordinator
    )
    val accountState by accountSessionCoordinator.state.collectAsStateWithLifecycle()
    val cloudSettingsState by cloudSettingsCoordinator.state.collectAsStateWithLifecycle()
    val actions = androidx.compose.runtime.remember(
        dependencies,
        runtime,
        appState,
        coroutineScope
    ) {
        BksAppActions(
            dependencies = dependencies,
            runtime = runtime,
            appState = appState,
            coroutineScope = coroutineScope
        )
    }
    val presentation = bksAppPresentation(accountState, cloudSettingsState)

    BksAccountSessionNotices(accountSessionCoordinator, appState)
    BksFeatureNotices(owners, appState)

    BksAppEffects(
        context = BksAppEffectsContext(
            dependencies = dependencies,
            bindings = bindings,
            runtime = runtime,
            actions = actions,
            appState = appState,
            lifecycleOwner = lifecycleOwner
        )
    )

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
                        viewModelFactory = accountEntryFactory(dependencies, accountSessionCoordinator),
                        wechatAuthCallback = bindings.wechatAuthCallback,
                        onWechatAuthCallbackConsumed = bindings.onWechatAuthCallbackConsumed,
                        avatarCache = dependencies.account.wechatAvatarCache
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
                                    categorizationRules = runtime.categorizationRules,
                                    onCategorizationRulesChange = actions::persistCategorizationRules,
                                    accountToken = presentation.accountToken,
                                    aiSettings = presentation.effectiveAiSettings,
                                    aiCategorizationGateway = dependencies.aiCategorizationGateway,
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
                                        dependencies,
                                        accountSessionCoordinator
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
                                            avatarCache = dependencies.account.wechatAvatarCache,
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
                                                onOpenAccessibilitySettings =
                                                    bindings.onOpenBillSyncAccessibilitySettings,
                                                onBack = {
                                                    appState.profileDestination.value = null
                                                },
                                                innerPadding = innerPadding
                                            )
                                        ProfileDestination.CategorizationRules -> BksCategorizationRoute(
                                            rules = runtime.categorizationRules,
                                            onRulesChange = actions::persistCategorizationRules,
                                            aiSettings = presentation.effectiveAiSettings,
                                            signedIn = presentation.signedIn,
                                            cloudWritesPaused = presentation.cloudWritesPaused,
                                            settingsSyncInFlight = presentation.settingsSyncInFlight,
                                            onAiSettingsChange = actions::persistAiSettings,
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
                        dependencies.account.accountRepository.signOut(token)
                    }
                },
                moveToLocalMode = accountSessionCoordinator::moveToLocalMode
            )
        }
    )
}

private data class BksFeatureOwners(
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

@Composable
private fun BksFeatureNotices(
    owners: BksFeatureOwners,
    appState: com.bks.ui.BksAppState
) {
    BksReviewQueueNotices(owners.reviewQueueViewModel, appState)
    BksAutomaticBookkeepingNotices(owners.automaticBookkeepingViewModel, appState)
}

@Composable
private fun rememberBksFeatureOwners(
    dependencies: BksAppDependencies,
    accountSessionCoordinator: AccountSessionCoordinator,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
): BksFeatureOwners {
    val ledgerViewModel: LedgerViewModel = viewModel(
        factory = ledgerFactory(dependencies, cloudSettingsCoordinator)
    )
    val reviewQueueViewModel: ReviewQueueViewModel = viewModel(
        factory = reviewQueueFactory(dependencies, ledgerViewModel)
    )
    val dataAndBackupViewModel: DataAndBackupViewModel = viewModel(
        factory = dataAndBackupFactory(dependencies, ledgerViewModel, reviewQueueViewModel)
    )
    val ledgerSyncViewModel: LedgerSyncViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerSyncViewModel(
                runtime = dependencies.sync.runtime,
                coordinator = dependencies.sync.coordinator,
                accountSessionState = accountSessionCoordinator.state
            ) as T
        }
    )
    val automaticBookkeepingViewModel: AutomaticBookkeepingViewModel = viewModel(
        factory = automaticBookkeepingFactory(dependencies)
    )
    val ledgerUiState by ledgerViewModel.uiState.collectAsStateWithLifecycle()
    val reviewQueueState by reviewQueueViewModel.uiState.collectAsStateWithLifecycle()
    val dataAndBackupUiState by dataAndBackupViewModel.uiState.collectAsStateWithLifecycle()
    val ledgerSyncUiState by ledgerSyncViewModel.uiState.collectAsStateWithLifecycle()
    val automaticBookkeepingState by automaticBookkeepingViewModel.uiState
        .collectAsStateWithLifecycle()
    return BksFeatureOwners(
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
    appState: com.bks.ui.BksAppState
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
    appState: com.bks.ui.BksAppState
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
    appState: com.bks.ui.BksAppState
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

private fun ledgerFactory(
    dependencies: BksAppDependencies,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
        repository = dependencies.local.ledgerRepository,
        cloudSettingsCoordinator = cloudSettingsCoordinator
    ) as T
}

private fun reviewQueueFactory(
    dependencies: BksAppDependencies,
    ledgerViewModel: LedgerViewModel
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewQueueViewModel(
        persistence = dependencies.local.reviewQueuePersistence,
        localDataReplacementInFlight = ledgerViewModel.localDataReplacementInFlight
    ) as T
}

private fun dataAndBackupFactory(
    dependencies: BksAppDependencies,
    ledgerViewModel: LedgerViewModel,
    reviewQueueViewModel: ReviewQueueViewModel
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DataAndBackupViewModel(
        exportEncryptedBackup = dependencies.local.dataBackupRepository::exportEncryptedBackup,
        validateEncryptedBackup = dependencies.local.dataBackupRepository::validateEncryptedBackup,
        importEncryptedBackup = dependencies.local.dataBackupRepository::importEncryptedBackup,
        serializeWithSync = dependencies.sync.coordinator::withLocalDataReplacement,
        runLocalDataReplacement = ledgerViewModel::runLocalDataReplacement,
        clearLocalData = ledgerViewModel::clearLocalData,
        awaitReviewIdle = reviewQueueViewModel::awaitIdleAndResetForLocalDataReplacement,
        clearAvatarCache = dependencies.account.wechatAvatarCache::clear,
        clearCloudSettingsLocalState = dependencies.account.cloudSettingsCoordinator::clearLocalState,
        disableSync = dependencies.sync.runtime::disable,
        clearDiagnostics = {
            dependencies.diagnosticLogs.clear(keepEnabledPreference = false)
        }
    ) as T
}

private fun automaticBookkeepingFactory(
    dependencies: BksAppDependencies
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = dependencies.local.preferencesRepository
        return AutomaticBookkeepingViewModel(
            enabled = repository.userPreferences
                .map { it.automaticBookkeepingEnabled }
                .distinctUntilChanged(),
            persistEnabled = repository::setAutomaticBookkeepingEnabled
        ) as T
    }
}
