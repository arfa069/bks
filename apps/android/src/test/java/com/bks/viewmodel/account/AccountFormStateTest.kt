package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountFailureKind
import com.bks.model.domain.account.AccountRepositoryResult
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSessionVerificationDecision
import com.bks.model.domain.account.resolveAccountSessionVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFormStateTest {
    @Test
    fun localModeRequiresAgreementBeforeEntry() {
        val blocked = reduceAccountFormState(AccountFormState(), AccountAction.StartLocalMode)

        assertEquals("请先阅读并同意用户协议和隐私政策", blocked.errorMessage)

        val allowed = reduceAccountFormState(
            blocked.copy(agreementAccepted = true),
            AccountAction.StartLocalMode
        )

        assertEquals(AccountFlow.LocalModeExplanation, allowed.flow)
        assertNull(allowed.errorMessage)
    }

    @Test
    fun invalidRegistrationShowsFieldErrors() {
        val state = AccountFormState(
            flow = AccountFlow.Register,
            agreementAccepted = true,
            phone = "123",
            verificationCode = "",
            password = "weak",
            confirmPassword = "different"
        )

        val result = reduceAccountFormState(state, AccountAction.SubmitRegister)

        assertEquals("标识格式不正确", result.phoneError)
        assertEquals("请输入验证码", result.verificationCodeError)
        assertEquals("密码需 8-32 位，包含大小写字母、数字和符号", result.passwordError)
        assertEquals("两次输入的密码不一致", result.confirmPasswordError)
    }

    @Test
    fun validRegistrationIsReadyForRepositorySubmit() {
        val state = AccountFormState(
            flow = AccountFlow.Register,
            agreementAccepted = true,
            phone = "13800138000",
            verificationCode = "123456",
            password = "Aa123456!",
            confirmPassword = "Aa123456!"
        )

        val result = reduceAccountFormState(state, AccountAction.SubmitRegister)

        assertNull(result.errorMessage)
        assertTrue(result.hasNoFieldErrors)
    }

    @Test
    fun smsRequestOnlyValidatesBeforeRepositorySucceeds() {
        val requested = reduceAccountFormState(
            AccountFormState(phone = "13800138000"),
            AccountAction.RequestSmsCode
        )

        assertEquals(0, requested.smsCountdownSeconds)
        assertNull(requested.phoneError)

    }

    @Test
    fun onlyVerifiedRuntimeAllowsCloudWrites() {
        assertTrue(
            AccountRuntimeState(AccountRuntimeStatus.Verified).cloudWritesAllowed
        )
        assertTrue(
            !AccountRuntimeState(AccountRuntimeStatus.Validating).cloudWritesAllowed
        )
        assertTrue(
            !AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified).cloudWritesAllowed
        )
        assertTrue(
            !AccountRuntimeState(AccountRuntimeStatus.DeletionCoolingOff).cloudWritesAllowed
        )
    }

    @Test
    fun networkFailureKeepsOfflineSessionButInvalidSessionClearsIt() {
        val networkDecision = resolveAccountSessionVerification(
            AccountRepositoryResult.Failure(
                AccountFailureKind.Network,
                "offline"
            )
        )
        val invalidDecision = resolveAccountSessionVerification(
            AccountRepositoryResult.Failure(
                AccountFailureKind.InvalidSession,
                "expired"
            )
        )

        assertEquals(AccountSessionVerificationDecision.KeepOfflineSession, networkDecision)
        assertEquals(AccountSessionVerificationDecision.ClearInvalidSession, invalidDecision)
    }

    @Test
    fun usernameRegistrationHidesVerificationCodeRequirement() {
        val state = AccountFormState(
            flow = AccountFlow.Register,
            agreementAccepted = true,
            phone = "username123",
            verificationCode = "",
            password = "Aa123456!",
            confirmPassword = "Aa123456!"
        )

        val result = reduceAccountFormState(state, AccountAction.SubmitRegister)

        assertNull(result.phoneError)
        assertNull(result.verificationCodeError)
        assertNull(result.passwordError)
        assertNull(result.confirmPasswordError)
        assertTrue(result.hasNoFieldErrors)
    }

    @Test
    fun usernameRecoveryIsBlockedWithFriendlyError() {
        val state = AccountFormState(
            flow = AccountFlow.Recovery,
            phone = "username123"
        )

        val result = reduceAccountFormState(state, AccountAction.SubmitRecovery)

        assertEquals("用户名不支持找回密码，请使用已绑定的手机号或邮箱", result.phoneError)
    }

    @Test
    fun usernameSmsCodeRequestIsBlocked() {
        val state = AccountFormState(
            flow = AccountFlow.Register,
            phone = "username123"
        )

        val result = reduceAccountFormState(state, AccountAction.RequestSmsCode)

        assertEquals("用户名不支持获取验证码，请使用手机号或邮箱", result.phoneError)
    }
}
