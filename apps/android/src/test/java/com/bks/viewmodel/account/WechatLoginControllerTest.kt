package com.bks.viewmodel.account

import com.bks.model.domain.account.TestAccountRepository
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRepositoryResult
import com.bks.model.domain.account.AccountVerificationPurpose
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthCoordinator
import com.bks.model.platform.account.WechatAuthGateway
import com.bks.model.platform.account.WechatAuthLaunchResult
import com.bks.model.platform.account.WechatAuthPurpose
import com.bks.viewmodel.account.WechatBindMethod
import com.bks.viewmodel.account.WechatLoginController
import com.bks.viewmodel.account.WechatLoginPage
import com.bks.viewmodel.account.WechatLoginUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatLoginControllerTest {
    @Test
    fun agreementGateBlocksGatewayAndDuplicateStartIsLocked() {
        val gateway = RecordingWechatGateway()
        val controller = controller(TestAccountRepository(), gateway)

        val blocked = controller.start(WechatLoginUiState(), agreementAccepted = false)

        assertEquals(0, gateway.calls)
        assertEquals("请先阅读并同意用户协议和隐私政策", blocked.errorMessage)

        val started = controller.start(blocked, agreementAccepted = true)
        val duplicate = controller.start(started, agreementAccepted = true)

        assertEquals(1, gateway.calls)
        assertTrue(duplicate.operationInProgress)
        assertEquals(WechatAuthPurpose.SignInOrRegister, gateway.lastPurpose)
    }

    @Test
    fun callbackShowsProfilePreviewAndCreatesWechatAccount() = runTest {
        val repository = TestAccountRepository().apply {
            authenticationResult = AccountRepositoryResult.Success(
                AccountCredentials(
                    phone = null,
                    token = "wechat-token",
                    wechatLinked = true,
                    nickname = "微信小张",
                    avatarUrl = "https://example.com/avatar.jpg"
                )
            )
        }
        var committed: AccountCredentials? = null
        val controller = controller(
            repository = repository,
            commitSession = { credentials -> committed = credentials; true }
        )

        val preview = controller.handleCallback(
            WechatLoginUiState(operationInProgress = true),
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )

        assertEquals(WechatLoginPage.Preview, preview.page)
        assertEquals("微信用户", preview.nickname)
        assertEquals(1, repository.exchangeWechatCalls)

        val completed = controller.createWechatAccount(preview)

        assertEquals(1, repository.registerWithWechatCalls)
        assertNotNull(committed)
        assertEquals("wechat-token", committed?.token)
        assertEquals(WechatLoginPage.Idle, completed.page)
    }

    @Test
    fun passwordAndSmsBindingUseTheirDedicatedRepositoryPaths() = runTest {
        val passwordRepository = TestAccountRepository()
        val passwordController = previewController(passwordRepository)
        val passwordState = passwordController.showBinding(previewState())

        passwordController.bindExistingAccount(passwordState, "13800138000", "Aa123456!", "")

        assertEquals(1, passwordRepository.linkWechatWithPasswordCalls)
        assertEquals(0, passwordRepository.linkWechatWithCodeCalls)

        val smsRepository = TestAccountRepository()
        val smsController = previewController(smsRepository)
        val smsState = smsController.selectBindMethod(
            smsController.showBinding(previewState()),
            WechatBindMethod.Sms
        )

        val requested = smsController.requestBindingSms(smsState, "13800138000")

        assertEquals(1, smsRepository.smsCalls)
        assertEquals(AccountVerificationPurpose.WechatLink, smsRepository.lastSmsPurpose)
        assertEquals("ticket", smsRepository.lastSmsContextKey)
        assertTrue(requested.smsRequested)

        smsController.bindExistingAccount(requested, "13800138000", "", "123456")

        assertEquals(1, smsRepository.linkWechatWithCodeCalls)
        assertEquals(0, smsRepository.linkWechatWithPasswordCalls)
    }

    @Test
    fun persistenceFailureClearsLocalSessionAndDoesNotCommit() = runTest {
        val repository = TestAccountRepository().apply {
            authenticationResult = AccountRepositoryResult.Success(
                AccountCredentials(null, "new-token", wechatLinked = true)
            )
        }
        var cleared = false
        var commitCalls = 0
        val controller = controller(
            repository = repository,
            commitSession = { commitCalls += 1; false },
            clearPersistedSession = { cleared = true; true }
        )
        val preview = controller.handleCallback(
            WechatLoginUiState(operationInProgress = true),
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )

        val failed = controller.createWechatAccount(preview)

        assertTrue(cleared)
        assertEquals(1, commitCalls)
        assertEquals(1, repository.signOutCalls)
        assertFalse(failed.operationInProgress)
        assertTrue(failed.errorMessage?.contains("无法安全保存登录状态") == true)
    }

    private suspend fun previewController(repository: TestAccountRepository): WechatLoginController {
        val controller = controller(repository)
        controller.handleCallback(
            WechatLoginUiState(operationInProgress = true),
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )
        return controller
    }

    private fun previewState() = WechatLoginUiState(
        page = WechatLoginPage.Preview,
        wechatTicket = "ticket"
    )

    private fun controller(
        repository: TestAccountRepository,
        gateway: WechatAuthGateway = RecordingWechatGateway(),
        commitSession: (AccountCredentials) -> Boolean = { true },
        clearPersistedSession: () -> Boolean = { true }
    ): WechatLoginController = WechatLoginController(
        accountRepository = repository,
        authCoordinator = WechatAuthCoordinator(gateway),
        commitSession = commitSession,
        clearPersistedSession = clearPersistedSession
    )

    private class RecordingWechatGateway : WechatAuthGateway {
        var calls = 0
        var lastPurpose: WechatAuthPurpose? = null

        override fun startAuthorization(
            purpose: WechatAuthPurpose,
            sessionFingerprint: String?
        ): WechatAuthLaunchResult {
            calls += 1
            lastPurpose = purpose
            return WechatAuthLaunchResult.Started
        }
    }
}
