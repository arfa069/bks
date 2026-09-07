package com.bks.view.account

import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountIdentifierType
import com.bks.viewmodel.account.AccountManagementOperationState
import com.bks.viewmodel.account.AccountIdentityPage
import com.bks.viewmodel.account.AccountIdentityFormState
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

internal data class WechatAccountManagementActions(
    val onStartWechatLink: () -> Unit,
    val onBeginAvatarRead: () -> Unit,
    val onCancelAvatarRead: () -> Unit,
    val onAvatarDataReady: (String) -> Unit,
    val onAvatarReadFailure: (String) -> Unit,
    val onIdentityFailure: (String) -> Unit,
    val onOpenIdentityPage: (AccountIdentityPage) -> Unit,
    val onCloseIdentityPage: () -> Unit,
    val onIdentityContextChanged: () -> Unit,
    val onClearIdentityError: () -> Unit,
    val onUpdateNickname: (String) -> Unit,
    val onPrepareIdentifierLink: (AccountIdentityFormState) -> Unit,
    val onConfirmIdentifier: (AccountIdentityFormState) -> Unit,
    val onConfirmPhonePassword: (AccountIdentityFormState) -> Unit,
    val onConfirmMerge: (String) -> Unit,
    val onRequestUnlinkCode: (String) -> Unit,
    val onUnlinkWechat: (AccountIdentityFormState) -> Unit
)

internal data class WechatAccountManagementState(
    val operationState: AccountManagementOperationState,
    val wechatAuthAvailable: Boolean
)

internal data class WechatAccountManagementPlatform(
    val avatarCache: WechatAvatarCache,
    val avatarDataReader: suspend (Context, Uri) -> String
)

private class WechatAccountProfileActionHandler(
    private val session: AccountSession.SignedIn,
    private val actions: WechatAccountManagementActions,
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
            WechatAccountProfileAction.BindWechat -> actions.onStartWechatLink()
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
            actions.onIdentityFailure("最后一种登录方式不可解绑")
        } else {
            open(
                AccountIdentityPage.UnlinkWechat,
                AccountIdentityDraft(unlinkIdentifier = session.phone ?: session.email.orEmpty())
            )
        }
    }

    private fun open(page: AccountIdentityPage, draft: AccountIdentityDraft) {
        onDraftChange(draft)
        actions.onOpenIdentityPage(page)
    }
}

@Composable
internal fun WechatAccountManagementPanel(
    session: AccountSession.SignedIn,
    state: WechatAccountManagementState,
    platform: WechatAccountManagementPlatform,
    actions: WechatAccountManagementActions,
    modifier: Modifier = Modifier
) {
    val operationState = state.operationState
    var draft by remember { mutableStateOf(AccountIdentityDraft()) }
    var showAvatarSourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(operationState.draftResetGeneration) {
        draft = AccountIdentityDraft()
    }
    val identityState = draft.toUiState(operationState)
    val profileActionHandler = remember(session, actions) {
        WechatAccountProfileActionHandler(
            session = session,
            actions = actions,
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
            wechatAuthAvailable = state.wechatAuthAvailable
        ),
        avatarCache = platform.avatarCache,
        onAction = profileActionHandler::handle,
        modifier = modifier
    )

    WechatAccountAvatarEditor(
        avatarDataReader = platform.avatarDataReader,
        state = WechatAvatarEditorState(
            showSourceDialog = showAvatarSourceDialog,
            pendingCameraUri = pendingCameraUri
        ),
        actions = WechatAvatarEditorActions(
            onStateChange = { next ->
                showAvatarSourceDialog = next.showSourceDialog
                pendingCameraUri = next.pendingCameraUri
            },
            onAvatarReadStarted = actions.onBeginAvatarRead,
            onAvatarReadCancelled = actions.onCancelAvatarRead,
            onAvatarDataReady = actions.onAvatarDataReady,
            onAvatarReadFailure = actions.onAvatarReadFailure
        )
    )

    WechatAccountIdentityDialogHost(
        session = session,
        actions = actions,
        state = identityState,
        onStateChange = { next ->
            if (next.page == AccountIdentityPage.Idle) {
                draft = AccountIdentityDraft()
                actions.onCloseIdentityPage()
            } else {
                if (draft.identityContextChanged(next)) {
                    actions.onIdentityContextChanged()
                } else if (next.errorMessage == null && identityState.errorMessage != null) {
                    actions.onClearIdentityError()
                }
                draft = next.toDraft()
            }
        }
    )
}

private fun AccountIdentityDraft.toUiState(
    operationState: AccountManagementOperationState
): AccountIdentityFormState = AccountIdentityFormState(
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

private fun AccountIdentityFormState.toDraft(): AccountIdentityDraft = AccountIdentityDraft(
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

private fun AccountIdentityDraft.identityContextChanged(next: AccountIdentityFormState): Boolean =
    phone != next.phone ||
        identifierAttachMethod != next.identifierAttachMethod ||
        targetIdentifierType != next.targetIdentifierType ||
        replaceExistingIdentifier != next.replaceExistingIdentifier ||
        unlinkIdentifier != next.unlinkIdentifier
