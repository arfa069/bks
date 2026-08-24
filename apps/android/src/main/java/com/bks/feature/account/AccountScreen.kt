package com.bks.feature.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bks.ui.components.SlidePageTransition

internal data class AccountScreenDependencies(
    val viewModel: AccountEntryViewModel,
    val wechatAuthCallback: WechatAuthCallback? = null,
    val onWechatAuthCallbackConsumed: () -> Unit = {},
    val avatarCacheOverride: WechatAvatarCache? = null
)

internal data class WechatLoginActions(
    val onBack: () -> Unit,
    val onShowBinding: () -> Unit,
    val onSelectBindMethod: (WechatBindMethod) -> Unit,
    val onCreateAccount: () -> Unit,
    val onRequestBindingSms: (String) -> Unit,
    val onBindExistingAccount: (String, String, String) -> Unit
)

internal data class WechatLoginPageArgs(
    val state: WechatLoginUiState,
    val actions: WechatLoginActions
)

@Composable
internal fun AccountScreen(
    dependencies: AccountScreenDependencies,
    modifier: Modifier = Modifier,
    initialState: AccountUiState = AccountUiState(),
    onComplianceClick: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val viewModel = dependencies.viewModel
    var formState by remember { mutableStateOf(initialState) }
    val operationState by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val avatarCache = dependencies.avatarCacheOverride ?: rememberWechatAvatarCache()
    val renderedState = formState.copy(
        operationInProgress = operationState.operationInProgress,
        smsCountdownSeconds = operationState.smsCountdownSeconds
    )

    fun dispatch(action: AccountAction) {
        val validated = reduceAccountState(formState, action)
        formState = validated
        when {
            action == AccountAction.ConfirmLocalMode -> viewModel.enterLocalMode()
            action is AccountAction.UpdatePhone || action.changesSmsContext() -> viewModel.invalidateSmsContext()
            action.isNetworkAction() -> viewModel.submit(action, validated)
        }
    }

    AccountBackHandler(
        wechatState = operationState.wechat,
        onWechatBack = { viewModel.onWechatAction(AccountEntryWechatAction.Back) },
        flow = formState.flow,
        onFlowBack = { flow ->
            dispatch(if (flow == AccountFlow.Recovery) AccountAction.ShowLogin else AccountAction.BackToLanding)
        },
        onBack = onBack
    )

    LaunchedEffect(formState.flow) { scrollState.scrollTo(0) }

    LaunchedEffect(dependencies.wechatAuthCallback) {
        if (dependencies.wechatAuthCallback != null && viewModel.wechatAvailable) {
            viewModel.onWechatAction(
                AccountEntryWechatAction.HandleCallback(dependencies.wechatAuthCallback)
            )
            dependencies.onWechatAuthCallbackConsumed()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(formState.errorMessage) {
        formState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    SlidePageTransition(
        targetState = currentAccountPage(
            wechatState = operationState.wechat,
            flow = formState.flow
        ),
        modifier = modifier.fillMaxSize()
    ) { targetPage ->
        when (targetPage) {
            AccountPage.Wechat -> WechatLoginFlowPage(
                args = WechatLoginPageArgs(
                    state = operationState.wechat,
                    actions = WechatLoginActions(
                        onBack = { viewModel.onWechatAction(AccountEntryWechatAction.Back) },
                        onShowBinding = { viewModel.onWechatAction(AccountEntryWechatAction.ShowBinding) },
                        onSelectBindMethod = {
                            viewModel.onWechatAction(AccountEntryWechatAction.SelectBindMethod(it))
                        },
                        onCreateAccount = { viewModel.onWechatAction(AccountEntryWechatAction.CreateAccount) },
                        onRequestBindingSms = {
                            viewModel.onWechatAction(AccountEntryWechatAction.RequestBindingSms(it))
                        },
                        onBindExistingAccount = { identifier, password, code ->
                            viewModel.onWechatAction(
                                AccountEntryWechatAction.BindExistingAccount(identifier, password, code)
                            )
                        }
                    )
                ),
                avatarCache = avatarCache,
                snackbarHostState = snackbarHostState,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize()
            )

            is AccountPage.Flow -> AccountFlowPageContent(
                args = AccountFlowPageArgs(
                    state = renderedState,
                    onAction = ::dispatch,
                    wechatEnabled = viewModel.wechatAvailable,
                    onWechatClick = {
                        viewModel.onWechatAction(AccountEntryWechatAction.Start(formState.agreementAccepted))
                    },
                    onComplianceClick = onComplianceClick
                ),
                snackbarHostState = snackbarHostState,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun AccountAction.changesSmsContext(): Boolean = when (this) {
    AccountAction.ShowLogin,
    AccountAction.ShowRegister,
    AccountAction.ShowRecovery,
    AccountAction.BackToLanding,
    AccountAction.StartLocalMode -> true

    else -> false
}

@Composable
private fun AccountBackHandler(
    wechatState: WechatLoginUiState,
    onWechatBack: () -> Unit,
    flow: AccountFlow,
    onFlowBack: (AccountFlow) -> Unit,
    onBack: (() -> Unit)?
) {
    val wechatPageActive = wechatState.page != WechatLoginPage.Idle
    BackHandler(enabled = wechatPageActive || flow != AccountFlow.Landing || onBack != null) {
        when {
            wechatPageActive -> onWechatBack()
            flow != AccountFlow.Landing -> onFlowBack(flow)
            else -> onBack?.invoke()
        }
    }
}
