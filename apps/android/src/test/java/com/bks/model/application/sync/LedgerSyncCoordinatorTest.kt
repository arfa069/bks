package com.bks.model.application.sync

import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.domain.sync.LedgerSyncRemoteResult
import com.bks.model.domain.sync.LedgerSyncRepository
import com.bks.model.domain.sync.LedgerSyncLocalState
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
import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerSyncCoordinatorTest {
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
        localStore = LedgerSyncLocalStore(database, clock = { NOW })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun disableWaitsForInFlightSynchronizationAndWinsFinalLocalState() = runTest {
        localStore.enable("profile-a")
        val repository = BlockingInitializeRepository()
        val coordinator = LedgerSyncCoordinator(localStore, repository) { "device" }

        val synchronization = async { coordinator.synchronize("token") }
        repository.initializeStarted.await()
        val disable = async { coordinator.disable() }
        runCurrent()

        assertFalse(disable.isCompleted)

        repository.releaseInitialize.complete(Unit)
        assertTrue(synchronization.await() is LedgerSyncOperationResult.Success)
        assertEquals(1, repository.pullCalls)
        disable.await()

        assertEquals(LedgerSyncLocalState(), localStore.currentState())
        assertEquals(0, localStore.pendingMutationCount())
    }

    @Test
    fun localDataReplacementBlocksNewSynchronizationUntilReplacementFinishes() = runTest {
        localStore.enable("profile-a")
        val repository = BlockingInitializeRepository()
        val coordinator = LedgerSyncCoordinator(localStore, repository) { "device" }
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()

        val replacement = async {
            coordinator.withLocalDataReplacement {
                replacementStarted.complete(Unit)
                releaseReplacement.await()
            }
        }
        replacementStarted.await()
        val synchronization = async { coordinator.synchronize("token") }
        runCurrent()

        assertFalse(repository.initializeStarted.isCompleted)

        releaseReplacement.complete(Unit)
        replacement.await()
        repository.initializeStarted.await()
        repository.releaseInitialize.complete(Unit)

        assertTrue(synchronization.await() is LedgerSyncOperationResult.Success)
        assertEquals(1, repository.pullCalls)
    }

    @Test
    fun inFlightSynchronizationBlocksLocalDataReplacementUntilSyncFinishes() = runTest {
        localStore.enable("profile-a")
        val repository = BlockingInitializeRepository()
        val coordinator = LedgerSyncCoordinator(localStore, repository) { "device" }
        val replacementStarted = CompletableDeferred<Unit>()

        val synchronization = async { coordinator.synchronize("token") }
        repository.initializeStarted.await()
        val replacement = async {
            coordinator.withLocalDataReplacement {
                replacementStarted.complete(Unit)
            }
        }
        runCurrent()

        assertFalse(replacementStarted.isCompleted)

        repository.releaseInitialize.complete(Unit)
        assertTrue(synchronization.await() is LedgerSyncOperationResult.Success)
        replacement.await()

        assertTrue(replacementStarted.isCompleted)
        assertEquals(1, repository.pullCalls)
    }

    private class BlockingInitializeRepository : LedgerSyncRepository {
        override val available = true
        override val insecureHttpTestMode = false
        val initializeStarted = CompletableDeferred<Unit>()
        val releaseInitialize = CompletableDeferred<Unit>()
        var pullCalls = 0

        override suspend fun initialize(token: String, deviceId: String): LedgerSyncRemoteResult<LedgerSyncInitializeResult> {
            initializeStarted.complete(Unit)
            releaseInitialize.await()
            return LedgerSyncRemoteResult.Success(
                LedgerSyncInitializeResult("profile-a", 0, 0)
            )
        }

        override suspend fun snapshot(token: String, offset: Int): LedgerSyncRemoteResult<LedgerSyncSnapshotPage> =
            error("snapshot should not run")

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutation>
        ): LedgerSyncRemoteResult<LedgerSyncPushResult> = error("push should not run")

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResult> {
            pullCalls += 1
            return LedgerSyncRemoteResult.Success(
                LedgerSyncPullResult(emptyList(), emptyList(), 41, false)
            )
        }

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoice
        ): LedgerSyncRemoteResult<LedgerSyncRecord> =
            error("resolve should not run")
    }

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
