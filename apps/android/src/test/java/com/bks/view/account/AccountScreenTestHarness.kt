package com.bks.view.account

import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.toSignedInSession
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.viewmodel.account.AccountEntryViewModel
import com.bks.viewmodel.account.AccountEntryWechatAction
import com.bks.viewmodel.account.AccountFormState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import com.bks.view.compliance.ComplianceMaterialsScreen
@Composable
@Suppress("LongParameterList")
internal fun AccountScreen(
    modifier: Modifier = Modifier,
    initialState: AccountFormState = AccountFormState(),
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
    val operationState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(wechatAuthCallback) {
        if (wechatAuthCallback != null && viewModel.wechatAvailable) {
            viewModel.onWechatAction(AccountEntryWechatAction.HandleCallback(wechatAuthCallback))
            onWechatAuthCallbackConsumed()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    if (showCompliance) {
        BackHandler { showCompliance = false }
        ComplianceMaterialsScreen(onBack = { showCompliance = false })
    } else {
        AccountScreen(
            state = AccountScreenState(
                operationState = operationState,
                wechatAvailable = viewModel.wechatAvailable
            ),
            actions = AccountScreenActions(
                onSubmit = viewModel::submit,
                onEnterLocalMode = viewModel::enterLocalMode,
                onInvalidateSmsContext = viewModel::invalidateSmsContext,
                onWechatAction = viewModel::onWechatAction,
                onComplianceClick = { showCompliance = true },
                onBack = onBack
            ),
            platform = AccountScreenPlatform(
                snackbarHostState = snackbarHostState,
                avatarCacheOverride = avatarCacheOverride
            ),
            modifier = modifier,
            initialState = initialState
        )
    }
}
