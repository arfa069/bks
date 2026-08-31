package com.bks.view.app

import com.bks.view.common.AccountEntryMode
import com.bks.view.navigation.AppTab
import com.bks.R
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
import com.bks.viewmodel.account.AccountEntryViewModel
import com.bks.view.account.AccountScreenDependencies
import com.bks.model.domain.account.AccountSession
import com.bks.view.account.AccountScreen
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.view.account.WechatAvatarCache
import com.bks.view.compliance.ComplianceMaterialsScreen
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.LedgerViewModel
import com.bks.view.ledger.ManualLedgerEntryScreen
import com.bks.view.profile.ProfileDestination
import com.bks.view.common.BksAppState
import com.bks.view.common.AccountEntryReturnTarget
import com.bks.view.common.components.AppBottomNavigationBar
import com.bks.view.common.components.SlidePageTransition
import com.bks.view.common.visual.AppWallpaper
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
