package com.bks.feature.sync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncInitializeResponseContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPullResponseContract
import com.bks.api.LedgerSyncPushResponseContract
import com.bks.api.LedgerSyncResolveConflictResponseContract
import com.bks.api.LedgerSyncSnapshotResponseContract
import com.bks.data.local.AccountSyncStateEntity
import com.bks.data.local.BksDatabase
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

        assertEquals(AccountSyncStateEntity(), localStore.currentState())
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

        override suspend fun initialize(token: String, deviceId: String): LedgerSyncRemoteResult<LedgerSyncInitializeResponseContract> {
            initializeStarted.complete(Unit)
            releaseInitialize.await()
            return LedgerSyncRemoteResult.Success(
                LedgerSyncInitializeResponseContract("profile-a", 0, 0)
            )
        }

        override suspend fun snapshot(token: String, offset: Int): LedgerSyncRemoteResult<LedgerSyncSnapshotResponseContract> =
            error("snapshot should not run")

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutationContract>
        ): LedgerSyncRemoteResult<LedgerSyncPushResponseContract> = error("push should not run")

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResponseContract> {
            pullCalls += 1
            return LedgerSyncRemoteResult.Success(
                LedgerSyncPullResponseContract(emptyList(), emptyList(), 41, false)
            )
        }

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoiceContract
        ): LedgerSyncRemoteResult<LedgerSyncResolveConflictResponseContract> =
            error("resolve should not run")
    }

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
