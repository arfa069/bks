package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountSession

data class ProfileAccountSummaryUiModel(val text: String)

internal fun AccountSession.toProfileAccountSummaryUiModel(): ProfileAccountSummaryUiModel =
    ProfileAccountSummaryUiModel(
        text = when (this) {
            AccountSession.LocalMode -> "本地模式 · 账本仅保存在本机"
            is AccountSession.SignedIn -> phone
                ?.let { "已登录 · ${it.maskProfilePhone()}" }
                ?: "已登录 · ${nickname ?: "微信账号"}"
        }
    )

private fun String.maskProfilePhone(): String =
    if (length == 11) replaceRange(3, 7, "****") else "已登录"
