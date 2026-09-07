package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountIdentifier
import com.bks.model.domain.account.AccountIdentifierParser
import com.bks.model.domain.account.AccountIdentifierType

internal fun AccountManagementOperationState.openIdentityPage(
    page: AccountIdentityPage
): AccountManagementOperationState = copy(
    identityPage = page,
    identityError = null,
    phoneTicket = null,
    mergeTicket = null,
    sourceIdentifiers = emptyList(),
    sourceNickname = null,
    sourceWechatLinked = false
)

internal fun AccountManagementOperationState.showIdentifierLinkTicket(
    ticket: String
): AccountManagementOperationState = copy(
    identityOperationInProgress = false,
    identityError = null,
    phoneTicket = ticket
)

internal fun AccountManagementOperationState.showIdentityMerge(
    mergeTicket: String,
    sourceIdentifiers: List<AccountIdentifier>,
    sourceNickname: String?,
    sourceWechatLinked: Boolean
): AccountManagementOperationState = copy(
    identityOperationInProgress = false,
    identityError = null,
    identityPage = AccountIdentityPage.Merge,
    mergeTicket = mergeTicket,
    sourceIdentifiers = sourceIdentifiers,
    sourceNickname = sourceNickname,
    sourceWechatLinked = sourceWechatLinked
)

internal fun AccountManagementOperationState.showPhonePasswordPage(): AccountManagementOperationState = copy(
    identityPage = AccountIdentityPage.SetPhonePassword,
    identityOperationInProgress = false,
    identityError = null
)

internal fun AccountManagementOperationState.resetIdentity(): AccountManagementOperationState = copy(
    identityOperationInProgress = false,
    identityError = null,
    identityPage = AccountIdentityPage.Idle,
    phoneTicket = null,
    mergeTicket = null,
    sourceIdentifiers = emptyList(),
    sourceNickname = null,
    sourceWechatLinked = false,
    avatarError = null,
    draftResetGeneration = draftResetGeneration + 1
)

internal fun AccountIdentityFormState.hasValidIdentifier(): Boolean {
    val parsedType = runCatching { AccountIdentifierParser.parse(phone).type }.getOrNull() ?: return false
    if (parsedType == AccountIdentifierType.USERNAME) return false
    return targetIdentifierType?.let { it == parsedType } ?: true
}

internal fun AccountIdentityFormState.expectedIdentifierError(): String = when (targetIdentifierType) {
    AccountIdentifierType.PHONE -> "请输入有效的手机号"
    AccountIdentifierType.EMAIL -> "请输入有效的邮箱"
    else -> "请输入有效的手机号或邮箱"
}
