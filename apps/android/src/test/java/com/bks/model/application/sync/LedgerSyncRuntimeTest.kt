package com.bks.model.application.sync

import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncRuntime
import com.bks.model.application.sync.LedgerSyncSchedulingGateway
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.domain.sync.LedgerSyncRemoteResult
import com.bks.model.domain.sync.LedgerSyncRepository
import android.content.Context
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
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
class LedgerSyncRuntimeTest {
    private lateinit var database: BksDatabase
    private lateinit var localStore: LedgerSyncLocalStore
    private lateinit var accountState: MutableStateFlow<AccountSessionCoordinatorState>
    private lateinit var repository: RuntimeRepository
    private lateinit var scheduler: RecordingScheduler

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        localStore = LedgerSyncLocalStore(database)
        accountState = MutableStateFlow(AccountSessionCoordinatorState(isRestoring = false))
        repository = RuntimeRepository()
        scheduler = RecordingScheduler()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedStartDoesNotDuplicateCollectorsOrInitialSchedule() = runTest {
        localStore.enable("profile-a")
        accountState.value = verifiedState("token-a")
        val runtime = runtime()

        runtime.start()
        runtime.start()
        scheduler.periodicSignal.await()

        assertEquals(1, repository.initializeTokens.size)
        assertEquals(1, scheduler.legacyCancelCalls)
        assertEquals(1, scheduler.periodicCalls)
        assertEquals(1, scheduler.enqueueCalls)
        assertTrue(runtime.state.value.schedulingEligible)

        runtime.onResume()
        runtime.onResume()

        assertEquals(3, scheduler.enqueueCalls)
        runtime.stop()
    }

    @Test
    fun resumeAndSchedulingWaitForVerifiedSignedInEnabledGate() = runTest {
        localStore.enable("profile-a")
        accountState.value = signedInState("token-a", AccountRuntimeStatus.OfflineUnverified)
        val runtime = runtime()

        runtime.start()
        runtime.state.first { it.enabled }
        runtime.onResume()

        assertEquals(0, repository.initializeTokens.size)
        assertEquals(0, scheduler.enqueueCalls)
        assertFalse(runtime.state.value.schedulingEligible)

        accountState.value = verifiedState("token-a")
        scheduler.periodicSignal.await()

        assertEquals(listOf("token-a"), repository.initializeTokens)
        assertEquals(1, scheduler.periodicCalls)
        assertEquals(1, scheduler.enqueueCalls)
        runtime.stop()
    }

    @Test
    fun profileMismatchCancelsSchedulingAndPublishesStableDialogState() = runTest {
        localStore.enable("profile-local")
        repository.profileKey = "profile-remote"
        accountState.value = verifiedState("token-a")
        val runtime = runtime()

        runtime.start()
        scheduler.cancelSignal.await()
        runtime.state.first { it.accountSwitchRequired }

        assertTrue(runtime.state.value.accountSwitchRequired)
        assertFalse(runtime.state.value.schedulingEligible)
        assertEquals(1, scheduler.cancelCalls)
        assertEquals(0, scheduler.periodicCalls)
        assertEquals(0, scheduler.enqueueCalls)
        runtime.stop()
    }

    @Test
    fun restoringSessionDoesNotCancelExistingPeriodicWorkWhenVerificationStaysOffline() = runTest {
        localStore.enable("profile-a")
        accountState.value = AccountSessionCoordinatorState()
        val runtime = runtime()

        runtime.start()
        runtime.state.first { it.enabled }
        runCurrent()
        accountState.value = signedInState("token-a", AccountRuntimeStatus.OfflineUnverified)
        runtime.state.first { it.signedIn }
        runCurrent()

        assertEquals(0, scheduler.cancelCalls)
        assertEquals(0, scheduler.periodicCalls)
        runtime.stop()
    }

    @Test
    fun resumeRetriesSchedulingPreviewAfterTransientFailure() = runTest {
        localStore.enable("profile-a")
        accountState.value = verifiedState("token-a")
        var initializeAttempt = 0
        val firstFailureReturned = CompletableDeferred<Unit>()
        repository.initialize = {
            initializeAttempt += 1
            if (initializeAttempt == 1) {
                firstFailureReturned.complete(Unit)
                LedgerSyncRemoteResult.Failure(null, "offline", true)
            } else {
                LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-a", 0, 0))
            }
        }
        val runtime = runtime()

        runtime.start()
        firstFailureReturned.await()
        runCurrent()
        runtime.onResume()
        scheduler.periodicSignal.await()

        assertEquals(2, initializeAttempt)
        assertTrue(runtime.state.value.schedulingEligible)
        runtime.stop()
    }

    @Test
    fun stalePreviewCannotScheduleAfterAccountLeavesVerifiedSession() = runTest {
        localStore.enable("profile-a")
        accountState.value = verifiedState("token-a")
        val pendingInitialize = CompletableDeferred<LedgerSyncRemoteResult<LedgerSyncInitializeResult>>()
        val initializeStarted = CompletableDeferred<Unit>()
        repository.initialize = {
            initializeStarted.complete(Unit)
            withContext(NonCancellable) { pendingInitialize.await() }
        }
        val runtime = runtime()

        runtime.start()
        initializeStarted.await()
        accountState.value = AccountSessionCoordinatorState(
            session = AccountSession.LocalMode,
            isRestoring = false
        )
        scheduler.cancelSignal.await()
        pendingInitialize.complete(
            LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult("profile-a", 0, 0))
        )
        runCurrent()

        assertEquals(0, scheduler.periodicCalls)
        assertEquals(0, scheduler.enqueueCalls)
        assertFalse(runtime.state.value.schedulingEligible)
        runtime.stop()
    }

    private fun kotlinx.coroutines.test.TestScope.runtime() = LedgerSyncRuntime(
        localStore = localStore,
        coordinator = LedgerSyncCoordinator(localStore, repository) { "device" },
        accountSessionState = accountState,
        scheduler = scheduler,
        applicationScope = this,
        insecureHttpTestMode = false
    )

    private fun verifiedState(token: String) = signedInState(token, AccountRuntimeStatus.Verified)

    private fun signedInState(token: String, status: AccountRuntimeStatus) = AccountSessionCoordinatorState(
        session = AccountSession.SignedIn(token = token),
        isRestoring = false,
        runtimeState = AccountRuntimeState(status)
    )

    private class RecordingScheduler : LedgerSyncSchedulingGateway {
        var enqueueCalls = 0
        var periodicCalls = 0
        var cancelCalls = 0
        var legacyCancelCalls = 0
        val periodicSignal = CompletableDeferred<Unit>()
        val cancelSignal = CompletableDeferred<Unit>()

        override fun enqueueNow() {
            enqueueCalls += 1
        }

        override fun ensurePeriodic() {
            periodicCalls += 1
            periodicSignal.complete(Unit)
        }

        override fun cancel() {
            cancelCalls += 1
            cancelSignal.complete(Unit)
        }

        override fun cancelLegacy() {
            legacyCancelCalls += 1
        }
    }

    private class RuntimeRepository : LedgerSyncRepository {
        override val available = true
        override val insecureHttpTestMode = false
        var profileKey = "profile-a"
        val initializeTokens = mutableListOf<String>()
        var initialize: suspend (String) -> LedgerSyncRemoteResult<LedgerSyncInitializeResult> = {
            LedgerSyncRemoteResult.Success(LedgerSyncInitializeResult(profileKey, 0, 0))
        }

        override suspend fun initialize(token: String, deviceId: String): LedgerSyncRemoteResult<LedgerSyncInitializeResult> {
            initializeTokens += token
            return initialize(token)
        }

        override suspend fun snapshot(token: String, offset: Int) =
            LedgerSyncRemoteResult.Success(LedgerSyncSnapshotPage(emptyList(), null, 0))

        override suspend fun push(
            token: String,
            deviceId: String,
            mutations: List<LedgerSyncMutation>
        ): LedgerSyncRemoteResult<LedgerSyncPushResult> = error("not used")

        override suspend fun pull(
            token: String,
            deviceId: String,
            cursor: Long
        ): LedgerSyncRemoteResult<LedgerSyncPullResult> = error("not used")

        override suspend fun resolve(
            token: String,
            conflictId: String,
            expectedVersion: Long,
            choice: LedgerSyncConflictChoice
        ): LedgerSyncRemoteResult<LedgerSyncRecord> = error("not used")
    }
}
