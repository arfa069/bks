package com.bks.model.application.billsync

import com.bks.model.domain.billsync.BillBackfillGateway
import com.bks.model.domain.billsync.BillBackfillPhase
import com.bks.model.domain.billsync.BillBackfillState
import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit, time-bounded session, independent of the automatic-bookkeeping preference. */
class BillBackfillCoordinator(
    private val scope: CoroutineScope,
    private val persist: suspend (BillSyncResult) -> BillSyncResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionDurationMillis: Long = 10 * 60 * 1_000L
) : BillBackfillGateway {
    private val mutableState = MutableStateFlow(BillBackfillState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var timeout: Job? = null
    private val seen = mutableSetOf<String>()

    override suspend fun start(source: PaymentSource) = mutex.withLock {
        val current = state.value
        if (current.isBusy || source == PaymentSource.OTHER) return@withLock
        if (!current.serviceConnected) {
            mutableState.update { it.copy(message = "请先开启无障碍服务，再开始补录") }
            return@withLock
        }
        seen.clear()
        val id = current.sessionId + 1
        mutableState.value = BillBackfillState(
            sessionId = id,
            source = source,
            phase = BillBackfillPhase.Running,
            serviceConnected = true,
            message = "请切换到所选应用，逐笔打开账单详情",
            expiresAtMillis = clock() + sessionDurationMillis
        )
        timeout?.cancel()
        timeout = scope.launch {
            delay(sessionDurationMillis)
            finishSession(id, "本次补录已满 10 分钟，已自动结束")
        }
    }

    override suspend fun finish() {
        finishSession(state.value.sessionId, "补录已结束，已识别记录请在待确认中审核")
    }

    fun serviceConnectionChanged(connected: Boolean) {
        mutableState.update {
            it.copy(
                serviceConnected = connected,
                phase = if (!connected && it.isRunning) BillBackfillPhase.Finishing else it.phase
            )
        }
        if (!connected && state.value.isBusy) {
            val id = state.value.sessionId
            scope.launch { finishSession(id, "无障碍服务已断开，本次补录已结束") }
        }
    }

    fun accepts(id: Long, source: PaymentSource): Boolean = state.value.let {
        it.isRunning && it.serviceConnected && it.sessionId == id && it.source == source && clock() < it.expiresAtMillis
    }

    fun report(id: Long, message: String) {
        mutableState.update { if (it.sessionId == id && it.isRunning) it.copy(message = message) else it }
    }

    suspend fun submit(id: Long, entry: PendingEntry, usedOcr: Boolean) = mutex.withLock {
        if (!accepts(id, entry.source) || entry.id in seen) return@withLock
        try {
            withContext(NonCancellable) {
                val result = persist(BillSyncResult(recognized = true, createdEntries = listOf(entry)))
                seen += entry.id
                // A stop waits for this write; a committed row is included in the final counters.
                mutableState.update {
                    it.copy(
                        insertedCount = it.insertedCount + result.insertedCount,
                        duplicateCount = it.duplicateCount + if (result.insertedCount == 0) 1 else 0,
                        ocrCount = it.ocrCount + if (usedOcr) 1 else 0,
                        message = if (result.insertedCount > 0) "已加入待确认，可继续打开下一笔账单" else "该账单已处理，已跳过"
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            report(id, "保存失败，请稍后重新打开这笔账单")
        }
    }

    private suspend fun finishSession(id: Long, message: String) {
        mutableState.update {
            if (it.sessionId == id && it.isRunning) it.copy(phase = BillBackfillPhase.Finishing) else it
        }
        mutex.withLock {
            mutableState.update {
                if (it.sessionId == id && it.isBusy) it.copy(phase = BillBackfillPhase.Finished, message = message) else it
            }
        }
    }
}
