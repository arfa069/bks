package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountIdentifier
import com.bks.model.domain.account.AccountIdentifierType

internal enum class AccountIdentityPage {
    Idle,
    AttachIdentifier,
    SetPhonePassword,
    Merge,
    UnlinkWechat,
    EditNickname
}

internal enum class IdentifierAttachMethod {
    Sms,
    PasswordMerge
}

internal enum class UnlinkMethod {
    Password,
    Code
}

internal data class AccountIdentityFormState(
    val page: AccountIdentityPage = AccountIdentityPage.Idle,
    val identifierAttachMethod: IdentifierAttachMethod = IdentifierAttachMethod.Sms,
    val unlinkMethod: UnlinkMethod = UnlinkMethod.Password,
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val editNicknameInput: String = "",
    val targetIdentifierType: AccountIdentifierType? = null,
    val replaceExistingIdentifier: Boolean = false,
    val phoneTicket: String? = null,
    val mergeTicket: String? = null,
    val sourceIdentifiers: List<AccountIdentifier> = emptyList(),
    val sourceNickname: String? = null,
    val sourceWechatLinked: Boolean = false,
    val unlinkIdentifier: String = "",
    val confirmText: String = "",
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null
)
