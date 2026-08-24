package com.bks.feature.account

internal class WechatAccountIdentityDialogOperations(
    val session: AccountSession.SignedIn,
    private val deletionState: AccountDeletionUiState,
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
