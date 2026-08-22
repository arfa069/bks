package com.bks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.AccountSessionNotice
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.rememberBksAppState
import com.bks.ui.theme.BksTheme
import com.bks.ui.visual.AppWallpaper

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
fun BksApp(
    bindings: BksAppBindings = BksAppBindings(),
    overrides: BksAppOverrides = BksAppOverrides()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val appState = rememberBksAppState()
    val applicationContext = LocalContext.current.applicationContext
    val container = remember(applicationContext) { applicationContext.bksAppContainer }
    val dependencies = remember(container, overrides) {
        container.createAppDependencies(overrides)
    }
    val runtime = rememberBksAppRuntimeState()
    val accountSessionCoordinator = dependencies.account.sessionCoordinator
    val accountState by accountSessionCoordinator.state.collectAsStateWithLifecycle()
    val actions = androidx.compose.runtime.remember(dependencies, runtime, appState, coroutineScope) {
        BksAppActions(
            dependencies = dependencies,
            runtime = runtime,
            appState = appState,
            coroutineScope = coroutineScope
        )
    }
    val presentation = rememberBksAppPresentation(runtime)
    val routeContext = BksRouteContext(
        dependencies = dependencies,
        runtime = runtime,
        presentation = presentation,
        bindings = bindings,
        actions = actions,
        appState = appState,
        coroutineScope = coroutineScope
    )

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
    LaunchedEffect(accountState) {
        runtime.accountSession = accountState.session
        runtime.isRestoringAccountSession = accountState.isRestoring
        runtime.accountRuntimeState = accountState.runtimeState
        runtime.accountDeletionState = accountState.deletionState
    }

    BksAppEffects(
        context = BksAppEffectsContext(
            dependencies = dependencies,
            bindings = bindings,
            runtime = runtime,
            actions = actions,
            appState = appState,
            lifecycleOwner = lifecycleOwner,
            presentation = presentation
        )
    )

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
                    BksAccountEntry(routeContext)
                } else {
                    BksRouteHost(routeContext)
                }
            }
        }
    }
    BksLedgerSyncAccountSwitchDialog(routeContext)
}
