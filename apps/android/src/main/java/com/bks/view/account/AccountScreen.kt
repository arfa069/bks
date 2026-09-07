package com.bks.view.account

import com.bks.viewmodel.account.isNetworkAction
import com.bks.viewmodel.account.WechatBindMethod
import com.bks.viewmodel.account.WechatLoginPage
import com.bks.viewmodel.account.WechatLoginUiState
import com.bks.viewmodel.account.AccountFormState
import com.bks.viewmodel.account.AccountAction
import com.bks.viewmodel.account.AccountEntryOperationState
import com.bks.viewmodel.account.AccountEntryWechatAction
import com.bks.viewmodel.account.AccountFlow
import com.bks.viewmodel.account.reduceAccountFormState
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
import com.bks.view.common.components.SlidePageTransition

internal data class AccountScreenState(
    val operationState: AccountEntryOperationState,
    val wechatAvailable: Boolean
)

internal data class AccountScreenPlatform(
    val snackbarHostState: SnackbarHostState,
    val avatarCacheOverride: WechatAvatarCache? = null
)

internal data class AccountScreenActions(
    val onSubmit: (AccountAction, AccountFormState) -> Unit,
    val onEnterLocalMode: () -> Unit,
    val onInvalidateSmsContext: () -> Unit,
    val onWechatAction: (AccountEntryWechatAction) -> Unit,
    val onComplianceClick: () -> Unit,
    val onBack: (() -> Unit)?
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
    state: AccountScreenState,
    actions: AccountScreenActions,
    platform: AccountScreenPlatform,
    modifier: Modifier = Modifier,
    initialState: AccountFormState = AccountFormState()
) {
    var formState by remember { mutableStateOf(initialState) }
    val operationState = state.operationState
    val scrollState = rememberScrollState()
    val avatarCache = platform.avatarCacheOverride ?: rememberWechatAvatarCache()
    val renderedState = formState.copy(
        operationInProgress = operationState.operationInProgress,
        smsCountdownSeconds = operationState.smsCountdownSeconds
    )

    fun dispatch(action: AccountAction) {
        val validated = reduceAccountFormState(formState, action)
        formState = validated
        when {
            action == AccountAction.ConfirmLocalMode -> actions.onEnterLocalMode()
            action is AccountAction.UpdatePhone || action.changesSmsContext() -> actions.onInvalidateSmsContext()
            action.isNetworkAction() -> actions.onSubmit(action, validated)
        }
    }

    AccountBackHandler(
        wechatState = operationState.wechat,
        onWechatBack = { actions.onWechatAction(AccountEntryWechatAction.Back) },
        flow = formState.flow,
        onFlowBack = { flow ->
            dispatch(if (flow == AccountFlow.Recovery) AccountAction.ShowLogin else AccountAction.BackToLanding)
        },
        onBack = actions.onBack
    )

    LaunchedEffect(formState.flow) { scrollState.scrollTo(0) }

    LaunchedEffect(formState.errorMessage) {
        formState.errorMessage?.let { platform.snackbarHostState.showSnackbar(it) }
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
                        onBack = { actions.onWechatAction(AccountEntryWechatAction.Back) },
                        onShowBinding = { actions.onWechatAction(AccountEntryWechatAction.ShowBinding) },
                        onSelectBindMethod = {
                            actions.onWechatAction(AccountEntryWechatAction.SelectBindMethod(it))
                        },
                        onCreateAccount = { actions.onWechatAction(AccountEntryWechatAction.CreateAccount) },
                        onRequestBindingSms = {
                            actions.onWechatAction(AccountEntryWechatAction.RequestBindingSms(it))
                        },
                        onBindExistingAccount = { identifier, password, code ->
                            actions.onWechatAction(
                                AccountEntryWechatAction.BindExistingAccount(identifier, password, code)
                            )
                        }
                    )
                ),
                avatarCache = avatarCache,
                snackbarHostState = platform.snackbarHostState,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize()
            )

            is AccountPage.Flow -> AccountFlowPageContent(
                args = AccountFlowPageArgs(
                    state = renderedState,
                    onAction = ::dispatch,
                    wechatEnabled = state.wechatAvailable,
                    onWechatClick = {
                        actions.onWechatAction(AccountEntryWechatAction.Start(formState.agreementAccepted))
                    },
                    onComplianceClick = actions.onComplianceClick
                ),
                snackbarHostState = platform.snackbarHostState,
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
