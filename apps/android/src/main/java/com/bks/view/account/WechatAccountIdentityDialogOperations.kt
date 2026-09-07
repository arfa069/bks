package com.bks.view.account

import com.bks.model.domain.account.AccountSession
import com.bks.viewmodel.account.AccountIdentityFormState
internal class WechatAccountIdentityDialogOperations(
    val session: AccountSession.SignedIn,
    private val managementActions: WechatAccountManagementActions,
    val actions: WechatAccountIdentityActions
) {
    fun launchNicknameUpdate(nickname: String) {
        managementActions.onUpdateNickname(nickname)
    }

    fun launchIdentifierSms(state: AccountIdentityFormState) {
        managementActions.onPrepareIdentifierLink(state)
    }

    fun launchIdentifierConfirmation(state: AccountIdentityFormState) {
        managementActions.onConfirmIdentifier(state)
    }

    fun launchPhonePasswordConfirmation(state: AccountIdentityFormState) {
        managementActions.onConfirmPhonePassword(state)
    }

    fun launchMergeConfirmation(state: AccountIdentityFormState) {
        managementActions.onConfirmMerge(state.confirmText)
    }

    fun launchUnlinkSmsRequest(state: AccountIdentityFormState) {
        managementActions.onRequestUnlinkCode(state.unlinkIdentifier)
    }

    fun launchUnlinkConfirmation(state: AccountIdentityFormState) {
        managementActions.onUnlinkWechat(state)
    }
}
