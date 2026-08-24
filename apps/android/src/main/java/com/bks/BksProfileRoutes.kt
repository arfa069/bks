package com.bks

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.feature.account.AccountDeletionUiState
import com.bks.feature.account.AccountManagementScreen
import com.bks.feature.account.AccountManagementScreenActions
import com.bks.feature.account.AccountManagementScreenDependencies
import com.bks.feature.account.AccountManagementScreenState
import com.bks.feature.account.AccountManagementViewModel
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountSession
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAvatarCache
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationAiUiState
import com.bks.feature.categorization.CategorizationRule
import com.bks.feature.categorization.CategorizationRulesActions
import com.bks.feature.categorization.CategorizationRulesScreen
import com.bks.feature.compliance.ComplianceAndPrivacyScreen
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.monitoring.AutomaticBookkeepingFeatureState
import com.bks.feature.monitoring.AutomaticBookkeepingScreen
import com.bks.feature.monitoring.AutomaticBookkeepingUiState
import com.bks.feature.monitoring.AutomaticBookkeepingViewModel
import com.bks.feature.profile.ProfileDestination
import com.bks.feature.profile.ProfileOverviewScreen
import com.bks.feature.settings.DataAndBackupActions
import com.bks.feature.settings.DataAndBackupScreen
import com.bks.feature.settings.DataAndBackupUiState
import com.bks.feature.settings.DataAndBackupViewModel
import com.bks.feature.settings.LedgerSyncSectionActions
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.feature.sync.LedgerSyncViewModel

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
        session = session,
        onDestinationSelected = onDestinationSelected,
        onNavigateHome = onNavigateHome,
        ledgerSyncEnabled = ledgerSyncEnabled,
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
    onOpenAccessibilitySettings: () -> Unit,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    AutomaticBookkeepingScreen(
        state = AutomaticBookkeepingUiState(
            enabled = state.enabled,
            writeInProgress = state.writeInProgress,
            accessibilityAccessGranted = accessibilityAccessGranted,
            accessibilityServiceConnected = accessibilityServiceConnected
        ),
        onEnabledChange = viewModel::setEnabled,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
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
    deletionState: AccountDeletionUiState,
    viewModel: AccountManagementViewModel,
    wechatAuthCallback: WechatAuthCallback?,
    onWechatAuthCallbackConsumed: () -> Unit,
    avatarCache: WechatAvatarCache,
    onSignInOrRegister: () -> Unit,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    AccountManagementScreen(
        screenState = AccountManagementScreenState(
            session = session,
            runtimeState = runtimeState,
            deletionState = deletionState
        ),
        dependencies = AccountManagementScreenDependencies(
            viewModel = viewModel,
            wechatAuthCallback = wechatAuthCallback,
            onWechatAuthCallbackConsumed = onWechatAuthCallbackConsumed,
            avatarCacheOverride = avatarCache
        ),
        actions = AccountManagementScreenActions(
            onSignInOrRegister = onSignInOrRegister,
            onBack = onBack
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
@Suppress("LongParameterList")
internal fun BksCategorizationRoute(
    rules: List<CategorizationRule>,
    onRulesChange: (List<CategorizationRule>) -> Unit,
    aiSettings: AiCategorizationSettings,
    signedIn: Boolean,
    cloudWritesPaused: Boolean,
    settingsSyncInFlight: Boolean,
    onAiSettingsChange: (AiCategorizationSettings) -> Unit,
    onBack: () -> Unit,
    innerPadding: PaddingValues
) {
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = onRulesChange,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        aiUiState = CategorizationAiUiState(
            settings = aiSettings,
            signedIn = signedIn,
            cloudWritesPaused = cloudWritesPaused,
            settingsSyncInFlight = settingsSyncInFlight
        ),
        actions = CategorizationRulesActions(
            onAiSettingsChange = onAiSettingsChange,
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
    DataAndBackupScreen(
        ledgerEntries = ledgerState.entries,
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
