package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountDeletionState
import com.bks.model.application.account.AccountSessionMutationResult
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountFailureKind
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRepositoryResult
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountWechatAuthResult
import com.bks.model.domain.account.toCredentials
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose
import com.bks.model.domain.account.wechatSessionFingerprint
import com.bks.model.domain.account.TestAccountRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.bks.model.domain.account.AccountIdentifier
import com.bks.model.domain.account.AccountIdentifierType
import com.bks.model.domain.account.IdentifierLinkPreparation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountManagementViewModelTest {
    @Test
    fun verificationSuccessCommitsOnlyThroughSessionBoundary() = runTest {
        withViewModel { fixture ->
            val refreshed = fixture.session.toCredentials(fixture.deletionState).copy(nickname = "已刷新")
            fixture.repository.verificationResult = AccountRepositoryResult.Success(refreshed)

            fixture.viewModel.retryVerification(fixture.session, fixture.deletionState)
            advanceUntilIdle()

            assertEquals(listOf(refreshed), fixture.commits)
            assertEquals(listOf(refreshed), fixture.verifiedCommits)
            assertFalse(fixture.viewModel.state.value.accountOperationInProgress)
            assertNull(fixture.viewModel.state.value.accountError)
            assertEquals(fixture.deletionState, fixture.commits.single().deletionState)
        }
    }

    @Test
    fun verificationPersistenceFailureKeepsSessionOfflineAndReportsStableError() = runTest {
        withViewModel(commitSucceeds = false) { fixture ->
            fixture.viewModel.retryVerification(fixture.session, fixture.deletionState)
            advanceUntilIdle()

            assertEquals(1, fixture.repository.verificationCalls)
            assertEquals(0, fixture.invalidateSessionCalls)
            assertEquals("账号资料已验证，但无法保存到本机，请重试", fixture.viewModel.state.value.accountError)
        }
    }

    @Test
    fun signOutRequiresRemoteSuccessAndLocalClearBeforePublishingSignedOut() = runTest {
        withViewModel { fixture ->
            fixture.repository.signOutResult = AccountRepositoryResult.Failure(
                AccountFailureKind.Network,
                "网络连接失败，请检查网络后重试"
            )
            fixture.viewModel.signOut(fixture.session)
            advanceUntilIdle()
            assertEquals(0, fixture.invalidateSessionCalls)

            fixture.repository.signOutResult = AccountRepositoryResult.Success(Unit)
            fixture.viewModel.signOut(fixture.session)
            advanceUntilIdle()
            assertEquals(1, fixture.invalidateSessionCalls)
        }
    }

    @Test
    fun deletionRequestAndCancelPublishOnlyThroughDeletionBoundary() = runTest {
        withViewModel { fixture ->
            fixture.viewModel.requestDeletion(fixture.session)
            advanceUntilIdle()
            fixture.viewModel.cancelDeletion(fixture.session)
            advanceUntilIdle()

            assertEquals(2, fixture.deletionUpdates.size)
            assertTrue(fixture.deletionUpdates.first().isPending)
            assertEquals(AccountDeletionState(), fixture.deletionUpdates.last())
        }
    }

    @Test
    fun profileAndAvatarSuccessCommitCredentialsAndNeverPersistSensitiveInputInState() = runTest {
        withViewModel { fixture ->
            fixture.viewModel.updateNickname(fixture.session, fixture.deletionState, "新昵称")
            advanceUntilIdle()
            fixture.viewModel.updateAvatar(fixture.session, fixture.deletionState, "data:image/jpeg;base64,private")
            advanceUntilIdle()

            assertEquals(2, fixture.commits.size)
            assertEquals("新昵称", fixture.commits.first().nickname)
            assertEquals("data:image/jpeg;base64,private", fixture.commits.last().avatarUrl)
            assertFalse(fixture.viewModel.state.value.toString().contains("private"))
        }
    }

    @Test
    fun wechatCallbackOwnsMergeTicketAndCancelOrCloseClearsTemporaryState() = runTest {
        val gateway = RecordingGateway()
        withViewModel(wechatAuthGateway = gateway) { fixture ->
            fixture.repository.wechatExchangeResult = AccountRepositoryResult.Success(
                AccountWechatAuthResult.MergeRequired(
                    mergeTicket = "temporary-merge-ticket",
                    sourceNickname = "来源账号",
                    sourceIdentifiers = emptyList(),
                    ticketExpiresAtMillis = 300_000L
                )
            )
            fixture.viewModel.startWechatLink(fixture.session)
            fixture.viewModel.handleWechatCallback(
                fixture.session,
                WechatAuthCallback.Authorized(
                    code = "one-time-code",
                    purpose = WechatAuthPurpose.LinkCurrentAccount,
                    sessionFingerprint = wechatSessionFingerprint(fixture.session.token)
                )
            )
            advanceUntilIdle()

            assertEquals("temporary-merge-ticket", fixture.viewModel.state.value.mergeTicket)
            assertEquals(AccountIdentityPage.Merge, fixture.viewModel.state.value.identityPage)
            fixture.viewModel.closeIdentityPage()
            assertNull(fixture.viewModel.state.value.mergeTicket)
            assertEquals(AccountIdentityPage.Idle, fixture.viewModel.state.value.identityPage)

            fixture.viewModel.startWechatLink(fixture.session)
            fixture.viewModel.handleWechatCallback(
                fixture.session,
                WechatAuthCallback.Cancelled(
                    purpose = WechatAuthPurpose.LinkCurrentAccount,
                    sessionFingerprint = wechatSessionFingerprint(fixture.session.token)
                )
            )
            assertEquals("已取消微信授权", fixture.viewModel.state.value.identityError)
        }
    }

    @Test
    fun identifierTicketIsOwnedByViewModelAndClearedWhenInputContextChanges() = runTest {
        withViewModel { fixture ->
            fixture.repository.phoneLinkPrepareResult = AccountRepositoryResult.Success(
                IdentifierLinkPreparation.LinkTicketIssued("temporary-link-ticket", 300_000L)
            )
            fixture.viewModel.openIdentityPage(AccountIdentityPage.AttachIdentifier)
            fixture.viewModel.prepareIdentifierLink(
                fixture.session,
                AccountIdentityFormState(
                    page = AccountIdentityPage.AttachIdentifier,
                    phone = "13900139000",
                    targetIdentifierType = AccountIdentifierType.PHONE
                )
            )
            advanceUntilIdle()
            assertEquals("temporary-link-ticket", fixture.viewModel.state.value.phoneTicket)

            fixture.viewModel.identityContextChanged()
            assertNull(fixture.viewModel.state.value.phoneTicket)
            assertFalse(fixture.viewModel.state.value.identityOperationInProgress)
        }
    }

    @Test
    fun invalidSessionAndPersistenceFailureClearTicketsAndUseInvalidationBoundary() = runTest {
        withViewModel(commitSucceeds = false) { fixture ->
            fixture.repository.updateNicknameResult = AccountRepositoryResult.Success(
                fixture.session.toCredentials(fixture.deletionState).copy(nickname = "不能持久化")
            )
            fixture.viewModel.openIdentityPage(AccountIdentityPage.EditNickname)
            fixture.viewModel.updateNickname(fixture.session, fixture.deletionState, "不能持久化")
            advanceUntilIdle()

            assertEquals(1, fixture.invalidateSessionCalls)
            assertTrue(fixture.repository.signOutCalls >= 1)
            assertEquals(AccountIdentityPage.Idle, fixture.viewModel.state.value.identityPage)
            assertNull(fixture.viewModel.state.value.phoneTicket)
        }
    }

    @Test
    fun closingManagementRejectsNonCooperativeLateVerificationResult() = runTest {
        val gate = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        withViewModel(
            configureRepository = {
                verificationGate = gate
                verificationIgnoresCancellation = true
            }
        ) { fixture ->
            fixture.viewModel.retryVerification(fixture.session, fixture.deletionState)
            runCurrent()
            fixture.viewModel.closeManagement()
            gate.complete(AccountRepositoryResult.Success(fixture.session.toCredentials(fixture.deletionState)))
            advanceUntilIdle()

            assertTrue(fixture.commits.isEmpty())
            assertEquals(AccountManagementOperationState(draftResetGeneration = 1), fixture.viewModel.state.value)
        }
    }

    @Test
    fun localInvalidationCompletesBeforeBestEffortRevokeCanBeCancelled() = runTest {
        val signOutGate = CompletableDeferred<AccountRepositoryResult<Unit>>()
        withViewModel(
            commitSucceeds = false,
            configureRepository = { this.signOutGate = signOutGate }
        ) { fixture ->
            fixture.viewModel.updateNickname(fixture.session, fixture.deletionState, "不能持久化")
            runCurrent()

            assertEquals(1, fixture.invalidateSessionCalls)
            assertEquals(1, fixture.repository.signOutCalls)
            fixture.viewModel.closeManagement()
            advanceUntilIdle()

            assertEquals(1, fixture.invalidateSessionCalls)
            assertFalse(signOutGate.isCompleted)
        }
    }

    @Test
    fun accountOperationIsRejectedWhileIdentityOperationIsRunning() = runTest {
        val identityGate = CompletableDeferred<Unit>()
        withViewModel(
            configureRepository = { prepareIdentifierLinkGate = identityGate }
        ) { fixture ->
            fixture.viewModel.prepareIdentifierLink(
                fixture.session,
                AccountIdentityFormState(
                    phone = "13900139000",
                    targetIdentifierType = AccountIdentifierType.PHONE
                )
            )
            runCurrent()
            fixture.viewModel.requestDeletion(fixture.session)
            runCurrent()

            assertEquals(0, fixture.repository.requestDeletionCalls)
            identityGate.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun identityOperationIsRejectedWhileAccountOperationIsRunning() = runTest {
        val verificationGate = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        withViewModel(
            configureRepository = { this.verificationGate = verificationGate }
        ) { fixture ->
            fixture.viewModel.retryVerification(fixture.session, fixture.deletionState)
            runCurrent()
            fixture.viewModel.updateNickname(fixture.session, fixture.deletionState, "不能并发")
            runCurrent()

            assertEquals(0, fixture.repository.updateNicknameCalls)
            verificationGate.complete(
                AccountRepositoryResult.Success(fixture.session.toCredentials(fixture.deletionState))
            )
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.withViewModel(
        commitSucceeds: Boolean = true,
        wechatAuthGateway: WechatAuthGateway? = null,
        configureRepository: TestAccountRepository.() -> Unit = {},
        test: suspend (Fixture) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val repository = TestAccountRepository().apply(configureRepository)
            val fixture = Fixture(repository)
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountManagementViewModel(
                    accountRepository = repository,
                    commitManagementCredentials = { expectedToken, credentials ->
                        if (expectedToken != fixture.session.token) {
                            AccountSessionMutationResult.StaleSession
                        } else if (commitSucceeds) {
                            fixture.commits += credentials
                            AccountSessionMutationResult.Applied
                        } else {
                            AccountSessionMutationResult.PersistenceFailed
                        }
                    },
                    commitVerifiedCredentials = { expectedToken, credentials ->
                        if (expectedToken != fixture.session.token) {
                            AccountSessionMutationResult.StaleSession
                        } else if (commitSucceeds) {
                            fixture.commits += credentials
                            fixture.verifiedCommits += credentials
                            AccountSessionMutationResult.Applied
                        } else {
                            AccountSessionMutationResult.PersistenceFailed
                        }
                    },
                    invalidateSession = {
                        fixture.invalidateSessionCalls += 1
                        true
                    },
                    updateDeletionState = { expectedToken, state ->
                        if (expectedToken == fixture.session.token) {
                            fixture.deletionUpdates += state
                            AccountSessionMutationResult.Applied
                        } else {
                            AccountSessionMutationResult.StaleSession
                        }
                    },
                    wechatAuthGateway = wechatAuthGateway
                ) as T
            }
            fixture.viewModel = ViewModelProvider(store, factory)[AccountManagementViewModel::class.java]
            fixture.viewModel.activate(fixture.session)
            test(fixture)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(val repository: TestAccountRepository) {
        lateinit var viewModel: AccountManagementViewModel
        val deletionState = AccountDeletionState(1_000L, 604_801_000L)
        val session = AccountSession.SignedIn(
            primaryIdentifier = AccountIdentifier(AccountIdentifierType.PHONE, "13800138000"),
            identifiers = listOf(AccountIdentifier(AccountIdentifierType.PHONE, "13800138000")),
            token = "token-1"
        )
        val commits = mutableListOf<AccountCredentials>()
        val verifiedCommits = mutableListOf<AccountCredentials>()
        val deletionUpdates = mutableListOf<AccountDeletionState>()
        var invalidateSessionCalls = 0
    }

    private class RecordingGateway : WechatAuthGateway {
        override fun startAuthorization(
            purpose: WechatAuthPurpose,
            sessionFingerprint: String?
        ): WechatAuthLaunchResult = WechatAuthLaunchResult.Started
    }
}
