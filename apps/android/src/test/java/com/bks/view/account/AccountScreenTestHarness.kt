package com.bks.view.account

import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.toSignedInSession
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthGateway
import com.bks.viewmodel.account.AccountEntryViewModel
import com.bks.viewmodel.account.AccountUiState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bks.view.compliance.ComplianceMaterialsScreen
@Composable
@Suppress("LongParameterList")
internal fun AccountScreen(
    modifier: Modifier = Modifier,
    initialState: AccountUiState = AccountUiState(),
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean = { true },
    clearPersistedSession: () -> Boolean = { true },
    wechatAuthGateway: WechatAuthGateway? = null,
    wechatAuthCallback: WechatAuthCallback? = null,
    onWechatAuthCallbackConsumed: () -> Unit = {},
    avatarCacheOverride: WechatAvatarCache? = null,
    onSessionChange: (AccountSession) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var showCompliance by remember { mutableStateOf(false) }
    val viewModel = remember(accountRepository, wechatAuthGateway) {
        AccountEntryViewModel(
            accountRepository = accountRepository,
            commitSession = { credentials ->
                persistSession(credentials).also { committed ->
                    if (committed) onSessionChange(credentials.toSignedInSession())
                }
            },
            clearPersistedSession = clearPersistedSession,
            enterLocalMode = { onSessionChange(AccountSession.LocalMode) },
            wechatAuthGateway = wechatAuthGateway
        )
    }
    if (showCompliance) {
        BackHandler { showCompliance = false }
        ComplianceMaterialsScreen(onBack = { showCompliance = false })
    } else {
        AccountScreen(
            dependencies = AccountScreenDependencies(
                viewModel = viewModel,
                wechatAuthCallback = wechatAuthCallback,
                onWechatAuthCallbackConsumed = onWechatAuthCallbackConsumed,
                avatarCacheOverride = avatarCacheOverride
            ),
            modifier = modifier,
            initialState = initialState,
            onComplianceClick = { showCompliance = true },
            onBack = onBack
        )
    }
}
