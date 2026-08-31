package com.bks.model.application.sync

import com.bks.model.domain.sync.LedgerDataChangeRegistration
import com.bks.model.domain.sync.LedgerSyncConflict
import com.bks.model.domain.sync.LedgerSyncLocalRepository
import com.bks.model.domain.sync.LedgerSyncLocalState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class LedgerSyncRuntimeState(
    val signedIn: Boolean = false,
    val enabled: Boolean = false,
    val profileKey: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val conflicts: List<LedgerSyncConflict> = emptyList(),
    val insecureHttpTestMode: Boolean = false,
    val accountSwitchRequired: Boolean = false,
    val schedulingEligible: Boolean = false
)

internal interface LedgerSyncSchedulingGateway {
    fun enqueueNow()
    fun ensurePeriodic()
    fun cancel()
    fun cancelLegacy() = Unit
}

internal class LedgerSyncRuntime(
    private val localStore: LedgerSyncLocalRepository,
    private val coordinator: LedgerSyncCoordinator,
    private val accountSessionState: StateFlow<AccountSessionCoordinatorState>,
    private val scheduler: LedgerSyncSchedulingGateway,
    private val applicationScope: CoroutineScope,
    insecureHttpTestMode: Boolean
) {
    private val started = AtomicBoolean(false)
    private val schedulingGeneration = AtomicLong(0)
    private val jobs = mutableListOf<Job>()
    private val schedulingPreviewLock = Any()
    private var schedulingPreviewJob: Job? = null
    private var dataChangeRegistration: LedgerDataChangeRegistration? = null
    @Volatile
    private var latestSchedulingGate = SchedulingGate()
    private val mutableState = MutableStateFlow(
        LedgerSyncRuntimeState(insecureHttpTestMode = insecureHttpTestMode)
    )

    val state: StateFlow<LedgerSyncRuntimeState> = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scheduler.cancelLegacy()
        dataChangeRegistration = localStore.observeFormalDataChanges {
            if (mutableState.value.schedulingEligible) scheduler.enqueueNow()
        }
        jobs += applicationScope.launch { collectDurableState() }
        jobs += applicationScope.launch { collectSchedulingGate() }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        schedulingGeneration.incrementAndGet()
        synchronized(schedulingPreviewLock) {
            schedulingPreviewJob?.cancel()
            schedulingPreviewJob = null
        }
        dataChangeRegistration?.close()
        dataChangeRegistration = null
        jobs.forEach(Job::cancel)
        jobs.clear()
    }

    fun onResume() {
        if (mutableState.value.schedulingEligible) {
            scheduler.enqueueNow()
        } else {
            val gate = latestSchedulingGate
            if (gate.canPreview) launchSchedulingPreview(gate, schedulingGeneration.get())
        }
    }

    suspend fun onSyncOperationSucceeded() {
        val account = accountSessionState.value
        val syncState = localStore.currentState()
        if (account != accountSessionState.value) return
        val signedIn = account.session as? AccountSession.SignedIn ?: return
        if (
            account.isRestoring ||
            account.runtimeState.status != AccountRuntimeStatus.Verified ||
            !syncState.enabled
        ) {
            return
        }
        latestSchedulingGate = SchedulingGate(
            token = signedIn.token,
            restoring = false,
            verified = true,
            enabled = true,
            profileKey = syncState.profileKey
        )
        mutableState.update {
            it.copy(accountSwitchRequired = false, schedulingEligible = true)
        }
        scheduler.ensurePeriodic()
        scheduler.enqueueNow()
    }

    suspend fun disable() {
        scheduler.cancel()
        coordinator.disable()
    }

    private suspend fun collectDurableState() {
        combine(
            accountSessionState,
            localStore.state,
            localStore.outboxCount,
            localStore.conflicts
        ) { account, syncState, outboxCount, conflicts ->
            DurableStateSnapshot(account, syncState, outboxCount, conflicts)
        }.collect { snapshot ->
            mutableState.update { current ->
                current.copy(
                    signedIn = snapshot.account.session is AccountSession.SignedIn,
                    enabled = snapshot.syncState?.enabled == true,
                    profileKey = snapshot.syncState?.profileKey,
                    lastSuccessAtMillis = snapshot.syncState?.lastSuccessAtMillis,
                    lastError = snapshot.syncState?.lastError,
                    pendingCount = snapshot.outboxCount,
                    conflicts = snapshot.conflicts
                )
            }
        }
    }

    private suspend fun collectSchedulingGate() {
        combine(accountSessionState, localStore.state) { account, syncState ->
            val signedIn = account.session as? AccountSession.SignedIn
            SchedulingGate(
                token = signedIn?.token,
                restoring = account.isRestoring,
                verified = account.runtimeState.status == AccountRuntimeStatus.Verified,
                enabled = syncState?.enabled == true,
                profileKey = syncState?.profileKey
            )
        }.distinctUntilChanged().collect(::acceptSchedulingGate)
    }

    private fun acceptSchedulingGate(gate: SchedulingGate) {
        latestSchedulingGate = gate
        val generation = schedulingGeneration.incrementAndGet()
        synchronized(schedulingPreviewLock) {
            schedulingPreviewJob?.cancel()
            schedulingPreviewJob = null
        }
        mutableState.update { it.copy(schedulingEligible = false) }
        if (gate.restoring) return
        if (!gate.enabled || gate.token == null) {
            if (gate.enabled) scheduler.cancel()
            mutableState.update { it.copy(accountSwitchRequired = false) }
            return
        }
        if (!gate.canPreview) return

        launchSchedulingPreview(gate, generation)
    }

    private fun launchSchedulingPreview(gate: SchedulingGate, generation: Long) {
        synchronized(schedulingPreviewLock) {
            if (generation != schedulingGeneration.get() || schedulingPreviewJob?.isActive == true) return
            schedulingPreviewJob = applicationScope.launch {
                applySchedulingPreview(gate, generation)
            }
        }
    }

    private suspend fun applySchedulingPreview(gate: SchedulingGate, generation: Long) {
        when (val preview = coordinator.preview(requireNotNull(gate.token))) {
            is LedgerSyncOperationResult.Failure -> Unit
            is LedgerSyncOperationResult.Success -> {
                if (generation != schedulingGeneration.get() || gate != latestSchedulingGate) return
                val mismatch = gate.profileKey != null && gate.profileKey != preview.value.profileKey
                mutableState.update {
                    it.copy(
                        accountSwitchRequired = mismatch,
                        schedulingEligible = !mismatch
                    )
                }
                if (mismatch) {
                    scheduler.cancel()
                } else {
                    scheduler.ensurePeriodic()
                    scheduler.enqueueNow()
                }
            }
        }
    }

    private data class SchedulingGate(
        val token: String? = null,
        val restoring: Boolean = true,
        val verified: Boolean = false,
        val enabled: Boolean = false,
        val profileKey: String? = null
    ) {
        val canPreview: Boolean
            get() = !restoring && token != null && verified && enabled
    }

    private data class DurableStateSnapshot(
        val account: AccountSessionCoordinatorState,
        val syncState: LedgerSyncLocalState?,
        val outboxCount: Int,
        val conflicts: List<LedgerSyncConflict>
    )
}
