package com.bks.view.app

import com.bks.model.application.account.cloudWritesPaused
import com.bks.model.application.account.settingsSyncInFlight
import com.bks.BuildConfig
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.model.domain.account.AccountDeletionState
import com.bks.view.account.AccountManagementScreen
import com.bks.view.account.AccountManagementScreenActions
import com.bks.view.account.AccountManagementScreenPlatform
import com.bks.view.account.AccountManagementScreenState
import com.bks.view.account.WechatAccountManagementActions
import com.bks.viewmodel.account.AccountManagementViewModel
import com.bks.model.domain.account.WechatAuthPurpose
import com.bks.viewmodel.account.toProfileAccountSummaryUiModel
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.view.account.WechatAvatarCache
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.viewmodel.categorization.CategorizationUiState
import com.bks.viewmodel.categorization.CategorizationViewModel
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.view.categorization.CategorizationRulesActions
import com.bks.view.categorization.CategorizationRulesScreen
import com.bks.view.compliance.ComplianceAndPrivacyScreen
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.viewmodel.monitoring.AutomaticBookkeepingFeatureState
import com.bks.view.monitoring.AutomaticBookkeepingScreen
import com.bks.viewmodel.monitoring.AutomaticBookkeepingUiState
import com.bks.viewmodel.monitoring.AutomaticBookkeepingViewModel
import com.bks.view.profile.ProfileDestination
import com.bks.view.profile.ProfileOverviewScreen
import com.bks.viewmodel.settings.DataAndBackupActions
import com.bks.viewmodel.settings.LedgerCsvEntryUiModel
import com.bks.view.settings.DataAndBackupScreen
import com.bks.viewmodel.settings.DataAndBackupUiState
import com.bks.viewmodel.settings.DataAndBackupViewModel
import com.bks.viewmodel.sync.LedgerSyncSectionActions
import com.bks.viewmodel.sync.LedgerSyncUiState
import com.bks.viewmodel.sync.LedgerSyncViewModel
@Composable
internal fun BksProfileRoute(
    activeAccountSession: AccountSession,
    destination: ProfileDestination?,
    accountManagementViewModelFactory: ViewModelProvider.Factory,
    onAccountManagementSessionEnded: () -> Unit,
    content: @Composable (ProfileDestination?, AccountManagementViewModel) -> Unit
) {
    val accountManagementViewModel: AccountManagementViewModel = viewModel(
        factory = accountManagementViewModelFactory
    )
    var managementHadSignedInSession by remember { mutableStateOf(false) }
    LaunchedEffect(destination, activeAccountSession) {
        if (destination != ProfileDestination.AccountManagement) {
            accountManagementViewModel.closeManagement()
            managementHadSignedInSession = false
        } else if (activeAccountSession is AccountSession.SignedIn) {
            managementHadSignedInSession = true
        } else if (managementHadSignedInSession) {
            accountManagementViewModel.closeManagement()
            onAccountManagementSessionEnded()
        }
    }
    content(destination, accountManagementViewModel)
}

@Composable
internal fun BksProfileOverviewRoute(
    session: AccountSession,
    ledgerSyncEnabled: Boolean,
    innerPadding: PaddingValues,
    onDestinationSelected: (ProfileDestination) -> Unit,
    onNavigateHome: () -> Unit
) {
    ProfileOverviewScreen(
        accountSummary = session.toProfileAccountSummaryUiModel(),
        onDestinationSelected = onDestinationSelected,
        onNavigateHome = onNavigateHome,
        ledgerSyncEnabled = ledgerSyncEnabled,
        isDebugBuild = BuildConfig.DEBUG,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
@Suppress("LongParameterList")
internal fun BksAutomaticBookkeepingRoute(
    state: AutomaticBookkeepingFeatureState,
    viewModel: AutomaticBookkeepingViewModel,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    batteryOptimizationIgnored: Boolean?,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onOpenPowerPolicySettings: () -> Unit,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    AutomaticBookkeepingScreen(
        state = AutomaticBookkeepingUiState(
            enabled = state.enabled,
            writeInProgress = state.writeInProgress,
            accessibilityAccessGranted = accessibilityAccessGranted,
            accessibilityServiceConnected = accessibilityServiceConnected,
            batteryOptimizationIgnored = batteryOptimizationIgnored
        ),
        onEnabledChange = viewModel::setEnabled,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenAutoStartSettings = onOpenAutoStartSettings,
        onOpenPowerPolicySettings = onOpenPowerPolicySettings,
        onBack = onBack,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
@Suppress("LongParameterList")
internal fun BksAccountManagementRoute(
    session: AccountSession,
    runtimeState: AccountRuntimeState,
    deletionState: AccountDeletionState,
    viewModel: AccountManagementViewModel,
    wechatAuthCallback: WechatAuthCallback?,
    onWechatAuthCallbackConsumed: () -> Unit,
    avatarCache: WechatAvatarCache,
    onSignInOrRegister: () -> Unit,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    val operationState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        viewModel.activate(session)
    }
    LaunchedEffect(wechatAuthCallback, session) {
        val signedIn = session as? AccountSession.SignedIn ?: return@LaunchedEffect
        val callback = wechatAuthCallback ?: return@LaunchedEffect
        if (callback.purpose != WechatAuthPurpose.LinkCurrentAccount) return@LaunchedEffect
        onWechatAuthCallbackConsumed()
        viewModel.handleWechatCallback(signedIn, callback)
    }
    val signedIn = session as? AccountSession.SignedIn
    AccountManagementScreen(
        screenState = AccountManagementScreenState(
            session = session,
            runtimeState = runtimeState,
            deletionState = deletionState,
            operationState = operationState,
            wechatAvailable = viewModel.wechatAvailable
        ),
        platform = AccountManagementScreenPlatform(
            avatarCacheOverride = avatarCache
        ),
        actions = AccountManagementScreenActions(
            onSignInOrRegister = onSignInOrRegister,
            onBack = {
                viewModel.closeManagement()
                onBack()
            },
            onRetryVerification = {
                signedIn?.let { viewModel.retryVerification(it, deletionState) }
            },
            onSignOut = {
                signedIn?.let(viewModel::signOut)
            },
            onRequestDeletion = {
                signedIn?.let(viewModel::requestDeletion)
            },
            onCancelDeletion = {
                signedIn?.let(viewModel::cancelDeletion)
            },
            wechat = accountManagementWechatActions(viewModel, signedIn, deletionState)
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

private fun accountManagementWechatActions(
    viewModel: AccountManagementViewModel,
    session: AccountSession.SignedIn?,
    deletionState: AccountDeletionState
) = WechatAccountManagementActions(
    onStartWechatLink = { session?.let(viewModel::startWechatLink) },
    onBeginAvatarRead = viewModel::beginAvatarRead,
    onCancelAvatarRead = viewModel::cancelAvatarRead,
    onAvatarDataReady = { data -> session?.let { viewModel.updateAvatar(it, deletionState, data) } },
    onAvatarReadFailure = viewModel::reportAvatarReadFailure,
    onIdentityFailure = viewModel::reportIdentityFailure,
    onOpenIdentityPage = viewModel::openIdentityPage,
    onCloseIdentityPage = viewModel::closeIdentityPage,
    onIdentityContextChanged = { viewModel.identityContextChanged() },
    onClearIdentityError = viewModel::clearIdentityError,
    onUpdateNickname = { nickname -> session?.let { viewModel.updateNickname(it, deletionState, nickname) } },
    onPrepareIdentifierLink = { state -> session?.let { viewModel.prepareIdentifierLink(it, state) } },
    onConfirmIdentifier = { state -> session?.let { viewModel.confirmIdentifier(it, state) } },
    onConfirmPhonePassword = { state -> session?.let { viewModel.confirmPhonePassword(it, state) } },
    onConfirmMerge = { text -> session?.let { viewModel.confirmMerge(it, text) } },
    onRequestUnlinkCode = { identifier -> session?.let { viewModel.requestUnlinkCode(it, identifier) } },
    onUnlinkWechat = { state -> session?.let { viewModel.unlinkWechat(it, state) } }
)

@Composable
@Suppress("LongParameterList")
internal fun BksCategorizationRoute(
    state: CategorizationUiState,
    categories: List<LedgerCategoryUiModel>,
    viewModel: CategorizationViewModel,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    CategorizationRulesScreen(
        rules = state.rules,
        onRulesChange = viewModel::updateRules,
        categories = categories,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        aiUiState = state.ai,
        actions = CategorizationRulesActions(
            onAiSettingsChange = viewModel::updateAiSettings,
            onBack = onBack
        )
    )
}

@Composable
@Suppress("LongParameterList")
internal fun BksDataAndBackupRoute(
    ledgerState: LedgerUiState,
    dataAndBackupState: DataAndBackupUiState,
    dataAndBackupViewModel: DataAndBackupViewModel,
    snackbarHostState: SnackbarHostState,
    ledgerSyncState: LedgerSyncUiState,
    ledgerSyncViewModel: LedgerSyncViewModel,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    val backupLedgerEntries = remember(ledgerState.entries) {
        ledgerState.entries.map { entry ->
            LedgerCsvEntryUiModel(
                id = entry.id,
                transactionTimeText = entry.transactionTimeText,
                title = entry.title,
                amountMinor = entry.amountMinor,
                flowType = entry.flowType.name,
                category = entry.category,
                sourceLabel = entry.sourceLabel,
                kindLabel = entry.kindLabel,
                note = entry.note
            )
        }
    }
    DataAndBackupScreen(
        ledgerEntries = backupLedgerEntries,
        currentLedgerName = ledgerState.activeLedgerName,
        dataAndBackupState = dataAndBackupState,
        dataAndBackupActions = DataAndBackupActions(
            onExport = dataAndBackupViewModel::export,
            onValidate = dataAndBackupViewModel::validate,
            onImport = dataAndBackupViewModel::import,
            onDelete = dataAndBackupViewModel::delete,
            onAttachUi = dataAndBackupViewModel::attachUi,
            onDetachUi = dataAndBackupViewModel::detachUi,
            onAcknowledgeValidation = dataAndBackupViewModel::acknowledgeValidation,
            onEndValidatedContinuation = dataAndBackupViewModel::endValidatedContinuation,
            onClearValidation = dataAndBackupViewModel::clearValidation,
            onAcknowledgeNotice = dataAndBackupViewModel::acknowledgeNotice
        ),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        ledgerSyncState = ledgerSyncState,
        ledgerSyncActions = LedgerSyncSectionActions(
            onPreview = ledgerSyncViewModel::preview,
            onDismissPreview = ledgerSyncViewModel::dismissPreview,
            onEnable = ledgerSyncViewModel::enable,
            onSyncNow = ledgerSyncViewModel::synchronize,
            onDisable = ledgerSyncViewModel::disable,
            onResolveConflict = ledgerSyncViewModel::resolveConflict
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
internal fun BksComplianceRoute(
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = BuildConfig.DEBUG,
        onBack = onBack,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
