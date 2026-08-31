package com.bks.view.account

import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountDeletionState
import com.bks.viewmodel.account.AccountIdentityUiState
import com.bks.viewmodel.account.AccountManagementViewModel
internal class WechatAccountIdentityDialogOperations(
    val session: AccountSession.SignedIn,
    private val deletionState: AccountDeletionState,
    private val viewModel: AccountManagementViewModel,
    val actions: WechatAccountIdentityActions
) {
    fun launchNicknameUpdate(nickname: String) {
        viewModel.updateNickname(session, deletionState, nickname)
    }

    fun launchIdentifierSms(state: AccountIdentityUiState) {
        viewModel.prepareIdentifierLink(session, state)
    }

    fun launchIdentifierConfirmation(state: AccountIdentityUiState) {
        viewModel.confirmIdentifier(session, state)
    }

    fun launchPhonePasswordConfirmation(state: AccountIdentityUiState) {
        viewModel.confirmPhonePassword(session, state)
    }

    fun launchMergeConfirmation(state: AccountIdentityUiState) {
        viewModel.confirmMerge(session, state.confirmText)
    }

    fun launchUnlinkSmsRequest(state: AccountIdentityUiState) {
        viewModel.requestUnlinkCode(session, state.unlinkIdentifier)
    }

    fun launchUnlinkConfirmation(state: AccountIdentityUiState) {
        viewModel.unlinkWechat(session, state)
    }
}
