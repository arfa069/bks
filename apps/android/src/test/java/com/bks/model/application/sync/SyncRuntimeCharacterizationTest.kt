package com.bks.model.application.sync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.sync.LedgerSyncConflictChoice
import com.bks.model.domain.sync.LedgerSyncInitializeResult
import com.bks.model.domain.sync.LedgerSyncMutation
import com.bks.model.domain.sync.LedgerSyncPullResult
import com.bks.model.domain.sync.LedgerSyncPushResult
import com.bks.model.domain.sync.LedgerSyncRecord
import com.bks.model.domain.sync.LedgerSyncSnapshotPage
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.domain.sync.LedgerSyncRemoteResult
import com.bks.model.domain.sync.LedgerSyncRepository
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
            LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-remote", 0, 0))

        override suspend fun snapshot(token: String, offset: Int) =
            LedgerSyncRemoteResult.Success(LedgerSyncSnapshotPage(emptyList(), null, 0))

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutation>
        ): LedgerSyncRemoteResult<LedgerSyncPushResult> {
            pushCalls += 1
            error("push must not run for a mismatched profile")
        }

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResult> {
            pullCalls += 1
            error("pull must not run for a mismatched profile")
        }

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoice
        ): LedgerSyncRemoteResult<LedgerSyncRecord> =
            error("resolve is outside this characterization")
    }
}
