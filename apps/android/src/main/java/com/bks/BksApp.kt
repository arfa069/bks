package com.bks

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
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.account.AccountCloudSettingsCoordinator
import com.bks.feature.account.AccountCloudSettingsOperationResult
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionCoordinator
import com.bks.feature.account.AccountSessionNotice
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.account.cloudWritesPaused
import com.bks.feature.account.effectiveAiCategorizationSettings
import com.bks.feature.account.settingsSyncInFlight
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.AiCategorizationClient
import com.bks.feature.categorization.CategorizationRulesUpdate
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.home.HomeScreen
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.monitoring.AutomaticBookkeepingFeatureState
import com.bks.feature.monitoring.AutomaticBookkeepingViewModel
import com.bks.feature.profile.ProfileDestination
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.review.ReviewQueueViewModel
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.settings.DataAndBackupUiState
import com.bks.feature.settings.DataAndBackupViewModel
import com.bks.feature.sync.LedgerSyncRuntime
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
    val accountRepository = overrides.accountRepository ?: container.accountRepository
    val wechatAuthGateway = overrides.wechatAuthGateway ?: container.wechatAuthGateway
    val aiCategorizationGateway = overrides.aiCategorizationGateway ?: container.aiCategorizationGateway
    val accountSessionCoordinator = remember(container, overrides) {
        container.resolveAccountSessionCoordinator(overrides)
    }
    val cloudSettingsCoordinator = remember(container, overrides, accountSessionCoordinator) {
        container.resolveAccountCloudSettingsCoordinator(overrides, accountSessionCoordinator)
    }
    val ledgerSyncRuntime = remember(container, overrides, accountSessionCoordinator) {
        container.resolveLedgerSyncRuntime(overrides, accountSessionCoordinator)
    }
    val owners = rememberBksFeatureOwners(
        container = container,
        aiCategorizationGateway = aiCategorizationGateway,
        accountSessionCoordinator = accountSessionCoordinator,
        cloudSettingsCoordinator = cloudSettingsCoordinator,
        ledgerSyncRuntime = ledgerSyncRuntime
    )
    val accountState by accountSessionCoordinator.state.collectAsStateWithLifecycle()
    val cloudSettingsState by cloudSettingsCoordinator.state.collectAsStateWithLifecycle()
    val categorizationRules by container.preferencesRepository.categorizationRules
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val signedIn = accountState.session is AccountSession.SignedIn
    val areCloudWritesPaused = cloudWritesPaused(accountState)
    val isSettingsSyncInFlight = settingsSyncInFlight(accountState, cloudSettingsState)
    val effectiveAiSettings = effectiveAiCategorizationSettings(accountState, cloudSettingsState)
    val onCategorizationRulesChange: (CategorizationRulesUpdate) -> Unit = { update ->
        coroutineScope.launch {
            container.preferencesRepository.updateCategorizationRules(update)
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
                        avatarCache = container.wechatAvatarCache
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
                                    onCategorizationRulesChange = onCategorizationRulesChange,
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
                                            avatarCache = container.wechatAvatarCache,
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
                                            rules = categorizationRules,
                                            onRulesChange = onCategorizationRulesChange,
                                            aiSettings = effectiveAiSettings,
                                            signedIn = signedIn,
                                            cloudWritesPaused = areCloudWritesPaused,
                                            settingsSyncInFlight = isSettingsSyncInFlight,
                                            onAiSettingsChange = { nextSettings ->
                                                coroutineScope.launch {
                                                    when (
                                                        val result = cloudSettingsCoordinator
                                                            .updateAiSettings(nextSettings)
                                                    ) {
                                                        is AccountCloudSettingsOperationResult.CloudFailure ->
                                                            appState.snackbarHostState.showSnackbar(
                                                                result.reason.toAiSettingsMessage()
                                                            )
                                                        AccountCloudSettingsOperationResult.CloudWritesBlocked ->
                                                            appState.snackbarHostState.showSnackbar(
                                                                if (!signedIn) {
                                                                    "登录后才能同步云端 AI 设置"
                                                                } else {
                                                                    "账号当前不允许写入云端设置"
                                                                }
                                                            )
                                                        AccountCloudSettingsOperationResult.InFlight,
                                                        AccountCloudSettingsOperationResult.Stale,
                                                        AccountCloudSettingsOperationResult.Success -> Unit
                                                    }
                                                }
                                            },
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
                        accountRepository.signOut(token)
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
    container: BksAppContainer,
    aiCategorizationGateway: AiCategorizationGateway,
    accountSessionCoordinator: AccountSessionCoordinator,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator,
    ledgerSyncRuntime: LedgerSyncRuntime
): BksFeatureOwners {
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
            ledgerSyncRuntime = ledgerSyncRuntime,
            cloudSettingsCoordinator = cloudSettingsCoordinator,
            ledgerViewModel = ledgerViewModel,
            reviewQueueViewModel = reviewQueueViewModel
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
    repository: LocalLedgerRepository,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
        repository = repository,
        cloudSettingsCoordinator = cloudSettingsCoordinator
    ) as T
}

private fun reviewQueueFactory(
    persistence: ReviewQueuePersistence,
    aiCategorizationGateway: AiCategorizationGateway,
    ledgerViewModel: LedgerViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewQueueViewModel(
        persistence = persistence,
        localDataReplacementInFlight = ledgerViewModel.localDataReplacementInFlight,
        aiCategorizationClient = AiCategorizationClient(aiCategorizationGateway),
        accountSessionState = accountSessionCoordinator.state,
        cloudSettingsState = cloudSettingsCoordinator.state
    ) as T
}

private fun dataAndBackupFactory(
    container: BksAppContainer,
    ledgerSyncRuntime: LedgerSyncRuntime,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator,
    ledgerViewModel: LedgerViewModel,
    reviewQueueViewModel: ReviewQueueViewModel
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DataAndBackupViewModel(
        exportEncryptedBackup = container.dataBackupRepository::exportEncryptedBackup,
        validateEncryptedBackup = container.dataBackupRepository::validateEncryptedBackup,
        importEncryptedBackup = container.dataBackupRepository::importEncryptedBackup,
        serializeWithSync = container.ledgerSyncCoordinator::withLocalDataReplacement,
        runLocalDataReplacement = ledgerViewModel::runLocalDataReplacement,
        clearLocalData = ledgerViewModel::clearLocalData,
        awaitReviewIdle = reviewQueueViewModel::awaitIdleAndResetForLocalDataReplacement,
        clearAvatarCache = container.wechatAvatarCache::clear,
        clearCloudSettingsLocalState = cloudSettingsCoordinator::clearLocalState,
        disableSync = ledgerSyncRuntime::disable,
        clearDiagnostics = {
            container.diagnosticLogs.clear(keepEnabledPreference = false)
        }
    ) as T
}

private fun automaticBookkeepingFactory(
    repository: LocalPreferencesRepository
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AutomaticBookkeepingViewModel(
            enabled = repository.userPreferences
                .map { it.automaticBookkeepingEnabled }
                .distinctUntilChanged(),
            persistEnabled = repository::setAutomaticBookkeepingEnabled
        ) as T
    }
}
