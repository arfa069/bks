package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountIdentifierParser
import com.bks.model.domain.account.AccountIdentifierType

private fun parseIdentifierOrNull(identifier: String) =
    try {
        AccountIdentifierParser.parse(identifier)
    } catch (_: IllegalArgumentException) {
        null
    }

val AccountUiState.identifierType: AccountIdentifierType
    get() = parseIdentifierOrNull(phone)?.type
        ?: if (phone.contains("@")) {
            AccountIdentifierType.EMAIL
        } else if (phone.all { it.isDigit() }) {
            AccountIdentifierType.PHONE
        } else {
            AccountIdentifierType.USERNAME
        }

val AccountUiState.requiresVerificationCode: Boolean
    get() = identifierType != AccountIdentifierType.USERNAME

internal fun validateIdentifier(identifier: String, flow: AccountFlow = AccountFlow.Landing): String? {
    if (identifier.isBlank()) return "请输入手机号、邮箱或用户名"
    val parseResult = parseIdentifierOrNull(identifier) ?: return "标识格式不正确"
    if (flow == AccountFlow.Recovery && parseResult.type == AccountIdentifierType.USERNAME) {
        return "用户名不支持找回密码，请使用已绑定的手机号或邮箱"
    }
    return null
}

internal fun validatePassword(password: String): String? {
    val valid = password.length in 8..32 &&
        password.any { it.isUpperCase() } &&
        password.any { it.isLowerCase() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() }
    return if (valid) null else "密码需 8-32 位，包含大小写字母、数字和符号"
}
