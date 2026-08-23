package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.feature.account.AccountManagementScreen
import com.bks.feature.account.AccountSession
import com.bks.feature.categorization.CategorizationAiUiState
import com.bks.feature.categorization.CategorizationRulesActions
import com.bks.feature.categorization.CategorizationRulesScreen
import com.bks.feature.compliance.ComplianceAndPrivacyScreen
import com.bks.feature.monitoring.AutomaticBookkeepingScreen
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
    when (destination) {
        null -> BksProfileOverviewRoute(context, activeAccountSession, innerPadding, onNavigateHome)
        ProfileDestination.AccountManagement -> BksAccountManagementRoute(context, activeAccountSession, innerPadding)
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
        enabled = context.runtime.automaticBookkeepingEnabled,
        accessibilityAccessGranted = context.bindings.billSyncAccessibilityAccessGranted,
        accessibilityServiceConnected = context.bindings.billSyncAccessibilityServiceConnected,
        onEnabledChange = context.actions::setAutomaticBookkeepingEnabled,
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
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    val bindings = context.bindings
    val dependencies = context.dependencies
    val appState = context.appState
    val sessionCoordinator = dependencies.account.sessionCoordinator
    AccountManagementScreen(
        session = session,
        runtimeState = runtime.accountRuntimeState,
        deletionState = runtime.accountDeletionState,
        accountRepository = dependencies.account.accountRepository,
        onSignInOrRegister = {
            appState.openAccountEntry(
                mode = AccountEntryMode.SignInOrRegister,
                returnTarget = AccountEntryReturnTarget.AccountManagement
            )
            appState.profileDestination.value = null
        },
        onSessionVerified = {},
        onInvalidSession = {
            sessionCoordinator.moveToLocalMode()
            appState.profileDestination.value = null
        },
        persistSession = sessionCoordinator::commit,
        clearPersistedSession = sessionCoordinator::clearPersistedSession,
        wechatAuthGateway = dependencies.account.wechatAuthGateway,
        wechatAuthCallback = bindings.wechatAuthCallback,
        onWechatAuthCallbackConsumed = bindings.onWechatAuthCallbackConsumed,
        avatarCacheOverride = dependencies.account.wechatAvatarCache,
        onSignedOut = {
            sessionCoordinator.moveToLocalMode()
            appState.profileDestination.value = null
        },
        onDeletionStateChange = sessionCoordinator::updateDeletionState,
        onBack = { appState.profileDestination.value = null },
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
    val runtime = context.runtime
    val dependencies = context.dependencies
    DataAndBackupScreen(
        ledgerEntries = context.presentation.ledgerEntries,
        currentLedgerName = context.presentation.activeLedgerName,
        onExportEncryptedBackup = { passphrase ->
            dependencies.local.dataBackupRepository.exportEncryptedBackup(passphrase)
        },
        onValidateEncryptedBackup = { backup, passphrase ->
            dependencies.local.dataBackupRepository.validateEncryptedBackup(backup, passphrase)
        },
        onImportEncryptedBackup = { backup, passphrase ->
            dependencies.local.dataBackupRepository.importEncryptedBackup(backup, passphrase)
            runtime.reviewState = com.bks.feature.review.ReviewQueueState()
        },
        onDeleteLocalData = context.actions::clearLocalData,
        onBack = { context.appState.profileDestination.value = null },
        snackbarHostState = context.appState.snackbarHostState,
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
