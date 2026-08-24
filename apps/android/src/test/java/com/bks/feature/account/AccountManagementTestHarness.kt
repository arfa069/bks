package com.bks.feature.account

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
@Suppress("LongParameterList")
internal fun TestAccountManagementScreen(
    session: AccountSession,
    runtimeState: AccountRuntimeState,
    deletionState: AccountDeletionUiState,
    accountRepository: AccountRepository,
    onSignInOrRegister: () -> Unit,
    onSessionVerified: (AccountCredentials) -> Unit,
    onInvalidSession: () -> Unit,
    persistSession: (AccountCredentials) -> Boolean = { true },
    clearPersistedSession: () -> Boolean,
    wechatAuthGateway: WechatAuthGateway? = null,
    wechatAuthCallback: WechatAuthCallback? = null,
    onWechatAuthCallbackConsumed: () -> Unit = {},
    avatarCacheOverride: WechatAvatarCache? = null,
    avatarDataReader: suspend (Context, Uri) -> String = { context, uri ->
        context.readCompressedAvatarDataUrl(uri)
    },
    onSignedOut: () -> Unit,
    onDeletionStateChange: (AccountDeletionUiState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarCache = avatarCacheOverride ?: rememberWechatAvatarCache()
    val viewModel = remember(accountRepository, wechatAuthGateway, avatarCache) {
        AccountManagementViewModel(
            accountRepository = accountRepository,
            commitManagementCredentials = { _, credentials ->
                if (persistSession(credentials)) {
                    onSessionVerified(credentials)
                    avatarCache.prepareUrl(credentials.avatarUrl)
                    AccountSessionMutationResult.Applied
                } else {
                    AccountSessionMutationResult.PersistenceFailed
                }
            },
            commitVerifiedCredentials = { _, credentials ->
                if (persistSession(credentials)) {
                    onSessionVerified(credentials)
                    avatarCache.prepareUrl(credentials.avatarUrl)
                    AccountSessionMutationResult.Applied
                } else {
                    AccountSessionMutationResult.PersistenceFailed
                }
            },
            invalidateSession = {
                clearPersistedSession().also { cleared ->
                    if (cleared) {
                        avatarCache.clear()
                        onSignedOut()
                        onInvalidSession()
                    }
                }
            },
            updateDeletionState = { _, state ->
                onDeletionStateChange(state)
                AccountSessionMutationResult.Applied
            },
            wechatAuthGateway = wechatAuthGateway
        )
    }
    AccountManagementScreen(
        screenState = AccountManagementScreenState(session, runtimeState, deletionState),
        dependencies = AccountManagementScreenDependencies(
            viewModel = viewModel,
            wechatAuthCallback = wechatAuthCallback,
            onWechatAuthCallbackConsumed = onWechatAuthCallbackConsumed,
            avatarCacheOverride = avatarCache,
            avatarDataReader = avatarDataReader
        ),
        actions = AccountManagementScreenActions(
            onSignInOrRegister = onSignInOrRegister,
            onBack = onBack
        ),
        modifier = modifier
    )
}
