package com.bks.view.account

import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountIdentifierType
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthPurpose
import com.bks.model.platform.account.managementPurpose
import com.bks.model.domain.account.AccountDeletionState
import com.bks.viewmodel.account.AccountManagementOperationState
import com.bks.viewmodel.account.AccountManagementViewModel
import com.bks.viewmodel.account.AccountIdentityPage
import com.bks.viewmodel.account.AccountIdentityUiState
import com.bks.viewmodel.account.IdentifierAttachMethod
import com.bks.viewmodel.account.UnlinkMethod
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private data class AccountIdentityDraft(
    val identifierAttachMethod: IdentifierAttachMethod = IdentifierAttachMethod.Sms,
    val unlinkMethod: UnlinkMethod = UnlinkMethod.Password,
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val editNicknameInput: String = "",
    val targetIdentifierType: AccountIdentifierType? = null,
    val replaceExistingIdentifier: Boolean = false,
    val unlinkIdentifier: String = "",
    val confirmText: String = ""
)

internal data class WechatAccountManagementDependencies(
    val viewModel: AccountManagementViewModel,
    val wechatAuthCallback: WechatAuthCallback?,
    val avatarCache: WechatAvatarCache,
    val onWechatAuthCallbackConsumed: () -> Unit,
    val avatarDataReader: suspend (Context, Uri) -> String
)

private class WechatAccountProfileActionHandler(
    private val session: AccountSession.SignedIn,
    private val viewModel: AccountManagementViewModel,
    private val onDraftChange: (AccountIdentityDraft) -> Unit,
    private val onAvatarEditorRequested: () -> Unit
) {
    fun handle(action: WechatAccountProfileAction) {
        when (action) {
            WechatAccountProfileAction.EditAvatar -> onAvatarEditorRequested()
            WechatAccountProfileAction.EditNickname -> open(
                AccountIdentityPage.EditNickname,
                AccountIdentityDraft(editNicknameInput = session.nickname.orEmpty())
            )
            WechatAccountProfileAction.ReplacePhone -> openIdentifier(AccountIdentifierType.PHONE, true)
            WechatAccountProfileAction.BindPhone -> openIdentifier(AccountIdentifierType.PHONE, false)
            WechatAccountProfileAction.ReplaceEmail -> openIdentifier(AccountIdentifierType.EMAIL, true)
            WechatAccountProfileAction.BindEmail -> openIdentifier(AccountIdentifierType.EMAIL, false)
            WechatAccountProfileAction.UnlinkWechat -> openWechatUnlink()
            WechatAccountProfileAction.BindWechat -> viewModel.startWechatLink(session)
        }
    }

    private fun openIdentifier(type: AccountIdentifierType, replaceExisting: Boolean) {
        open(
            AccountIdentityPage.AttachIdentifier,
            AccountIdentityDraft(
                targetIdentifierType = type,
                replaceExistingIdentifier = replaceExisting
            )
        )
    }

    private fun openWechatUnlink() {
        if (session.phone == null && session.email == null && session.username == null) {
            viewModel.reportIdentityFailure("最后一种登录方式不可解绑")
        } else {
            open(
                AccountIdentityPage.UnlinkWechat,
                AccountIdentityDraft(unlinkIdentifier = session.phone ?: session.email.orEmpty())
            )
        }
    }

    private fun open(page: AccountIdentityPage, draft: AccountIdentityDraft) {
        onDraftChange(draft)
        viewModel.openIdentityPage(page)
    }
}

@Composable
internal fun WechatAccountManagementPanel(
    session: AccountSession.SignedIn,
    deletionState: AccountDeletionState,
    operationState: AccountManagementOperationState,
    dependencies: WechatAccountManagementDependencies,
    modifier: Modifier = Modifier
) {
    val viewModel = dependencies.viewModel
    var draft by remember { mutableStateOf(AccountIdentityDraft()) }
    var showAvatarSourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(operationState.draftResetGeneration) {
        draft = AccountIdentityDraft()
    }
    LaunchedEffect(dependencies.wechatAuthCallback, session.token) {
        val callback = dependencies.wechatAuthCallback ?: return@LaunchedEffect
        if (callback.managementPurpose() != WechatAuthPurpose.LinkCurrentAccount) return@LaunchedEffect
        dependencies.onWechatAuthCallbackConsumed()
        viewModel.handleWechatCallback(session, callback)
    }

    val identityState = draft.toUiState(operationState)
    val profileActionHandler = remember(session, viewModel) {
        WechatAccountProfileActionHandler(
            session = session,
            viewModel = viewModel,
            onDraftChange = { draft = it },
            onAvatarEditorRequested = { showAvatarSourceDialog = true }
        )
    }

    WechatAccountProfileContent(
        session = session,
        uiState = WechatAccountProfileUiState(
            identityState = identityState,
            displayedAvatarUrl = session.avatarUrl,
            avatarError = operationState.avatarError,
            wechatAuthAvailable = viewModel.wechatAvailable
        ),
        avatarCache = dependencies.avatarCache,
        onAction = profileActionHandler::handle,
        modifier = modifier
    )

    WechatAccountAvatarEditor(
        avatarDataReader = dependencies.avatarDataReader,
        state = WechatAvatarEditorState(
            showSourceDialog = showAvatarSourceDialog,
            pendingCameraUri = pendingCameraUri
        ),
        actions = WechatAvatarEditorActions(
            onStateChange = { next ->
                showAvatarSourceDialog = next.showSourceDialog
                pendingCameraUri = next.pendingCameraUri
            },
            onAvatarReadStarted = viewModel::beginAvatarRead,
            onAvatarReadCancelled = viewModel::cancelAvatarRead,
            onAvatarDataReady = { dataUrl -> viewModel.updateAvatar(session, deletionState, dataUrl) },
            onAvatarReadFailure = viewModel::reportAvatarReadFailure
        )
    )

    WechatAccountIdentityDialogHost(
        session = session,
        deletionState = deletionState,
        viewModel = viewModel,
        state = identityState,
        onStateChange = { next ->
            if (next.page == AccountIdentityPage.Idle) {
                draft = AccountIdentityDraft()
                viewModel.closeIdentityPage()
            } else {
                if (draft.identityContextChanged(next)) {
                    viewModel.identityContextChanged()
                } else if (next.errorMessage == null && identityState.errorMessage != null) {
                    viewModel.clearIdentityError()
                }
                draft = next.toDraft()
            }
        }
    )
}

private fun AccountIdentityDraft.toUiState(
    operationState: AccountManagementOperationState
): AccountIdentityUiState = AccountIdentityUiState(
    page = operationState.identityPage,
    identifierAttachMethod = identifierAttachMethod,
    unlinkMethod = unlinkMethod,
    phone = phone,
    code = code,
    password = password,
    editNicknameInput = editNicknameInput,
    targetIdentifierType = targetIdentifierType,
    replaceExistingIdentifier = replaceExistingIdentifier,
    phoneTicket = operationState.phoneTicket,
    mergeTicket = operationState.mergeTicket,
    sourceIdentifiers = operationState.sourceIdentifiers,
    sourceNickname = operationState.sourceNickname,
    sourceWechatLinked = operationState.sourceWechatLinked,
    unlinkIdentifier = unlinkIdentifier,
    confirmText = confirmText,
    operationInProgress = operationState.accountOperationInProgress ||
        operationState.identityOperationInProgress,
    errorMessage = operationState.identityError
)

private fun AccountIdentityUiState.toDraft(): AccountIdentityDraft = AccountIdentityDraft(
    identifierAttachMethod = identifierAttachMethod,
    unlinkMethod = unlinkMethod,
    phone = phone,
    code = code,
    password = password,
    editNicknameInput = editNicknameInput,
    targetIdentifierType = targetIdentifierType,
    replaceExistingIdentifier = replaceExistingIdentifier,
    unlinkIdentifier = unlinkIdentifier,
    confirmText = confirmText
)

private fun AccountIdentityDraft.identityContextChanged(next: AccountIdentityUiState): Boolean =
    phone != next.phone ||
        identifierAttachMethod != next.identifierAttachMethod ||
        targetIdentifierType != next.targetIdentifierType ||
        replaceExistingIdentifier != next.replaceExistingIdentifier ||
        unlinkIdentifier != next.unlinkIdentifier
