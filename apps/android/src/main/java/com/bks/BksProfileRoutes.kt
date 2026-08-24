package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.feature.account.AccountManagementScreen
import com.bks.feature.account.AccountManagementScreenActions
import com.bks.feature.account.AccountManagementScreenDependencies
import com.bks.feature.account.AccountManagementScreenState
import com.bks.feature.account.AccountManagementViewModel
import com.bks.feature.account.AccountSession
import com.bks.feature.categorization.CategorizationAiUiState
import com.bks.feature.categorization.CategorizationRulesActions
import com.bks.feature.categorization.CategorizationRulesScreen
import com.bks.feature.compliance.ComplianceAndPrivacyScreen
import com.bks.feature.monitoring.AutomaticBookkeepingScreen
import com.bks.feature.monitoring.AutomaticBookkeepingUiState
import com.bks.feature.profile.ProfileDestination
import com.bks.feature.profile.ProfileOverviewScreen
import com.bks.feature.settings.DataAndBackupScreen
import com.bks.ui.AccountEntryMode
import com.bks.ui.AccountEntryReturnTarget

@Composable
internal fun BksProfileRoute(
    context: BksRouteContext,
    activeAccountSession: AccountSession,
    innerPadding: PaddingValues,
    destination: ProfileDestination?,
    onNavigateHome: () -> Unit
) {
    val dependencies = context.dependencies
    val sessionCoordinator = dependencies.account.sessionCoordinator
    val accountManagementViewModel: AccountManagementViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountManagementViewModel(
                accountRepository = dependencies.account.accountRepository,
                commitManagementCredentials = sessionCoordinator::commitManagementCredentials,
                commitVerifiedCredentials = sessionCoordinator::commitVerifiedCredentials,
                invalidateSession = sessionCoordinator::invalidateSession,
                updateDeletionState = sessionCoordinator::updateDeletionState,
                wechatAuthGateway = dependencies.account.wechatAuthGateway
            ) as T
        }
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
            context.appState.profileDestination.value = null
        }
    }
    when (destination) {
        null -> BksProfileOverviewRoute(context, activeAccountSession, innerPadding, onNavigateHome)
        ProfileDestination.AccountManagement -> BksAccountManagementRoute(
            context,
            activeAccountSession,
            accountManagementViewModel,
            innerPadding
        )
        ProfileDestination.AutomaticBookkeeping -> BksAutomaticBookkeepingRoute(context, innerPadding)
        ProfileDestination.CategorizationRules -> BksCategorizationRoute(context, innerPadding)
        ProfileDestination.DataAndBackup -> BksDataAndBackupRoute(context, innerPadding)
        ProfileDestination.ComplianceAndPrivacy -> BksComplianceRoute(context, innerPadding)
    }
}

@Composable
private fun BksProfileOverviewRoute(
    context: BksRouteContext,
    session: AccountSession,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ProfileOverviewScreen(
        session = session,
        onDestinationSelected = { context.appState.profileDestination.value = it },
        onNavigateHome = onNavigateHome,
        ledgerSyncEnabled = context.ledgerSyncUiState.enabled,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksAutomaticBookkeepingRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    AutomaticBookkeepingScreen(
        state = AutomaticBookkeepingUiState(
            enabled = context.automaticBookkeepingState.enabled,
            writeInProgress = context.automaticBookkeepingState.writeInProgress,
            accessibilityAccessGranted = context.bindings.billSyncAccessibilityAccessGranted,
            accessibilityServiceConnected = context.bindings.billSyncAccessibilityServiceConnected
        ),
        onEnabledChange = context.automaticBookkeepingViewModel::setEnabled,
        onOpenAccessibilitySettings = context.bindings.onOpenBillSyncAccessibilitySettings,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksAccountManagementRoute(
    context: BksRouteContext,
    session: AccountSession,
    viewModel: AccountManagementViewModel,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    val bindings = context.bindings
    val dependencies = context.dependencies
    val appState = context.appState
    AccountManagementScreen(
        screenState = AccountManagementScreenState(
            session = session,
            runtimeState = runtime.accountRuntimeState,
            deletionState = runtime.accountDeletionState
        ),
        dependencies = AccountManagementScreenDependencies(
            viewModel = viewModel,
            wechatAuthCallback = bindings.wechatAuthCallback,
            onWechatAuthCallbackConsumed = bindings.onWechatAuthCallbackConsumed,
            avatarCacheOverride = dependencies.account.wechatAvatarCache
        ),
        actions = AccountManagementScreenActions(
            onSignInOrRegister = {
                appState.openAccountEntry(
                    mode = AccountEntryMode.SignInOrRegister,
                    returnTarget = AccountEntryReturnTarget.AccountManagement
                )
                appState.profileDestination.value = null
            },
            onBack = {
                viewModel.closeManagement()
                appState.profileDestination.value = null
            }
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksCategorizationRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    CategorizationRulesScreen(
        rules = runtime.categorizationRules,
        onRulesChange = context.actions::persistCategorizationRules,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        aiUiState = CategorizationAiUiState(
            settings = context.presentation.effectiveAiSettings,
            signedIn = runtime.accountSession is AccountSession.SignedIn,
            cloudWritesPaused = runtime.accountDeletionState.isPending ||
                !runtime.accountRuntimeState.cloudWritesAllowed,
            settingsSyncInFlight = runtime.aiSettingsSyncInFlight
        ),
        actions = CategorizationRulesActions(
            onAiSettingsChange = context.actions::persistAiSettings,
            onBack = { context.appState.profileDestination.value = null }
        ),
    )
}

@Composable
private fun BksDataAndBackupRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    val dependencies = context.dependencies
    DataAndBackupScreen(
        ledgerEntries = context.ledgerUiState.entries,
        currentLedgerName = context.ledgerUiState.activeLedgerName,
        onExportEncryptedBackup = { passphrase ->
            dependencies.local.dataBackupRepository.exportEncryptedBackup(passphrase)
        },
        onValidateEncryptedBackup = { backup, passphrase ->
            dependencies.local.dataBackupRepository.validateEncryptedBackup(backup, passphrase)
        },
        onImportEncryptedBackup = { backup, passphrase ->
            context.ledgerViewModel.runLocalDataReplacement {
                context.reviewQueueViewModel.awaitIdleAndResetForLocalDataReplacement()
                dependencies.local.dataBackupRepository.importEncryptedBackup(backup, passphrase)
            }
        },
        onDeleteLocalData = context.actions::clearLocalData,
        onBack = { context.appState.profileDestination.value = null },
        snackbarHostState = context.appState.snackbarHostState,
        localDataReplacementInFlight = context.ledgerUiState.localDataReplacementInFlight,
        ledgerSyncState = context.ledgerSyncUiState,
        ledgerSyncActions = com.bks.feature.settings.LedgerSyncSectionActions(
            onPreview = context.ledgerSyncViewModel::preview,
            onDismissPreview = context.ledgerSyncViewModel::dismissPreview,
            onEnable = context.ledgerSyncViewModel::enable,
            onSyncNow = context.ledgerSyncViewModel::synchronize,
            onDisable = context.ledgerSyncViewModel::disable,
            onResolveConflict = context.ledgerSyncViewModel::resolveConflict
        ),
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksComplianceRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = BuildConfig.DEBUG,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
