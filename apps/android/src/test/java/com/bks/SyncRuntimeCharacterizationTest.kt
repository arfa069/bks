package com.bks

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncInitializeResponseContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPullResponseContract
import com.bks.api.LedgerSyncPushResponseContract
import com.bks.api.LedgerSyncResolveConflictResponseContract
import com.bks.api.LedgerSyncSnapshotResponseContract
import com.bks.data.local.BksDatabase
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.sync.LedgerSyncCoordinator
import com.bks.feature.sync.LedgerSyncLocalStore
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncRemoteResult
import com.bks.feature.sync.LedgerSyncRepository
import com.bks.feature.sync.LedgerSyncUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRuntimeCharacterizationTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var localStore: LedgerSyncLocalStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        localStore = LedgerSyncLocalStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun resumeEnqueuesOnlyForVerifiedSignedInEnabledSync() {
        val signedIn = AccountSession.SignedIn(token = "token")
        val verified = AccountRuntimeState(AccountRuntimeStatus.Verified)
        val enabled = LedgerSyncUiState(enabled = true)

        assertTrue(shouldEnqueueLedgerSync(Lifecycle.Event.ON_RESUME, signedIn, verified, enabled))
        assertFalse(shouldEnqueueLedgerSync(Lifecycle.Event.ON_START, signedIn, verified, enabled))
        assertFalse(shouldEnqueueLedgerSync(Lifecycle.Event.ON_RESUME, null, verified, enabled))
        assertFalse(
            shouldEnqueueLedgerSync(
                Lifecycle.Event.ON_RESUME,
                AccountSession.LocalMode,
                verified,
                enabled
            )
        )
        assertFalse(
            shouldEnqueueLedgerSync(
                Lifecycle.Event.ON_RESUME,
                signedIn,
                AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified),
                enabled
            )
        )
        assertFalse(
            shouldEnqueueLedgerSync(
                Lifecycle.Event.ON_RESUME,
                signedIn,
                verified,
                LedgerSyncUiState(enabled = false)
            )
        )
    }

    @Test
    fun synchronizeRejectsDifferentRemoteProfileBeforePushOrPull() = runBlocking {
        localStore.enable("profile-local")
        val repository = ProfileMismatchRepository()
        val coordinator = LedgerSyncCoordinator(localStore, repository) { "device" }

        val result = coordinator.synchronize("token") as LedgerSyncOperationResult.Failure

        assertEquals("SYNC_ACCOUNT_SWITCH_REQUIRED", result.code)
        assertFalse(result.retryable)
        assertEquals("profile-local", localStore.currentState().profileKey)
        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.pullCalls)
    }

    private class ProfileMismatchRepository : LedgerSyncRepository {
        override val available = true
        override val insecureHttpTestMode = false
        var pushCalls = 0
        var pullCalls = 0

        override suspend fun initialize(token: String, deviceId: String) =
            LedgerSyncRemoteResult.Success(LedgerSyncInitializeResponseContract("profile-remote", 0, 0))

        override suspend fun snapshot(token: String, offset: Int) =
            LedgerSyncRemoteResult.Success(LedgerSyncSnapshotResponseContract(emptyList(), null, 0))

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutationContract>
        ): LedgerSyncRemoteResult<LedgerSyncPushResponseContract> {
            pushCalls += 1
            error("push must not run for a mismatched profile")
        }

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResponseContract> {
            pullCalls += 1
            error("pull must not run for a mismatched profile")
        }

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoiceContract
        ): LedgerSyncRemoteResult<LedgerSyncResolveConflictResponseContract> =
            error("resolve is outside this characterization")
    }
}
