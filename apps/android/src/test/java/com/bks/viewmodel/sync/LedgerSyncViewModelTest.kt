package com.bks.viewmodel.sync

import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncRuntime
import com.bks.model.application.sync.LedgerSyncSchedulingGateway
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.data.remote.sync.entityId
import com.bks.model.data.remote.sync.entityType
import com.bks.model.data.remote.sync.toContract
import com.bks.model.domain.ledger.LedgerBook
import com.bks.model.domain.sync.LedgerSyncRemoteResult
import com.bks.model.domain.sync.LedgerSyncRepository
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.sync.LedgerSyncConflictChoice
import com.bks.model.domain.sync.LedgerSyncEntityType
import com.bks.model.domain.sync.LedgerSyncInitializeResult
import com.bks.api.LedgerSyncJsonContracts
import com.bks.model.domain.sync.LedgerSyncMutation
import com.bks.model.domain.sync.LedgerSyncMutationResult
import com.bks.model.domain.sync.LedgerSyncPayload
import com.bks.model.domain.sync.LedgerSyncPullResult
import com.bks.model.domain.sync.LedgerSyncPushResult
import com.bks.model.domain.sync.LedgerSyncRecord
import com.bks.model.domain.sync.LedgerSyncSnapshotPage
import com.bks.model.data.local.database.AccountSyncConflictEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerSyncViewModelTest {
    private lateinit var database: BksDatabase
    private lateinit var localStore: LedgerSyncLocalStore
    private lateinit var accountState: MutableStateFlow<AccountSessionCoordinatorState>
    private lateinit var repository: ViewModelRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        localStore = LedgerSyncLocalStore(database)
        accountState = MutableStateFlow(
            AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
            )
        )
        repository = ViewModelRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mapsConflictEntitiesToUiModelsWithoutExposingRoomEntities() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        var runtime: LedgerSyncRuntime? = null
        try {
            val canonical = LedgerSyncPayload.LedgerBook("book-a", "云端账本", 1)
            val candidate = LedgerSyncPayload.LedgerBook("book-a", "本机账本", 1)
            database.ledgerSyncDao().upsertConflict(
                AccountSyncConflictEntity(
                    conflictId = "conflict-a",
                    entityType = LedgerSyncEntityType.LEDGER_BOOK.name,
                    entityId = "book-a",
                    canonicalVersion = 2,
                    canonicalDeleted = false,
                    canonicalPayload = LedgerSyncJsonContracts.encodePayload(
                        LedgerSyncEntityType.LEDGER_BOOK.toContract(),
                        canonical.toContract()
                    ),
                    candidateDeleted = false,
                    candidatePayload = LedgerSyncJsonContracts.encodePayload(
                        LedgerSyncEntityType.LEDGER_BOOK.toContract(),
                        candidate.toContract()
                    ),
                    createdAtMillis = 1
                )
            )
            val owners = createViewModel(store)
            runtime = owners.runtime
            val viewModel = owners.viewModel

            val conflict = viewModel.uiState.first { it.conflicts.isNotEmpty() }.conflicts.single()
            assertEquals("conflict-a", conflict.conflictId)
            assertEquals("账本冲突", conflict.title)
            assertEquals("云端账本", conflict.cloudSummary)
            assertEquals("本机账本", conflict.localSummary)
        } finally {
            store.clear()
            runtime?.stop()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun previewOwnsBusyAndDialogStateAndClearsBusyAfterCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        var runtime: LedgerSyncRuntime? = null
        try {
            val pendingInitialize = CompletableDeferred<LedgerSyncRemoteResult<LedgerSyncInitializeResult>>()
            repository.initialize = { pendingInitialize.await() }
            val owners = createViewModel(store)
            runtime = owners.runtime
            val viewModel = owners.viewModel

            val result = async { viewModel.preview() }
            viewModel.uiState.first { it.previewBusy }

            pendingInitialize.complete(
                LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-a", 0, 0))
            )
            val previewResult = result.await()
            assertTrue(previewResult is LedgerSyncActionResult.Success)
            val previewed = viewModel.uiState.first { !it.previewBusy && it.preview != null }
            assertFalse(previewed.previewBusy)
            assertEquals(0, previewed.preview?.localRecordCount)
            assertEquals(0, previewed.preview?.cloudRecordCount)
            assertFalse(requireNotNull(previewed.preview).insecureHttpTestMode)
            assertTrue(previewed.conflicts.isEmpty())

            viewModel.dismissPreview()
            assertEquals(null, viewModel.uiState.first { it.preview == null }.preview)
        } finally {
            store.clear()
            runtime?.stop()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun previewFailureMapsMessageAndClearsBusyWithoutPublishingPreview() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        var runtime: LedgerSyncRuntime? = null
        try {
            repository.initialize = {
                LedgerSyncRemoteResult.Failure("OFFLINE", "同步服务不可用", true)
            }
            val owners = createViewModel(store)
            runtime = owners.runtime
            val viewModel = owners.viewModel

            val result = viewModel.preview()

            assertEquals(
                LedgerSyncActionResult.Failure("同步服务不可用"),
                result
            )
            val settled = viewModel.uiState.first { !it.previewBusy }
            assertEquals(null, settled.preview)
            assertTrue(settled.conflicts.isEmpty())
        } finally {
            store.clear()
            runtime?.stop()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stalePreviewResultIsDiscardedWhenAccountChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        var runtime: LedgerSyncRuntime? = null
        try {
            val pendingInitialize = CompletableDeferred<LedgerSyncRemoteResult<LedgerSyncInitializeResult>>()
            val initializeStarted = CompletableDeferred<Unit>()
            repository.initialize = {
                initializeStarted.complete(Unit)
                withContext(NonCancellable) { pendingInitialize.await() }
            }
            val owners = createViewModel(store)
            runtime = owners.runtime
            val viewModel = owners.viewModel

            val result = async { viewModel.preview() }
            initializeStarted.await()
            accountState.value = AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-b"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
            )
            pendingInitialize.complete(
                LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-a", 2, 3))
            )

            assertTrue(result.await() is LedgerSyncActionResult.Success)
            assertEquals(null, viewModel.uiState.first { !it.previewBusy }.preview)
        } finally {
            store.clear()
            runtime?.stop()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulAccountSwitchClosesDialogWhenFollowUpPreviewFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        var runtime: LedgerSyncRuntime? = null
        try {
            localStore.enable("profile-old")
            accountState.value = AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-new"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified)
            )
            var initializeAttempt = 0
            val followUpFailureReturned = CompletableDeferred<Unit>()
            repository.initialize = {
                initializeAttempt += 1
                if (initializeAttempt >= 4) {
                    followUpFailureReturned.complete(Unit)
                    LedgerSyncRemoteResult.Failure(null, "offline", true)
                } else {
                    LedgerSyncRemoteResult.Success(
                        LedgerSyncInitializeResult("profile-new", 0, 0)
                    )
                }
            }
            val scheduler = RecordingScheduler()
            val owners = createViewModel(store, scheduler)
            runtime = owners.runtime
            val viewModel = owners.viewModel
            viewModel.uiState.first { it.showAccountSwitch }

            val result = viewModel.switchAccount()
            followUpFailureReturned.await()

            assertTrue(result is LedgerSyncActionResult.Success)
            assertFalse(viewModel.uiState.first { !it.accountSwitchBusy }.showAccountSwitch)
            assertEquals(1, scheduler.periodicCalls)
        } finally {
            store.clear()
            runtime?.stop()
            Dispatchers.resetMain()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.createViewModel(
        store: ViewModelStore,
        scheduler: LedgerSyncSchedulingGateway = NoOpScheduler
    ): ViewModelOwners {
        val coordinator = LedgerSyncCoordinator(localStore, repository) { "device" }
        val runtime = LedgerSyncRuntime(
            localStore = localStore,
            coordinator = coordinator,
            accountSessionState = accountState,
            scheduler = scheduler,
            applicationScope = this,
            insecureHttpTestMode = false
        ).also { it.start() }
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerSyncViewModel(
                    runtime = runtime,
                    coordinator = coordinator,
                    accountSessionState = accountState
                ) as T
            }
        )[LedgerSyncViewModel::class.java]
        return ViewModelOwners(runtime, viewModel)
    }

    private data class ViewModelOwners(
        val runtime: LedgerSyncRuntime,
        val viewModel: LedgerSyncViewModel
    )

    private object NoOpScheduler : LedgerSyncSchedulingGateway {
        override fun enqueueNow() = Unit
        override fun ensurePeriodic() = Unit
        override fun cancel() = Unit
    }

    private class RecordingScheduler : LedgerSyncSchedulingGateway {
        var periodicCalls = 0

        override fun enqueueNow() = Unit

        override fun ensurePeriodic() {
            periodicCalls += 1
        }

        override fun cancel() = Unit
    }

    private class ViewModelRepository : LedgerSyncRepository {
        override val available = true
        override val insecureHttpTestMode = false
        var initialize: suspend () -> LedgerSyncRemoteResult<LedgerSyncInitializeResult> = {
            LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-a", 0, 0))
        }

        override suspend fun initialize(token: String, deviceId: String) = initialize()

        override suspend fun snapshot(token: String, offset: Int) =
            LedgerSyncRemoteResult.Success(LedgerSyncSnapshotPage(emptyList(), null, 0))

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutation>
        ): LedgerSyncRemoteResult<LedgerSyncPushResult> = LedgerSyncRemoteResult.Success(
            LedgerSyncPushResult(
                results = mutations.map {
                    LedgerSyncMutationResult(
                        mutationId = it.mutationId,
                        accepted = true,
                        version = 1,
                        revision = 1,
                        conflictId = null
                    )
                },
                currentCursor = 1
            )
        )

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResult> = LedgerSyncRemoteResult.Success(
            LedgerSyncPullResult(emptyList(), emptyList(), cursor, false)
        )

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoice
        ): LedgerSyncRemoteResult<LedgerSyncRecord> = error("not used")
    }
}
