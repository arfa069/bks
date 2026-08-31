package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountFailureKind
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRepositoryResult
import com.bks.model.domain.account.AccountWechatAuthResult
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthGateway
import com.bks.model.platform.account.WechatAuthLaunchResult
import com.bks.model.platform.account.WechatAuthPurpose
import com.bks.model.domain.account.TestAccountRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountEntryViewModelTest {
    @Test
    fun loginSuccessCommitsOnlyThroughSessionBoundaryAndClearsOperationState() = runTest {
        withViewModel { viewModel, repository, commits, _ ->
            val form = validLogin(password = "private-password")

            viewModel.submit(AccountAction.SubmitLogin, form)
            advanceUntilIdle()

            assertEquals(1, repository.authenticationCalls)
            assertEquals(listOf("token-1"), commits)
            assertEquals(AccountEntryOperationState(), viewModel.state.value)
            assertFalse(viewModel.state.value.toString().contains("private-password"))
        }
    }

    @Test
    fun repositoryFailureEmitsNoticeAndNeverCommitsSession() = runTest {
        withViewModel(
            configureRepository = {
                authenticationResult = AccountRepositoryResult.Failure(
                    AccountFailureKind.Network,
                    "网络连接失败，请检查网络后重试"
                )
            }
        ) { viewModel, _, commits, _ ->
            viewModel.submit(AccountAction.SubmitLogin, validLogin())
            advanceUntilIdle()

            assertTrue(commits.isEmpty())
            assertFalse(viewModel.state.value.operationInProgress)
            assertEquals("网络连接失败，请检查网络后重试", viewModel.notices.first())
        }
    }

    @Test
    fun registerAndRecoverySuccessUseTheSameSessionCommitBoundary() = runTest {
        withViewModel { viewModel, repository, commits, _ ->
            val register = AccountUiState(
                flow = AccountFlow.Register,
                phone = "13800138000",
                verificationCode = "123456",
                password = "Aa123456!",
                confirmPassword = "Aa123456!",
                agreementAccepted = true
            )
            viewModel.submit(AccountAction.SubmitRegister, register)
            advanceUntilIdle()

            val recovery = register.copy(flow = AccountFlow.Recovery, agreementAccepted = false)
            viewModel.submit(AccountAction.SubmitRecovery, recovery)
            advanceUntilIdle()

            assertEquals(2, repository.authenticationCalls)
            assertEquals(listOf("token-1", "token-1"), commits)
        }
    }

    @Test
    fun duplicateAuthenticationSubmitIsIgnoredWhileFirstRequestIsPending() = runTest {
        val gate = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        withViewModel(configureRepository = { authenticationGate = gate }) { viewModel, repository, commits, _ ->
            viewModel.submit(AccountAction.SubmitLogin, validLogin())
            viewModel.submit(AccountAction.SubmitLogin, validLogin())
            runCurrent()

            assertEquals(1, repository.authenticationCalls)
            assertTrue(viewModel.state.value.operationInProgress)

            gate.complete(repository.authenticationResult)
            advanceUntilIdle()

            assertEquals(1, repository.authenticationCalls)
            assertEquals(1, commits.size)
        }
    }

    @Test
    fun smsSuccessOwnsCountdownAndChangingIdentifierCancelsIt() = runTest {
        withViewModel { viewModel, repository, _, _ ->
            val form = AccountUiState(flow = AccountFlow.Register, phone = "13800138000")

            viewModel.submit(AccountAction.RequestSmsCode, form)
            runCurrent()

            assertEquals(1, repository.smsCalls)
            assertEquals(60, viewModel.state.value.smsCountdownSeconds)
            viewModel.invalidateSmsContext()
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.smsCountdownSeconds)
        }
    }

    @Test
    fun closingEntryInvalidatesPendingAuthenticationBeforeSessionCommit() = runTest {
        val gate = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        withViewModel(
            configureRepository = {
                authenticationGate = gate
                authenticationIgnoresCancellation = true
            }
        ) { viewModel, repository, commits, _ ->
            viewModel.submit(AccountAction.SubmitLogin, validLogin())
            runCurrent()

            viewModel.closeEntry()
            gate.complete(repository.authenticationResult)
            advanceUntilIdle()

            assertTrue(commits.isEmpty())
            assertEquals(AccountEntryOperationState(), viewModel.state.value)
        }
    }

    @Test
    fun changingSmsContextInvalidatesPendingResultAndRestoresIdleState() = runTest {
        val gate = CompletableDeferred<AccountRepositoryResult<Unit>>()
        withViewModel(
            configureRepository = {
                smsGate = gate
                smsIgnoresCancellation = true
            }
        ) { viewModel, repository, _, _ ->
            viewModel.submit(
                AccountAction.RequestSmsCode,
                AccountUiState(flow = AccountFlow.Register, phone = "13800138000")
            )
            runCurrent()

            viewModel.invalidateSmsContext()
            gate.complete(repository.smsResult)
            advanceUntilIdle()

            assertEquals(1, repository.smsCalls)
            assertFalse(viewModel.state.value.operationInProgress)
            assertEquals(0, viewModel.state.value.smsCountdownSeconds)
        }
    }

    @Test
    fun closingEntryClearsWechatStateAndCancelsPersistedAuthorization() = runTest {
        val gateway = RecordingWechatGateway()
        withViewModel(wechatAuthGateway = gateway) { viewModel, _, commits, _ ->
            viewModel.onWechatAction(AccountEntryWechatAction.Start(agreementAccepted = true))
            viewModel.closeEntry()
            advanceUntilIdle()

            assertEquals(1, gateway.cancelCalls)
            assertTrue(commits.isEmpty())
            assertEquals(AccountEntryOperationState(), viewModel.state.value)
        }
    }

    @Test
    fun cancelledWechatAuthorizationIsOwnedAsOperationStateWithoutRepositoryCall() = runTest {
        val gateway = RecordingWechatGateway()
        withViewModel(wechatAuthGateway = gateway) { viewModel, repository, commits, _ ->
            viewModel.onWechatAction(AccountEntryWechatAction.Start(agreementAccepted = true))
            assertTrue(viewModel.state.value.wechat.operationInProgress)

            viewModel.onWechatAction(
                AccountEntryWechatAction.HandleCallback(
                    WechatAuthCallback.Cancelled(WechatAuthPurpose.SignInOrRegister)
                )
            )
            advanceUntilIdle()

            assertEquals(0, repository.exchangeWechatCalls)
            assertTrue(commits.isEmpty())
            assertFalse(viewModel.state.value.wechat.operationInProgress)
            assertEquals("已取消微信授权", viewModel.state.value.wechat.errorMessage)
        }
    }

    @Test
    fun wechatSignedInResultCommitsThroughSameSessionBoundary() = runTest {
        val credentials = AccountCredentials(null, "wechat-token", wechatLinked = true)
        withViewModel(
            wechatAuthGateway = RecordingWechatGateway(),
            configureRepository = {
                wechatExchangeResult = AccountRepositoryResult.Success(
                    AccountWechatAuthResult.SignedIn(credentials)
                )
            }
        ) { viewModel, repository, commits, _ ->
            viewModel.onWechatAction(AccountEntryWechatAction.Start(agreementAccepted = true))
            viewModel.onWechatAction(
                AccountEntryWechatAction.HandleCallback(
                    WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
                )
            )
            advanceUntilIdle()

            assertEquals(1, repository.exchangeWechatCalls)
            assertEquals(listOf("wechat-token"), commits)
            assertEquals(WechatLoginPage.Idle, viewModel.state.value.wechat.page)
        }
    }

    @Test
    fun duplicateWechatCallbackIsIgnoredWhileExchangeIsPending() = runTest {
        val gate = CompletableDeferred<AccountRepositoryResult<AccountWechatAuthResult>>()
        withViewModel(
            wechatAuthGateway = RecordingWechatGateway(),
            configureRepository = { wechatExchangeGate = gate }
        ) { viewModel, repository, commits, _ ->
            val callback = AccountEntryWechatAction.HandleCallback(
                WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
            )
            viewModel.onWechatAction(AccountEntryWechatAction.Start(agreementAccepted = true))
            viewModel.onWechatAction(callback)
            viewModel.onWechatAction(callback)
            runCurrent()

            assertEquals(1, repository.exchangeWechatCalls)
            gate.complete(
                AccountRepositoryResult.Success(
                    AccountWechatAuthResult.SignedIn(AccountCredentials(null, "wechat-token"))
                )
            )
            advanceUntilIdle()

            assertEquals(1, repository.exchangeWechatCalls)
            assertEquals(listOf("wechat-token"), commits)
        }
    }

    @Test
    fun localModeUsesSessionBoundaryWithoutPersistingFormState() = runTest {
        withViewModel { viewModel, _, commits, localModeCalls ->
            viewModel.enterLocalMode()

            assertTrue(commits.isEmpty())
            assertEquals(1, localModeCalls())
        }
    }

    private suspend fun TestScope.withViewModel(
        wechatAuthGateway: WechatAuthGateway? = null,
        configureRepository: TestAccountRepository.() -> Unit = {},
        test: suspend (
            AccountEntryViewModel,
            TestAccountRepository,
            MutableList<String>,
            () -> Int
        ) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val repository = TestAccountRepository().apply(configureRepository)
            val commits = mutableListOf<String>()
            var localModeCalls = 0
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountEntryViewModel(
                    accountRepository = repository,
                    commitSession = { credentials -> commits += credentials.token; true },
                    clearPersistedSession = { true },
                    enterLocalMode = { localModeCalls += 1 },
                    wechatAuthGateway = wechatAuthGateway
                ) as T
            }
            val viewModel = ViewModelProvider(store, factory)[AccountEntryViewModel::class.java]
            test(viewModel, repository, commits) { localModeCalls }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun validLogin(password: String = "Aa123456!") = AccountUiState(
        flow = AccountFlow.Login,
        phone = "13800138000",
        password = password,
        agreementAccepted = true
    )

    private class RecordingWechatGateway : WechatAuthGateway {
        var cancelCalls = 0

        override fun startAuthorization(
            purpose: WechatAuthPurpose,
            sessionFingerprint: String?
        ): WechatAuthLaunchResult = WechatAuthLaunchResult.Started

        override fun cancelAuthorization(purpose: WechatAuthPurpose) {
            assertEquals(WechatAuthPurpose.SignInOrRegister, purpose)
            cancelCalls += 1
        }
    }
}
