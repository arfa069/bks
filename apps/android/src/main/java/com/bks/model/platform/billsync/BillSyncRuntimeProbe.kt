package com.bks.model.platform.billsync

import com.bks.model.domain.diagnostics.DiagnosticRecorder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BillSyncRuntimeProbeHandle(
    val dispatcher: CoroutineDispatcher,
    private val closeDispatcher: () -> Unit = {}
) {
    fun close() = closeDispatcher()
}

internal data class BillSyncRuntimeProbeEnvironment(
    val uptimeClock: () -> Long,
    val elapsedRealtimeClock: () -> Long,
    val clock: () -> Long,
    val diagnosticRecorder: DiagnosticRecorder
)

internal class BillSyncRuntimeProbeSession(
    val scope: CoroutineScope,
    private val handle: BillSyncRuntimeProbeHandle,
    private val environment: BillSyncRuntimeProbeEnvironment,
    private val mainProbeInFlight: AtomicBoolean = AtomicBoolean(false)
) {
    fun start(
        sessionId: Long,
        mainScope: CoroutineScope,
        isSessionActive: () -> Boolean
    ) {
        scope.launch {
            var expectedUptimeMillis = environment.uptimeClock() + MAIN_PROBE_INTERVAL_MILLIS
            var expectedElapsedRealtimeMillis =
                environment.elapsedRealtimeClock() + MAIN_PROBE_INTERVAL_MILLIS
            var expectedAtEpochMillis = environment.clock() + MAIN_PROBE_INTERVAL_MILLIS
            while (true) {
                delay(MAIN_PROBE_INTERVAL_MILLIS)
                if (!isSessionActive()) return@launch
                val workerAtUptimeMillis = environment.uptimeClock()
                val workerAtElapsedRealtimeMillis = environment.elapsedRealtimeClock()
                val workerDelayMillis = (workerAtUptimeMillis - expectedUptimeMillis).coerceAtLeast(0L)
                val workerElapsedDelayMillis =
                    (workerAtElapsedRealtimeMillis - expectedElapsedRealtimeMillis).coerceAtLeast(0L)
                val ticket = BillSyncDispatchProbeTicket(
                    expectedAtEpochMillis = expectedAtEpochMillis,
                    expectedUptimeMillis = expectedUptimeMillis,
                    workerAtUptimeMillis = workerAtUptimeMillis,
                    workerDelayMillis = workerDelayMillis,
                    workerElapsedDelayMillis = workerElapsedDelayMillis
                )
                if (mainProbeInFlight.compareAndSet(false, true)) {
                    mainScope.launch {
                        try {
                            if (!isSessionActive()) return@launch
                            recordDispatchDelayIfNeeded(
                                sessionId = sessionId,
                                ticket = ticket,
                                uptimeClock = environment.uptimeClock,
                                clock = environment.clock,
                                diagnosticRecorder = environment.diagnosticRecorder
                            )
                        } finally {
                            mainProbeInFlight.set(false)
                        }
                    }
                }
                expectedUptimeMillis = workerAtUptimeMillis + MAIN_PROBE_INTERVAL_MILLIS
                expectedElapsedRealtimeMillis =
                    workerAtElapsedRealtimeMillis + MAIN_PROBE_INTERVAL_MILLIS
                expectedAtEpochMillis = environment.clock() + MAIN_PROBE_INTERVAL_MILLIS
            }
        }
    }

    fun close() {
        scope.cancel()
        handle.close()
    }
}

internal fun BillSyncRuntimeProbeHandle.toSession(
    environment: BillSyncRuntimeProbeEnvironment
): BillSyncRuntimeProbeSession =
    BillSyncRuntimeProbeSession(
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        handle = this,
        environment = environment
    )

internal fun createBillSyncRuntimeProbeHandle(): BillSyncRuntimeProbeHandle {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, RUNTIME_PROBE_THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    return BillSyncRuntimeProbeHandle(
        dispatcher = dispatcher,
        closeDispatcher = dispatcher::close
    )
}

@Suppress("LongParameterList")
private fun recordDispatchDelayIfNeeded(
    sessionId: Long,
    ticket: BillSyncDispatchProbeTicket,
    uptimeClock: () -> Long,
    clock: () -> Long,
    diagnosticRecorder: DiagnosticRecorder
) {
    val mainAtUptimeMillis = uptimeClock()
    val mainPostDelayMillis =
        (mainAtUptimeMillis - ticket.workerAtUptimeMillis).coerceAtLeast(0L)
    val outcome = classifyBillSyncDispatchDelay(
        workerDelayMillis = ticket.workerDelayMillis,
        workerElapsedDelayMillis = ticket.workerElapsedDelayMillis,
        mainPostDelayMillis = mainPostDelayMillis
    ) ?: return
    diagnosticRecorder.recordBillSyncDispatchDelay(
        BillSyncDispatchProbeDiagnostic(
            timestampEpochMillis = clock(),
            sessionId = sessionId,
            expectedAtEpochMillis = ticket.expectedAtEpochMillis,
            workerDelayMillis = ticket.workerDelayMillis,
            workerElapsedDelayMillis = ticket.workerElapsedDelayMillis,
            mainPostDelayMillis = mainPostDelayMillis,
            mainTotalDelayMillis =
                (mainAtUptimeMillis - ticket.expectedUptimeMillis).coerceAtLeast(0L),
            outcome = outcome
        )
    )
}

private data class BillSyncDispatchProbeTicket(
    val expectedAtEpochMillis: Long,
    val expectedUptimeMillis: Long,
    val workerAtUptimeMillis: Long,
    val workerDelayMillis: Long,
    val workerElapsedDelayMillis: Long
)

internal const val MAIN_PROBE_INTERVAL_MILLIS = 2_000L
private const val RUNTIME_PROBE_THREAD_NAME = "BksBillSyncProbe"
