package com.bks.view.account

import com.bks.model.domain.account.AccountDeletionState
import com.bks.model.application.account.AccountSessionMutationResult
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthPurpose
import com.bks.viewmodel.account.AccountManagementViewModel
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier

@Composable
@Suppress("LongParameterList")
internal fun TestAccountManagementScreen(
    session: AccountSession,
    runtimeState: AccountRuntimeState,
    deletionState: AccountDeletionState,
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
    onDeletionStateChange: (AccountDeletionState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarCache = avatarCacheOverride ?: rememberWechatAvatarCache()
    val viewModel = remember(accountRepository, wechatAuthGateway, avatarCache) {
        createTestAccountManagementViewModel(
            accountRepository = accountRepository,
            callbacks = TestAccountManagementCallbacks(
                persistSession = persistSession,
                clearPersistedSession = clearPersistedSession,
                onSessionVerified = onSessionVerified,
                onSignedOut = onSignedOut,
                onInvalidSession = onInvalidSession,
                onDeletionStateChange = onDeletionStateChange
            ),
            avatarCache = avatarCache,
            wechatAuthGateway = wechatAuthGateway
        )
    }
    val operationState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        viewModel.activate(session)
    }
    LaunchedEffect(wechatAuthCallback, session) {
        val signedIn = session as? AccountSession.SignedIn ?: return@LaunchedEffect
        val callback = wechatAuthCallback ?: return@LaunchedEffect
        if (callback.purpose != WechatAuthPurpose.LinkCurrentAccount) return@LaunchedEffect
        onWechatAuthCallbackConsumed()
        viewModel.handleWechatCallback(signedIn, callback)
    }
    val signedIn = session as? AccountSession.SignedIn
    AccountManagementScreen(
        screenState = AccountManagementScreenState(
            session = session,
            runtimeState = runtimeState,
            deletionState = deletionState,
            operationState = operationState,
            wechatAvailable = viewModel.wechatAvailable
        ),
        platform = AccountManagementScreenPlatform(
            avatarCacheOverride = avatarCache,
            avatarDataReader = avatarDataReader
        ),
        actions = testAccountManagementActions(
            viewModel = viewModel,
            session = signedIn,
            deletionState = deletionState,
            onSignInOrRegister = onSignInOrRegister,
            onBack = onBack
        ),
        modifier = modifier
    )
}

private data class TestAccountManagementCallbacks(
    val persistSession: (AccountCredentials) -> Boolean,
    val clearPersistedSession: () -> Boolean,
    val onSessionVerified: (AccountCredentials) -> Unit,
    val onSignedOut: () -> Unit,
    val onInvalidSession: () -> Unit,
    val onDeletionStateChange: (AccountDeletionState) -> Unit
)

private fun createTestAccountManagementViewModel(
    accountRepository: AccountRepository,
    callbacks: TestAccountManagementCallbacks,
    avatarCache: WechatAvatarCache,
    wechatAuthGateway: WechatAuthGateway?
) = AccountManagementViewModel(
    accountRepository = accountRepository,
    commitManagementCredentials = { _, credentials ->
        commitTestCredentials(credentials, callbacks, avatarCache)
    },
    commitVerifiedCredentials = { _, credentials ->
        commitTestCredentials(credentials, callbacks, avatarCache)
    },
    invalidateSession = {
        callbacks.clearPersistedSession().also { cleared ->
            if (cleared) {
                avatarCache.clear()
                callbacks.onSignedOut()
                callbacks.onInvalidSession()
            }
        }
    },
    updateDeletionState = { _, state ->
        callbacks.onDeletionStateChange(state)
        AccountSessionMutationResult.Applied
    },
    wechatAuthGateway = wechatAuthGateway
)

private fun commitTestCredentials(
    credentials: AccountCredentials,
    callbacks: TestAccountManagementCallbacks,
    avatarCache: WechatAvatarCache
): AccountSessionMutationResult = if (callbacks.persistSession(credentials)) {
    callbacks.onSessionVerified(credentials)
    avatarCache.prepareUrl(credentials.avatarUrl)
    AccountSessionMutationResult.Applied
} else {
    AccountSessionMutationResult.PersistenceFailed
}

private fun testAccountManagementActions(
    viewModel: AccountManagementViewModel,
    session: AccountSession.SignedIn?,
    deletionState: AccountDeletionState,
    onSignInOrRegister: () -> Unit,
    onBack: () -> Unit
): AccountManagementScreenActions {
    if (session == null) {
        return AccountManagementScreenActions(
            onSignInOrRegister = onSignInOrRegister,
            onBack = onBack,
            onRetryVerification = {},
            onSignOut = {},
            onRequestDeletion = {},
            onCancelDeletion = {},
            wechat = noOpWechatAccountManagementActions()
        )
    }
    return AccountManagementScreenActions(
        onSignInOrRegister = onSignInOrRegister,
        onBack = onBack,
        onRetryVerification = { viewModel.retryVerification(session, deletionState) },
        onSignOut = { viewModel.signOut(session) },
        onRequestDeletion = { viewModel.requestDeletion(session) },
        onCancelDeletion = { viewModel.cancelDeletion(session) },
        wechat = WechatAccountManagementActions(
            onStartWechatLink = { viewModel.startWechatLink(session) },
            onBeginAvatarRead = viewModel::beginAvatarRead,
            onCancelAvatarRead = viewModel::cancelAvatarRead,
            onAvatarDataReady = { viewModel.updateAvatar(session, deletionState, it) },
            onAvatarReadFailure = viewModel::reportAvatarReadFailure,
            onIdentityFailure = viewModel::reportIdentityFailure,
            onOpenIdentityPage = viewModel::openIdentityPage,
            onCloseIdentityPage = viewModel::closeIdentityPage,
            onIdentityContextChanged = { viewModel.identityContextChanged() },
            onClearIdentityError = viewModel::clearIdentityError,
            onUpdateNickname = { viewModel.updateNickname(session, deletionState, it) },
            onPrepareIdentifierLink = { viewModel.prepareIdentifierLink(session, it) },
            onConfirmIdentifier = { viewModel.confirmIdentifier(session, it) },
            onConfirmPhonePassword = { viewModel.confirmPhonePassword(session, it) },
            onConfirmMerge = { viewModel.confirmMerge(session, it) },
            onRequestUnlinkCode = { viewModel.requestUnlinkCode(session, it) },
            onUnlinkWechat = { viewModel.unlinkWechat(session, it) }
        )
    )
}

private fun noOpWechatAccountManagementActions() = WechatAccountManagementActions(
    onStartWechatLink = {},
    onBeginAvatarRead = {},
    onCancelAvatarRead = {},
    onAvatarDataReady = {},
    onAvatarReadFailure = {},
    onIdentityFailure = {},
    onOpenIdentityPage = {},
    onCloseIdentityPage = {},
    onIdentityContextChanged = {},
    onClearIdentityError = {},
    onUpdateNickname = {},
    onPrepareIdentifierLink = {},
    onConfirmIdentifier = {},
    onConfirmPhonePassword = {},
    onConfirmMerge = {},
    onRequestUnlinkCode = {},
    onUnlinkWechat = {}
)
