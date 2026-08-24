package com.bks.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var secureStore: SecureAccountSessionStore
    private lateinit var localStore: LocalModeSessionStore
    private lateinit var repository: TestAccountRepository

    @Before
    fun setUp() {
        clearStores()
        secureStore = SecureAccountSessionStore(context, TestCipher())
        localStore = LocalModeSessionStore(context)
        repository = TestAccountRepository()
    }

    @After
    fun tearDown() {
        clearStores()
    }

    @Test
    fun emptySecureStoreRestoresConfirmedLocalMode() = runTest {
        localStore.confirmLocalMode()
        val coordinator = coordinator()

        coordinator.start()
        advanceUntilIdle()

        assertEquals(AccountSession.LocalMode, coordinator.state.value.session)
        assertEquals(AccountRuntimeStatus.LocalMode, coordinator.state.value.runtimeState.status)
        assertFalse(coordinator.state.value.isRestoring)
        assertEquals(0, repository.verificationCalls)
    }

    @Test
    fun restoredSessionIsVerifiedAndRepeatedStartDoesNotRepeatWork() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        val refreshed = restored.copy(nickname = "refreshed")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(refreshed)
        val coordinator = coordinator()

        coordinator.start()
        coordinator.start()
        advanceUntilIdle()

        assertEquals(refreshed.toSignedInSession(), coordinator.state.value.session)
        assertEquals(AccountRuntimeStatus.Verified, coordinator.state.value.runtimeState.status)
        assertEquals(1, repository.verificationCalls)
        assertEquals(AccountSessionRestoreResult.Restored(refreshed), secureStore.restore())
    }

    @Test
    fun networkFailureKeepsRestoredSessionOffline() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Failure(
            message = "offline",
            kind = AccountFailureKind.Network
        )
        val coordinator = coordinator()

        coordinator.start()
        advanceUntilIdle()

        assertEquals(restored.toSignedInSession(), coordinator.state.value.session)
        assertEquals(AccountRuntimeStatus.OfflineUnverified, coordinator.state.value.runtimeState.status)
    }

    @Test
    fun invalidSessionClearsSecureStoreAndMovesToLocalMode() = runTest {
        secureStore.save(AccountCredentials("13800138000", "token-1"))
        repository.verificationResult = AccountRepositoryResult.Failure(
            message = "invalid",
            kind = AccountFailureKind.InvalidSession
        )
        val coordinator = coordinator()

        coordinator.start()
        advanceUntilIdle()

        assertEquals(AccountSession.LocalMode, coordinator.state.value.session)
        assertEquals(AccountSessionRestoreResult.Empty, secureStore.restore())
        assertEquals(AccountSession.LocalMode, localStore.restoreSession())
    }

    @Test
    fun refreshedSessionPersistenceFailureDoesNotPublishRefreshedCredentials() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        val refreshed = restored.copy(nickname = "must-not-publish")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(refreshed)
        val coordinator = coordinator(persistSession = { false })

        coordinator.start()
        advanceUntilIdle()

        assertEquals(restored.toSignedInSession(), coordinator.state.value.session)
        assertEquals(AccountRuntimeStatus.OfflineUnverified, coordinator.state.value.runtimeState.status)
        assertEquals(AccountSessionRestoreResult.Restored(restored), secureStore.restore())
    }

    @Test
    fun commitFailureAndStaleVerificationResultsDoNotReplaceNewerSession() = runTest {
        val restored = AccountCredentials(accountId = 1, token = "token-1", nickname = "old")
        val replacement = restored.copy(nickname = "new")
        secureStore.save(restored)
        val verification = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        repository.verificationGate = verification
        var allowPersistence = true
        val coordinator = coordinator(persistSession = { credentials ->
            allowPersistence && secureStore.save(credentials)
        })

        coordinator.start()
        advanceUntilIdle()
        allowPersistence = false
        assertFalse(coordinator.commit(replacement))
        assertEquals(restored.toSignedInSession(), coordinator.state.value.session)

        allowPersistence = true
        assertTrue(coordinator.commit(replacement))
        verification.complete(AccountRepositoryResult.Success(restored.copy(nickname = "stale")))
        advanceUntilIdle()

        assertEquals(replacement.toSignedInSession(), coordinator.state.value.session)
        assertEquals(AccountSessionRestoreResult.Restored(replacement), secureStore.restore())

        clearStores()
        secureStore.save(restored)
        repository = TestAccountRepository()
        val invalidVerification = CompletableDeferred<AccountRepositoryResult<AccountCredentials>>()
        repository.verificationGate = invalidVerification
        val invalidCoordinator = coordinator()

        invalidCoordinator.start()
        advanceUntilIdle()
        assertTrue(invalidCoordinator.commit(replacement))
        invalidVerification.complete(
            AccountRepositoryResult.Failure(
                message = "stale invalid",
                kind = AccountFailureKind.InvalidSession
            )
        )
        advanceUntilIdle()

        assertEquals(replacement.toSignedInSession(), invalidCoordinator.state.value.session)
        assertEquals(AccountSessionRestoreResult.Restored(replacement), secureStore.restore())
    }

    @Test
    fun managementCommitPreservesCurrentDeletionStateAndRejectsStaleToken() = runTest {
        val pending = AccountDeletionUiState(1_000L, 604_801_000L)
        val restored = AccountCredentials("13800138000", "token-1", deletionState = pending)
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        val coordinator = coordinator()
        coordinator.start()
        advanceUntilIdle()

        val refreshed = restored.copy(nickname = "new", deletionState = AccountDeletionUiState())
        assertEquals(
            AccountSessionMutationResult.Applied,
            coordinator.commitManagementCredentials("token-1", refreshed)
        )
        assertEquals(pending, coordinator.state.value.deletionState)
        assertEquals(
            AccountSessionRestoreResult.Restored(refreshed.copy(deletionState = pending)),
            secureStore.restore()
        )

        val beforeStaleCommit = coordinator.state.value
        assertEquals(
            AccountSessionMutationResult.StaleSession,
            coordinator.commitManagementCredentials("stale-token", refreshed.copy(nickname = "stale"))
        )
        assertEquals(beforeStaleCommit, coordinator.state.value)
    }

    @Test
    fun verificationCommitUsesServerAuthoritativeDeletionState() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        val coordinator = coordinator()
        coordinator.start()
        advanceUntilIdle()

        val pending = AccountDeletionUiState(1_000L, 604_801_000L)
        assertEquals(
            AccountSessionMutationResult.Applied,
            coordinator.commitVerifiedCredentials("token-1", restored.copy(deletionState = pending))
        )
        assertEquals(pending, coordinator.state.value.deletionState)
        assertEquals(
            AccountRuntimeStatus.DeletionCoolingOff,
            coordinator.state.value.runtimeState.status
        )
        assertEquals(
            AccountSessionRestoreResult.Restored(restored.copy(deletionState = pending)),
            secureStore.restore()
        )

        assertEquals(
            AccountSessionMutationResult.Applied,
            coordinator.commitVerifiedCredentials("token-1", restored)
        )
        assertEquals(AccountDeletionUiState(), coordinator.state.value.deletionState)
        assertEquals(AccountRuntimeStatus.Verified, coordinator.state.value.runtimeState.status)
        assertEquals(AccountSessionRestoreResult.Restored(restored), secureStore.restore())
    }

    @Test
    fun deletionStateIsPersistedAndRestoredWhileOffline() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        val coordinator = coordinator()
        coordinator.start()
        advanceUntilIdle()

        val pending = AccountDeletionUiState(1_000L, 604_801_000L)
        assertEquals(
            AccountSessionMutationResult.Applied,
            coordinator.updateDeletionState("token-1", pending)
        )
        assertEquals(
            AccountSessionRestoreResult.Restored(restored.copy(deletionState = pending)),
            secureStore.restore()
        )

        repository = TestAccountRepository().apply {
            verificationResult = AccountRepositoryResult.Failure(
                message = "offline",
                kind = AccountFailureKind.Network
            )
        }
        val offlineCoordinator = coordinator()
        offlineCoordinator.start()
        advanceUntilIdle()

        assertEquals(pending, offlineCoordinator.state.value.deletionState)
        assertEquals(
            AccountRuntimeStatus.OfflineUnverified,
            offlineCoordinator.state.value.runtimeState.status
        )
        assertEquals(
            AccountSessionMutationResult.Applied,
            offlineCoordinator.updateDeletionState("token-1", AccountDeletionUiState())
        )
        assertEquals(
            AccountSessionRestoreResult.Restored(restored),
            secureStore.restore()
        )
    }

    @Test
    fun deletionPersistenceFailureDoesNotPublishOrOverwriteStoredState() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        var allowPersistence = true
        val coordinator = coordinator(persistSession = { credentials ->
            allowPersistence && secureStore.save(credentials)
        })
        coordinator.start()
        advanceUntilIdle()
        allowPersistence = false

        assertEquals(
            AccountSessionMutationResult.PersistenceFailed,
            coordinator.updateDeletionState(
                "token-1",
                AccountDeletionUiState(1_000L, 604_801_000L)
            )
        )
        assertEquals(AccountDeletionUiState(), coordinator.state.value.deletionState)
        assertEquals(AccountSessionRestoreResult.Restored(restored), secureStore.restore())
    }

    @Test
    fun sessionInvalidationClearsStorageAndCacheOnceBeforePublishingLocalMode() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        var acceptedCalls = 0
        var cacheClearCalls = 0
        val coordinator = coordinator(
            onCredentialsAccepted = { acceptedCalls += 1 },
            onMoveToLocalMode = { cacheClearCalls += 1 }
        )
        coordinator.start()
        advanceUntilIdle()
        assertEquals(1, acceptedCalls)

        assertTrue(coordinator.invalidateSession())

        assertEquals(1, cacheClearCalls)
        assertEquals(AccountSessionRestoreResult.Empty, secureStore.restore())
        assertEquals(AccountSession.LocalMode, localStore.restoreSession())
        assertEquals(AccountSession.LocalMode, coordinator.state.value.session)
    }

    @Test
    fun localModePersistenceFailureDoesNotClearOrPublishSession() = runTest {
        val restored = AccountCredentials("13800138000", "token-1")
        secureStore.save(restored)
        repository.verificationResult = AccountRepositoryResult.Success(restored)
        var cacheClearCalls = 0
        val coordinator = coordinator(
            confirmLocalMode = { false },
            onMoveToLocalMode = { cacheClearCalls += 1 }
        )
        coordinator.start()
        advanceUntilIdle()

        assertFalse(coordinator.invalidateSession())

        assertEquals(0, cacheClearCalls)
        assertEquals(AccountSessionRestoreResult.Restored(restored), secureStore.restore())
        assertEquals(null, localStore.restoreSession())
        assertEquals(restored.toSignedInSession(), coordinator.state.value.session)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        persistSession: (AccountCredentials) -> Boolean = secureStore::save,
        confirmLocalMode: () -> Boolean = localStore::confirmLocalMode,
        onCredentialsAccepted: (AccountCredentials) -> Unit = {},
        onMoveToLocalMode: () -> Unit = {}
    ): AccountSessionCoordinator = AccountSessionCoordinator(
        accountRepository = repository,
        secureSessionStore = secureStore,
        localModeSessionStore = localStore,
        applicationScope = this,
        persistSession = persistSession,
        confirmLocalMode = confirmLocalMode,
        onCredentialsAccepted = onCredentialsAccepted,
        onMoveToLocalMode = onMoveToLocalMode
    )

    private fun clearStores() {
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LOCAL_MODE_SESSION_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class TestCipher : AccountSessionCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = Base64.getEncoder().encode(plainText)

        override fun decrypt(cipherText: ByteArray): ByteArray = Base64.getDecoder().decode(cipherText)
    }
}
