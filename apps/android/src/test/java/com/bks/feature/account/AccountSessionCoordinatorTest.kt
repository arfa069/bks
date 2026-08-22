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

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        persistSession: (AccountCredentials) -> Boolean = secureStore::save
    ): AccountSessionCoordinator = AccountSessionCoordinator(
        accountRepository = repository,
        secureSessionStore = secureStore,
        localModeSessionStore = localStore,
        applicationScope = this,
        persistSession = persistSession
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
