package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileAccountSummaryUiModelTest {
    @Test
    fun localModeSummaryKeepsLocalStorageMessage() {
        assertEquals(
            "本地模式 · 账本仅保存在本机",
            AccountSession.LocalMode.toProfileAccountSummaryUiModel().text
        )
    }

    @Test
    fun signedInSummaryMasksPhoneNumber() {
        val session = AccountSession.SignedIn(
            token = "token",
            phone = "13800138000"
        )

        assertEquals("已登录 · 138****8000", session.toProfileAccountSummaryUiModel().text)
    }
}
