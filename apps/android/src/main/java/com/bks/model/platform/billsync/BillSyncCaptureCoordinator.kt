@file:Suppress("TooManyFunctions")

package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncResult
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.domain.diagnostics.NoOpDiagnosticRecorder
import com.ven.assists.AssistsCore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
internal class BillSyncCaptureCoordinator(
    context: Context,
    private val enabledIntent: Flow<Boolean>,
    private val processor: BillSyncCaptureOperations,
    private val applicationScope: CoroutineScope,
    private val diagnosticRecorder: DiagnosticRecorder = NoOpDiagnosticRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uptimeClock: () -> Long = SystemClock::uptimeMillis,
    private val elapsedRealtimeClock: () -> Long = SystemClock::elapsedRealtime,
    private val pidProvider: () -> Int = Process::myPid,
    processStartElapsedProvider: () -> Long = Process::getStartElapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val runtimeProbeHandleFactory: () -> BillSyncRuntimeProbeHandle =
        ::createBillSyncRuntimeProbeHandle,
    private val rootsProvider: (AssistsCore.NodeLookupScope) -> List<AccessibilityNodeInfo> =
        AssistsCore::getAccessibilityRootNodes
) {
    private val applicationContext = context.applicationContext
    private val processStartElapsedMillis = runCatching(processStartElapsedProvider).getOrNull()
    private val acceptedWindows = AcceptedWindowMemory()
    private val pendingCaptures = mutableMapOf<CaptureTrigger, PendingCapture>()
    private var enabledObserver: Job? = null
    private var captureSession: BillSyncCaptureSession? = null
    private var sessionGeneration = 0L
    private var currentWave: BillSyncWaveAccumulator? = null
    private var lastEventAtEpochMillis: Long? = null
    private var lastEventReceivedAtEpochMillis: Long? = null
    private var lastEventPackageName: String? = null
    @Volatile
    private var automaticBookkeepingEnabled = false

    @Synchronized
    fun start() {
        if (enabledObserver != null) return
        enabledObserver = applicationScope.launch {
            enabledIntent.collect { enabled ->
                val changed = automaticBookkeepingEnabled != enabled
                automaticBookkeepingEnabled = enabled
                if (changed) {
                    diagnosticRecorder.recordBillSyncSettingEvent(
                        enabled = enabled,
                        timestampEpochMillis = clock(),
                        sessionId = synchronized(this@BillSyncCaptureCoordinator) {
                            captureSession?.id
                        }
                    )
                }
            }
        }
        diagnosticRecorder.recordBillSyncCoordinatorEvent(
            eventName = "STARTED",
            timestampEpochMillis = clock(),
            sessionId = captureSession?.id,
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled
        )
    }

    @Synchronized
    fun stop() {
        diagnosticRecorder.recordBillSyncCoordinatorEvent(
            eventName = "STOPPED",
            timestampEpochMillis = clock(),
            sessionId = captureSession?.id,
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled
        )
        enabledObserver?.cancel()
        enabledObserver = null
        automaticBookkeepingEnabled = false
        endCaptureSession("coordinator_stopped")
    }

    @Synchronized
    fun onServiceConnected() {
        endCaptureSession("service_reconnected")
        sessionGeneration += 1
        val runtimeProbe = runCatching { runtimeProbeHandleFactory().toSession() }.getOrNull()
        captureSession = BillSyncCaptureSession(
            id = sessionGeneration,
            scope = applicationScope.childScope(),
            runtimeProbe = runtimeProbe
        )
        BillSyncServiceHealth.markServiceConnected(applicationContext, true)
        diagnosticRecorder.recordBillSyncServiceEvent(
            eventName = "CONNECTED",
            timestampEpochMillis = clock(),
            sessionId = sessionGeneration,
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled,
            processStartElapsedMillis = processStartElapsedMillis
        )
        diagnosticRecorder.recordBillSyncServiceEvent(
            eventName = "SESSION_STARTED",
            timestampEpochMillis = clock(),
            sessionId = sessionGeneration,
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled,
            processStartElapsedMillis = processStartElapsedMillis
        )
        startDispatchDelayProbe(requireNotNull(captureSession))
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val callbackEnterAtEpochMillis = clock()
        val callbackEnterUptimeMillis = uptimeClock()
        onAccessibilityEvent(
            event = event,
            callbackTiming = BillSyncAccessibilityCallbackTiming(
                callbackEnterAtEpochMillis = callbackEnterAtEpochMillis,
                callbackEnterUptimeMillis = callbackEnterUptimeMillis,
                superReturnUptimeMillis = callbackEnterUptimeMillis
            )
        )
    }

    fun onAccessibilityEvent(
        event: AccessibilityEvent,
        callbackTiming: BillSyncAccessibilityCallbackTiming
    ) {
        try {
            BillSyncCaptureLog.event(event)
            val receipt = event.toReceipt(
                callbackTiming = callbackTiming,
                coordinatorReceivedAtEpochMillis = clock(),
                coordinatorReceivedUptimeMillis = uptimeClock()
            )
            val trigger = CaptureTrigger(
                packageName = event.packageName?.toString()?.takeIf(String::isNotBlank),
                windowId = event.windowId
            )
            val captureEnabled = automaticBookkeepingEnabled
            BillSyncCaptureLog.capture(trigger.packageName, trigger.windowId, captureEnabled)
            val captureEligible = shouldCaptureEvent(
                captureEnabled,
                receipt.eventType,
                trigger.packageName
            )
            if (captureEnabled) rememberEvent(trigger, receipt, captureEligible)
            if (!captureEligible) return
            scheduleCapture(
                trigger = trigger,
                receipt = receipt,
                event = event
            )
        } catch (error: Throwable) {
            BillSyncCaptureLog.failed(event.packageName?.toString(), event.windowId, error)
        }
    }

    fun onUnbind() {
        diagnosticRecorder.recordBillSyncServiceEvent(
            eventName = "UNBIND",
            timestampEpochMillis = clock(),
            sessionId = synchronized(this) { captureSession?.id ?: sessionGeneration.takeIf { it > 0L } },
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled,
            processStartElapsedMillis = processStartElapsedMillis
        )
        BillSyncServiceHealth.markServiceConnected(applicationContext, false)
        endCaptureSession("unbind")
    }

    @Synchronized
    fun onServiceDestroyed() {
        diagnosticRecorder.recordBillSyncServiceEvent(
            eventName = "DESTROYED",
            timestampEpochMillis = clock(),
            sessionId = captureSession?.id ?: sessionGeneration.takeIf { it > 0L },
            pid = pidProvider(),
            enabled = automaticBookkeepingEnabled,
            processStartElapsedMillis = processStartElapsedMillis
        )
        BillSyncServiceHealth.markServiceConnected(applicationContext, false)
        endCaptureSession("destroyed")
    }

    @Synchronized
    fun healthSnapshot(): BillSyncRuntimeHealthSnapshot = BillSyncRuntimeHealthSnapshot(
        sessionId = captureSession?.id ?: sessionGeneration.takeIf { it > 0L },
        serviceConnected = captureSession != null,
        automaticBookkeepingEnabled = automaticBookkeepingEnabled,
        lastEventAtEpochMillis = lastEventAtEpochMillis,
        lastEventPackageName = lastEventPackageName,
        eventSilenceMillis = lastEventReceivedAtEpochMillis?.let { receivedAt ->
            (clock() - receivedAt).coerceAtLeast(0L)
        }
    )

    private fun scheduleCapture(
        trigger: CaptureTrigger,
        receipt: BillSyncEventReceipt,
        event: AccessibilityEvent
    ) {
        val immediateCapture = synchronized(this) {
            val session = captureSession ?: return
            val existing = pendingCaptures[trigger]
            if (existing != null) {
                existing.addEvent(receipt)
                existing.trailingJob?.cancel()
                existing.trailingJob = session.scope.launch {
                    delay(CAPTURE_SETTLE_MILLIS)
                    completePendingCapture(
                        trigger = trigger,
                        pending = existing,
                        cause = BillSyncCaptureCause.Trailing,
                        captureDueUptimeMillis = receipt.receivedUptimeMillis + CAPTURE_SETTLE_MILLIS
                    )
                }
                return@synchronized null
            }

            val eventSnapshot = event.copyForCapture()
            val pending = PendingCapture(receipt)
            pendingCaptures[trigger] = pending
            pending.trailingJob = session.scope.launch {
                delay(CAPTURE_SETTLE_MILLIS)
                completePendingCapture(
                    trigger = trigger,
                    pending = pending,
                    cause = BillSyncCaptureCause.Trailing,
                    captureDueUptimeMillis = receipt.receivedUptimeMillis + CAPTURE_SETTLE_MILLIS
                )
            }
            pending.deadlineJob = session.scope.launch {
                delay(MAX_CAPTURE_INTERVAL_MILLIS)
                completePendingCapture(
                    trigger = trigger,
                    pending = pending,
                    cause = BillSyncCaptureCause.Deadline,
                    captureDueUptimeMillis = receipt.receivedUptimeMillis + MAX_CAPTURE_INTERVAL_MILLIS
                )
            }
            ImmediateCapture(
                session = session,
                batch = pending.toBatch(
                    trigger = trigger,
                    sessionId = session.id,
                    cause = BillSyncCaptureCause.Leading,
                    captureDueUptimeMillis = receipt.receivedUptimeMillis
                ),
                eventSnapshot = eventSnapshot
            )
        }
        immediateCapture?.let(::submitCapture)
    }

    private fun completePendingCapture(
        trigger: CaptureTrigger,
        pending: PendingCapture,
        cause: BillSyncCaptureCause,
        captureDueUptimeMillis: Long
    ) {
        val capture = synchronized(this) {
            if (pendingCaptures[trigger] !== pending) return
            pendingCaptures.remove(trigger)
            if (cause == BillSyncCaptureCause.Deadline) {
                pending.trailingJob?.cancel()
            } else {
                pending.deadlineJob?.cancel()
            }
            val session = captureSession ?: return
            ImmediateCapture(
                session = session,
                batch = pending.toBatch(trigger, session.id, cause, captureDueUptimeMillis)
            )
        }
        submitCapture(capture)
    }

    private fun submitCapture(capture: ImmediateCapture) {
        val job = capture.session.scope.launch(captureDispatcher) {
            yield()
            captureBatch(capture.batch, capture.eventSnapshot)
        }
        capture.eventSnapshot?.let { snapshot ->
            job.invokeOnCompletion { snapshot.recycleAfterCapture() }
        }
    }

    private fun captureBatch(
        batch: BillSyncCaptureBatch,
        eventSnapshot: AccessibilityEvent? = null
    ) {
        try {
            captureCurrentWindow(batch, eventSnapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BillSyncCaptureLog.failed(
                batch.trigger.packageName,
                batch.trigger.windowId,
                error
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun captureCurrentWindow(
        batch: BillSyncCaptureBatch,
        eventSnapshot: AccessibilityEvent?
    ) {
        val trigger = batch.trigger
        val captureEnabled = automaticBookkeepingEnabled
        val captureAllowed = batch.eventTypeCounts.keys.any { eventType ->
            shouldCaptureEvent(captureEnabled, eventType, trigger.packageName)
        }
        if (!captureAllowed) return
        val session = synchronized(this) {
            captureSession?.takeIf { batch.sessionId == null || it.id == batch.sessionId }
        } ?: return
        if (acceptedWindows.deferIfPersistenceInFlight(batch)) {
            BillSyncCaptureLog.ignored(
                "capture_deferred",
                "package=${trigger.packageName} window=${trigger.windowId}"
            )
            return
        }

        val captureAtEpochMillis = clock()
        val captureAtUptimeMillis = uptimeClock()
        val queueDelayMillis = (captureAtUptimeMillis - batch.captureDueUptimeMillis)
            .coerceAtLeast(0L)
        recordWaveCaptureStarted(
            sessionId = batch.sessionId,
            cause = batch.captureCause,
            queueDelayMillis = queueDelayMillis,
            timestampEpochMillis = captureAtEpochMillis
        )

        val allRootCount = 0
        var activeRootCount = 0
        var lookupMillis = 0L
        var collectMillis: Long? = null
        var recognizeMillis: Long? = null
        var persistMillis: Long? = null
        var capturedPackageName: String? = null
        var capturedWindowId: Int? = null
        var rootSelection = CaptureRootSource.None.logValue
        var readableCapture: ReadablePageCapture? = null
        var waveMetricsRecorded = false

        fun recordWaveMetrics(outcome: BillSyncCaptureOutcome?) {
            if (waveMetricsRecorded) return
            recordWaveCaptureCompleted(
                sessionId = batch.sessionId,
                outcome = outcome,
                readableCapture = readableCapture,
                lookupMillis = lookupMillis
            )
            waveMetricsRecorded = true
        }

        fun recordOutcome(outcome: BillSyncCaptureOutcome) {
            val details = BillSyncCaptureDiagnosticDetails(
                captureAtEpochMillis = captureAtEpochMillis,
                captureEndAtEpochMillis = clock(),
                queueDelayMillis = queueDelayMillis,
                capturedPackageName = capturedPackageName,
                capturedWindowId = capturedWindowId,
                rootSelection = rootSelection,
                allRootCount = allRootCount,
                activeRootCount = activeRootCount,
                lookupMillis = lookupMillis,
                collectMillis = collectMillis,
                recognizeMillis = recognizeMillis,
                persistMillis = persistMillis,
                captureMillis = (uptimeClock() - captureAtUptimeMillis).coerceAtLeast(0L)
            )
            recordWaveMetrics(outcome)
            diagnosticRecorder.recordBillSyncCaptureBatch(
                batch = batch,
                readableCapture = readableCapture,
                details = details,
                outcome = outcome
            )
        }

        try {
            val lookup = lookupCaptureRoot(trigger, eventSnapshot)
            val selection = lookup.selection
            lookupMillis = lookup.durationMillis
            activeRootCount = selection.activeRoots.size
            rootSelection = selection.source.logValue
            val root = selection.root
            capturedPackageName = root?.packageName?.toString()?.takeIf(String::isNotBlank)
            capturedWindowId = root?.windowId
            if (root == null) {
                BillSyncCaptureLog.ignored("root_missing")
                recordOutcome(BillSyncCaptureOutcome.Failed("root_missing"))
                return
            }
            val activePackage = root.packageName?.toString().orEmpty()
            if (activePackage != ALIPAY_PACKAGE_NAME) {
                BillSyncCaptureLog.ignored("unexpected_package", "package=$activePackage")
                recordOutcome(BillSyncCaptureOutcome.Failed("unexpected_package"))
                return
            }

            val collectStartedAt = uptimeClock()
            readableCapture = root.collectReadablePageCapture()
            collectMillis = (uptimeClock() - collectStartedAt).coerceAtLeast(0L)
            val captured = requireNotNull(readableCapture)
            BillSyncCaptureLog.collected(activePackage, root.windowId, captured.pageText)
            recordWaveMetrics(outcome = null)
            val handedOff = synchronized(this) {
                if (captureSession !== session) return@synchronized false
                handoffSanitizedCapture(
                    capture = SanitizedBillSyncCapture(activePackage, root.windowId, captured.pageText),
                    acceptedWindows = acceptedWindows,
                    recognize = { pageText ->
                        recognizeWithTiming(pageText) { recognizeMillis = it }
                    },
                    persist = { result ->
                        persistWithTiming(result) { persistMillis = it }
                    },
                    launch = { block ->
                        session.scope.launch {
                            block()
                            acceptedWindows
                                .takeDeferredCapture(activePackage, root.windowId)
                                ?.asDeferred(uptimeClock())
                                ?.let { deferred ->
                                    submitCapture(
                                        ImmediateCapture(
                                            session = session,
                                            batch = deferred
                                        )
                                    )
                                }
                        }
                    },
                    onOutcome = ::recordOutcome
                )
                true
            }
            if (!handedOff) {
                recordOutcome(BillSyncCaptureOutcome.Failed("session_ended"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordOutcome(BillSyncCaptureOutcome.Failed("capture_failed"))
            BillSyncCaptureLog.failed(trigger.packageName, trigger.windowId, error)
        }
    }

    private fun lookupCaptureRoot(
        trigger: CaptureTrigger,
        eventSnapshot: AccessibilityEvent?
    ): TimedCaptureRootLookup {
        val startedAt = uptimeClock()
        val eventRoot = runCatching { eventSnapshot?.source }.getOrNull()
        val selection = selectCaptureRoot(eventRoot, trigger.windowId) {
            rootsProvider(AssistsCore.NodeLookupScope.ActiveWindow)
        }
        BillSyncCaptureLog.roots(
            emptyList(),
            selection.activeRoots,
            selection.root,
            trigger.packageName,
            trigger.windowId
        )
        return TimedCaptureRootLookup(
            selection = selection,
            durationMillis = (uptimeClock() - startedAt).coerceAtLeast(0L)
        )
    }

    private fun recognizeWithTiming(
        pageText: String,
        onMeasured: (Long) -> Unit
    ): BillSyncResult {
        val startedAt = uptimeClock()
        return try {
            processor.recognize(pageText)
        } finally {
            onMeasured((uptimeClock() - startedAt).coerceAtLeast(0L))
        }
    }

    private suspend fun persistWithTiming(
        result: BillSyncResult,
        onMeasured: (Long) -> Unit
    ): BillSyncResult {
        val startedAt = uptimeClock()
        return try {
            withContext(ioDispatcher) { processor.persist(result) }
        } finally {
            onMeasured((uptimeClock() - startedAt).coerceAtLeast(0L))
        }
    }

    @Synchronized
    private fun rememberEvent(
        trigger: CaptureTrigger,
        receipt: BillSyncEventReceipt,
        captureEligible: Boolean
    ) {
        if (captureEligible) {
            lastEventAtEpochMillis = receipt.eventAtEpochMillis
            lastEventReceivedAtEpochMillis = receipt.receivedAtEpochMillis
            lastEventPackageName = trigger.packageName
        }
        val session = captureSession ?: return
        val wave = waveFor(receipt.receivedAtEpochMillis, session.id) ?: return
        wave.allCallbacks += 1
        wave.maxFrameworkDeliveryMillis = maxOf(
            wave.maxFrameworkDeliveryMillis,
            receipt.frameworkDeliveryDelayMillis
        )
        wave.maxSuperDurationMillis = maxOf(
            wave.maxSuperDurationMillis,
            receipt.superDurationMillis
        )
        wave.maxCoordinatorDelayMillis = maxOf(
            wave.maxCoordinatorDelayMillis,
            receipt.coordinatorDelayMillis
        )
        if (captureEligible) {
            wave.eligibleCallbacks += 1
            wave.uniqueWindows += trigger
        } else {
            wave.filteredCallbacks += 1
        }
    }

    @Synchronized
    private fun recordWaveCaptureStarted(
        sessionId: Long?,
        cause: BillSyncCaptureCause,
        queueDelayMillis: Long,
        timestampEpochMillis: Long
    ) {
        val wave = waveFor(timestampEpochMillis, sessionId) ?: return
        wave.captures += 1
        wave.maxQueueMillis = maxOf(wave.maxQueueMillis, queueDelayMillis)
        when (cause) {
            BillSyncCaptureCause.Leading -> wave.leadingCaptures += 1
            BillSyncCaptureCause.Trailing -> wave.trailingCaptures += 1
            BillSyncCaptureCause.Deadline -> wave.deadlineCaptures += 1
            BillSyncCaptureCause.Deferred -> wave.deferredCaptures += 1
        }
    }

    @Synchronized
    private fun recordWaveCaptureCompleted(
        sessionId: Long?,
        outcome: BillSyncCaptureOutcome?,
        readableCapture: ReadablePageCapture?,
        lookupMillis: Long
    ) {
        val wave = currentWave?.takeIf { it.sessionId == sessionId } ?: return
        if (outcome is BillSyncCaptureOutcome.Failed) {
            when (outcome.reason) {
                "root_missing" -> wave.rootMissing += 1
                "own_package" -> wave.ownPackage += 1
            }
        }
        wave.nodesVisited += readableCapture?.visitedNodeCount ?: 0
        wave.totalLookupMillis += lookupMillis
    }

    @Synchronized
    private fun waveFor(timestampEpochMillis: Long, sessionId: Long?): BillSyncWaveAccumulator? {
        val activeSession = captureSession ?: return null
        if (sessionId != null && activeSession.id != sessionId) return null
        val existing = currentWave
        if (existing != null && timestampEpochMillis - existing.timestampEpochMillis >= WAVE_INTERVAL_MILLIS) {
            flushCurrentWave()
        }
        currentWave?.let { return it }
        return BillSyncWaveAccumulator(
            timestampEpochMillis = timestampEpochMillis,
            sessionId = activeSession.id
        ).also { wave ->
            currentWave = wave
            activeSession.scope.launch {
                delay(WAVE_INTERVAL_MILLIS)
                synchronized(this@BillSyncCaptureCoordinator) {
                    if (currentWave === wave) flushCurrentWave()
                }
            }
        }
    }

    @Synchronized
    private fun flushCurrentWave() {
        val wave = currentWave ?: return
        currentWave = null
        diagnosticRecorder.recordBillSyncWave(wave.toDiagnostic())
    }

    private fun startDispatchDelayProbe(session: BillSyncCaptureSession) {
        val runtimeProbe = session.runtimeProbe ?: return
        runtimeProbe.scope.launch {
            var expectedUptimeMillis = uptimeClock() + MAIN_PROBE_INTERVAL_MILLIS
            var expectedElapsedRealtimeMillis = elapsedRealtimeClock() + MAIN_PROBE_INTERVAL_MILLIS
            var expectedAtEpochMillis = clock() + MAIN_PROBE_INTERVAL_MILLIS
            while (true) {
                delay(MAIN_PROBE_INTERVAL_MILLIS)
                if (synchronized(this@BillSyncCaptureCoordinator) { captureSession !== session }) return@launch
                val workerAtUptimeMillis = uptimeClock()
                val workerAtElapsedRealtimeMillis = elapsedRealtimeClock()
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
                if (runtimeProbe.mainProbeInFlight.compareAndSet(false, true)) {
                    session.scope.launch {
                        try {
                            val sessionEnded = synchronized(this@BillSyncCaptureCoordinator) {
                                captureSession !== session
                            }
                            if (sessionEnded) return@launch
                            recordDispatchDelayIfNeeded(session.id, ticket)
                        } finally {
                            runtimeProbe.mainProbeInFlight.set(false)
                        }
                    }
                }
                expectedUptimeMillis = workerAtUptimeMillis + MAIN_PROBE_INTERVAL_MILLIS
                expectedElapsedRealtimeMillis =
                    workerAtElapsedRealtimeMillis + MAIN_PROBE_INTERVAL_MILLIS
                expectedAtEpochMillis = clock() + MAIN_PROBE_INTERVAL_MILLIS
            }
        }
    }

    private fun recordDispatchDelayIfNeeded(
        sessionId: Long,
        ticket: BillSyncDispatchProbeTicket
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

    @Synchronized
    private fun endCaptureSession(reason: String) {
        flushCurrentWave()
        val endingSession = captureSession
        endingSession?.scope?.cancel()
        endingSession?.runtimeProbe?.close()
        captureSession = null
        pendingCaptures.clear()
        acceptedWindows.clear()
        endingSession?.let { session ->
            diagnosticRecorder.recordBillSyncServiceEvent(
                eventName = "SESSION_ENDED",
                timestampEpochMillis = clock(),
                sessionId = session.id,
                pid = pidProvider(),
                enabled = automaticBookkeepingEnabled,
                processStartElapsedMillis = processStartElapsedMillis,
                reason = reason
            )
        }
    }
}

internal data class CaptureTrigger(
    val packageName: String?,
    val windowId: Int
)

internal data class BillSyncRuntimeHealthSnapshot(
    val sessionId: Long?,
    val serviceConnected: Boolean,
    val automaticBookkeepingEnabled: Boolean,
    val lastEventAtEpochMillis: Long?,
    val lastEventPackageName: String?,
    val eventSilenceMillis: Long?
)

private data class BillSyncEventReceipt(
    val eventType: Int,
    val eventAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val receivedUptimeMillis: Long,
    val callbackEnterAtEpochMillis: Long,
    val deliveryDelayMillis: Long,
    val frameworkDeliveryDelayMillis: Long,
    val superDurationMillis: Long,
    val coordinatorDelayMillis: Long
)

private fun AccessibilityEvent.toReceipt(
    callbackTiming: BillSyncAccessibilityCallbackTiming,
    coordinatorReceivedAtEpochMillis: Long,
    coordinatorReceivedUptimeMillis: Long
): BillSyncEventReceipt {
    val eventUptimeMillis = eventTime.takeIf { it > 0L }
        ?: callbackTiming.callbackEnterUptimeMillis
    val frameworkDeliveryDelayMillis =
        (callbackTiming.callbackEnterUptimeMillis - eventUptimeMillis).coerceAtLeast(0L)
    val deliveryDelayMillis =
        (coordinatorReceivedUptimeMillis - eventUptimeMillis).coerceAtLeast(0L)
    return BillSyncEventReceipt(
        eventType = eventType,
        eventAtEpochMillis = callbackTiming.callbackEnterAtEpochMillis -
            frameworkDeliveryDelayMillis,
        receivedAtEpochMillis = coordinatorReceivedAtEpochMillis,
        receivedUptimeMillis = coordinatorReceivedUptimeMillis,
        callbackEnterAtEpochMillis = callbackTiming.callbackEnterAtEpochMillis,
        deliveryDelayMillis = deliveryDelayMillis,
        frameworkDeliveryDelayMillis = frameworkDeliveryDelayMillis,
        superDurationMillis = callbackTiming.superDurationMillis,
        coordinatorDelayMillis =
            (coordinatorReceivedUptimeMillis - callbackTiming.superReturnUptimeMillis)
                .coerceAtLeast(0L)
    )
}

@Suppress("DEPRECATION")
private fun AccessibilityEvent.copyForCapture(): AccessibilityEvent = AccessibilityEvent.obtain(this)

@Suppress("DEPRECATION")
private fun AccessibilityEvent.recycleAfterCapture() = recycle()

private data class BillSyncCaptureSession(
    val id: Long,
    val scope: CoroutineScope,
    val runtimeProbe: BillSyncRuntimeProbeSession?
)

internal class BillSyncRuntimeProbeHandle(
    val dispatcher: CoroutineDispatcher,
    private val closeDispatcher: () -> Unit = {}
) {
    fun close() = closeDispatcher()
}

private class BillSyncRuntimeProbeSession(
    val scope: CoroutineScope,
    private val handle: BillSyncRuntimeProbeHandle,
    val mainProbeInFlight: AtomicBoolean = AtomicBoolean(false)
) {
    fun close() {
        scope.cancel()
        handle.close()
    }
}

private fun BillSyncRuntimeProbeHandle.toSession(): BillSyncRuntimeProbeSession =
    BillSyncRuntimeProbeSession(
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        handle = this
    )

private fun createBillSyncRuntimeProbeHandle(): BillSyncRuntimeProbeHandle {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, RUNTIME_PROBE_THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    return BillSyncRuntimeProbeHandle(
        dispatcher = dispatcher,
        closeDispatcher = dispatcher::close
    )
}

private data class BillSyncDispatchProbeTicket(
    val expectedAtEpochMillis: Long,
    val expectedUptimeMillis: Long,
    val workerAtUptimeMillis: Long,
    val workerDelayMillis: Long,
    val workerElapsedDelayMillis: Long
)

private data class ImmediateCapture(
    val session: BillSyncCaptureSession,
    val batch: BillSyncCaptureBatch,
    val eventSnapshot: AccessibilityEvent? = null
)

private data class TimedCaptureRootLookup(
    val selection: CaptureRootSelection,
    val durationMillis: Long
)

private class PendingCapture(
    private val firstReceipt: BillSyncEventReceipt
) {
    private val eventTypeCounts = mutableMapOf<Int, Int>()
    private var lastReceipt = firstReceipt
    private var maxDeliveryDelayMillis = firstReceipt.deliveryDelayMillis
    private var maxFrameworkDeliveryDelayMillis = firstReceipt.frameworkDeliveryDelayMillis
    private var maxSuperDurationMillis = firstReceipt.superDurationMillis
    private var maxCoordinatorDelayMillis = firstReceipt.coordinatorDelayMillis
    var trailingJob: Job? = null
    var deadlineJob: Job? = null

    init {
        addEvent(firstReceipt)
    }

    fun addEvent(receipt: BillSyncEventReceipt) {
        lastReceipt = receipt
        maxDeliveryDelayMillis = maxOf(maxDeliveryDelayMillis, receipt.deliveryDelayMillis)
        maxFrameworkDeliveryDelayMillis = maxOf(
            maxFrameworkDeliveryDelayMillis,
            receipt.frameworkDeliveryDelayMillis
        )
        maxSuperDurationMillis = maxOf(maxSuperDurationMillis, receipt.superDurationMillis)
        maxCoordinatorDelayMillis = maxOf(
            maxCoordinatorDelayMillis,
            receipt.coordinatorDelayMillis
        )
        eventTypeCounts[receipt.eventType] = eventTypeCounts.getOrDefault(receipt.eventType, 0) + 1
    }

    fun toBatch(
        trigger: CaptureTrigger,
        sessionId: Long,
        cause: BillSyncCaptureCause,
        captureDueUptimeMillis: Long
    ): BillSyncCaptureBatch = BillSyncCaptureBatch(
        trigger = trigger,
        timestampEpochMillis = firstReceipt.receivedAtEpochMillis,
        eventTypeCounts = eventTypeCounts.toMap(),
        sessionId = sessionId,
        captureCause = cause,
        eventAtFirstEpochMillis = firstReceipt.eventAtEpochMillis,
        eventAtLastEpochMillis = lastReceipt.eventAtEpochMillis,
        receivedAtFirstEpochMillis = firstReceipt.receivedAtEpochMillis,
        receivedAtLastEpochMillis = lastReceipt.receivedAtEpochMillis,
        callbackEnterAtFirstEpochMillis = firstReceipt.callbackEnterAtEpochMillis,
        callbackEnterAtLastEpochMillis = lastReceipt.callbackEnterAtEpochMillis,
        firstReceivedUptimeMillis = firstReceipt.receivedUptimeMillis,
        lastReceivedUptimeMillis = lastReceipt.receivedUptimeMillis,
        maxDeliveryDelayMillis = maxDeliveryDelayMillis,
        maxFrameworkDeliveryDelayMillis = maxFrameworkDeliveryDelayMillis,
        maxSuperDurationMillis = maxSuperDurationMillis,
        maxCoordinatorDelayMillis = maxCoordinatorDelayMillis,
        captureDueUptimeMillis = captureDueUptimeMillis
    )
}

private class BillSyncWaveAccumulator(
    val timestampEpochMillis: Long,
    val sessionId: Long
) {
    var allCallbacks: Int = 0
    var eligibleCallbacks: Int = 0
    var filteredCallbacks: Int = 0
    val uniqueWindows = mutableSetOf<CaptureTrigger>()
    var captures: Int = 0
    var leadingCaptures: Int = 0
    var trailingCaptures: Int = 0
    var deadlineCaptures: Int = 0
    var deferredCaptures: Int = 0
    var rootMissing: Int = 0
    var ownPackage: Int = 0
    var nodesVisited: Int = 0
    var totalLookupMillis: Long = 0L
    var maxQueueMillis: Long = 0L
    var maxFrameworkDeliveryMillis: Long = 0L
    var maxSuperDurationMillis: Long = 0L
    var maxCoordinatorDelayMillis: Long = 0L

    fun toDiagnostic(): BillSyncWaveDiagnostic = BillSyncWaveDiagnostic(
        timestampEpochMillis = timestampEpochMillis,
        sessionId = sessionId,
        allCallbacks = allCallbacks,
        eligibleCallbacks = eligibleCallbacks,
        filteredCallbacks = filteredCallbacks,
        uniqueWindows = uniqueWindows.size,
        captures = captures,
        leadingCaptures = leadingCaptures,
        trailingCaptures = trailingCaptures,
        deadlineCaptures = deadlineCaptures,
        deferredCaptures = deferredCaptures,
        rootMissing = rootMissing,
        ownPackage = ownPackage,
        nodesVisited = nodesVisited,
        totalLookupMillis = totalLookupMillis,
        maxQueueMillis = maxQueueMillis,
        maxFrameworkDeliveryMillis = maxFrameworkDeliveryMillis,
        maxSuperDurationMillis = maxSuperDurationMillis,
        maxCoordinatorDelayMillis = maxCoordinatorDelayMillis
    )
}

internal class BillSyncCaptureOperations(
    val recognize: (String) -> BillSyncResult,
    val persist: suspend (BillSyncResult) -> BillSyncResult
)

private fun CoroutineScope.childScope(): CoroutineScope {
    val parentContext = coroutineContext
    val childJob = SupervisorJob(parentContext[Job])
    val childContext: CoroutineContext = parentContext.minusKey(Job) + childJob
    return CoroutineScope(childContext)
}

internal data class SanitizedBillSyncCapture(
    val packageName: String,
    val windowId: Int,
    val pageText: String
)

internal fun shouldCaptureEvent(
    captureEnabled: Boolean,
    eventType: Int,
    candidatePackage: String?
): Boolean {
    if (!captureEnabled || eventType !in CAPTURE_EVENT_TYPES) return false
    return candidatePackage == ALIPAY_PACKAGE_NAME ||
        (candidatePackage == null && eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
}

@Suppress("LongParameterList")
internal fun handoffSanitizedCapture(
    capture: SanitizedBillSyncCapture,
    acceptedWindows: AcceptedWindowMemory,
    recognize: (String) -> BillSyncResult,
    persist: suspend (BillSyncResult) -> BillSyncResult,
    launch: (suspend () -> Unit) -> Unit,
    onOutcome: (BillSyncCaptureOutcome) -> Unit = {}
) {
    val recognized = recognize(capture.pageText)
    if (!recognized.recognized) {
        acceptedWindows.release(capture.packageName, capture.windowId)
        BillSyncCaptureLog.processed(recognized = false, insertedCount = 0)
        notifyCaptureOutcome(
            onOutcome,
            BillSyncCaptureOutcome.Failed(
                recognized.failureReason?.code ?: "page_not_recognized"
            )
        )
        return
    }
    if (!acceptedWindows.acceptIfNew(capture.packageName, capture.windowId)) {
        BillSyncCaptureLog.ignored(
            "already_captured_window",
            "package=${capture.packageName} window=${capture.windowId}"
        )
        notifyCaptureOutcome(
            onOutcome,
            BillSyncCaptureOutcome.NotInserted("already_captured_window")
        )
        return
    }
    launch {
        try {
            val result = persist(recognized)
            acceptedWindows.persistenceSucceeded(capture.packageName, capture.windowId)
            BillSyncCaptureLog.processed(recognized = true, insertedCount = result.insertedCount)
            notifyCaptureOutcome(
                onOutcome,
                if (result.insertedCount > 0) {
                    BillSyncCaptureOutcome.Inserted(result.insertedCount)
                } else {
                    BillSyncCaptureOutcome.NotInserted("duplicate_not_inserted")
                }
            )
        } catch (error: CancellationException) {
            acceptedWindows.persistenceCancelled(capture.packageName, capture.windowId)
            notifyCaptureOutcome(onOutcome, BillSyncCaptureOutcome.Failed("session_ended"))
            throw error
        } catch (error: Throwable) {
            acceptedWindows.persistenceFailed(capture.packageName, capture.windowId)
            BillSyncCaptureLog.failed(capture.packageName, capture.windowId, error)
            notifyCaptureOutcome(onOutcome, BillSyncCaptureOutcome.Failed("persistence_failed"))
        }
    }
}

private fun notifyCaptureOutcome(
    onOutcome: (BillSyncCaptureOutcome) -> Unit,
    outcome: BillSyncCaptureOutcome
) {
    runCatching { onOutcome(outcome) }
}

internal data class CaptureRootSelection(
    val activeRoots: List<AccessibilityNodeInfo>,
    val root: AccessibilityNodeInfo?,
    val source: CaptureRootSource
)

internal enum class CaptureRootSource(val logValue: String) {
    Event("event"),
    Active("active"),
    None("none")
}

internal fun selectCaptureRoot(
    eventRoot: AccessibilityNodeInfo?,
    eventWindowId: Int,
    activeRootsProvider: () -> List<AccessibilityNodeInfo>
): CaptureRootSelection {
    val matchingEventRoot = eventRoot?.takeIf { root ->
        root.packageName?.toString() == ALIPAY_PACKAGE_NAME &&
            (eventWindowId < 0 || root.windowId == eventWindowId)
    }
    val activeRoots = if (matchingEventRoot == null) activeRootsProvider() else emptyList()
    val alipayActiveRoots = activeRoots.filter { root ->
        root.packageName?.toString() == ALIPAY_PACKAGE_NAME
    }
    val activeRoot = alipayActiveRoots.firstOrNull { root ->
        eventWindowId >= 0 && root.windowId == eventWindowId
    } ?: alipayActiveRoots.firstOrNull()
    return CaptureRootSelection(
        activeRoots = activeRoots,
        root = matchingEventRoot ?: activeRoot,
        source = when {
            matchingEventRoot != null -> CaptureRootSource.Event
            activeRoot != null -> CaptureRootSource.Active
            else -> CaptureRootSource.None
        }
    )
}

internal class AcceptedWindowMemory {
    private val accepted = mutableMapOf<Pair<String, Int>, AcceptedWindow>()
    private val deferredCaptures = mutableMapOf<Pair<String, Int>, BillSyncCaptureBatch>()

    @Synchronized
    fun acceptIfNew(packageName: String, windowId: Int): Boolean {
        val key = packageName to windowId
        val existing = accepted[key]
        if (existing != null) {
            existing.releaseRequested = false
            return false
        }
        accepted[key] = AcceptedWindow(persistenceInFlight = true)
        return true
    }

    @Synchronized
    fun deferIfPersistenceInFlight(batch: BillSyncCaptureBatch): Boolean {
        val trigger = batch.trigger
        val packageName = trigger.packageName ?: return false
        if (trigger.windowId < 0) return false
        val key = packageName to trigger.windowId
        if (accepted[key]?.persistenceInFlight != true) return false
        deferredCaptures[key] = deferredCaptures[key]?.merge(batch) ?: batch
        return true
    }

    @Synchronized
    fun takeDeferredCapture(packageName: String, windowId: Int): BillSyncCaptureBatch? =
        deferredCaptures.remove(packageName to windowId)

    @Synchronized
    fun release(packageName: String, windowId: Int) {
        val key = packageName to windowId
        val existing = accepted[key] ?: return
        if (existing.persistenceInFlight) {
            existing.releaseRequested = true
        } else {
            accepted.remove(key)
        }
    }

    @Synchronized
    fun persistenceSucceeded(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = false)
    }

    @Synchronized
    fun persistenceFailed(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = true)
    }

    @Synchronized
    fun persistenceCancelled(packageName: String, windowId: Int) {
        deferredCaptures.remove(packageName to windowId)
        completePersistence(packageName, windowId, remove = false)
    }

    @Synchronized
    fun clear() {
        accepted.clear()
        deferredCaptures.clear()
    }

    private fun completePersistence(packageName: String, windowId: Int, remove: Boolean) {
        val key = packageName to windowId
        val existing = accepted[key] ?: return
        existing.persistenceInFlight = false
        if (remove || existing.releaseRequested) accepted.remove(key)
    }

    private data class AcceptedWindow(
        var persistenceInFlight: Boolean,
        var releaseRequested: Boolean = false
    )
}

internal data class ReadablePageCapture(
    val pageText: String,
    val visitedNodeCount: Int,
    val nodeLimitReached: Boolean
)

internal fun AccessibilityNodeInfo.collectReadableText(): String = collectReadablePageCapture().pageText

@Suppress("CyclomaticComplexMethod")
internal fun AccessibilityNodeInfo.collectReadablePageCapture(): ReadablePageCapture {
    val lines = linkedSetOf<String>()
    var visitedNodes = 0
    var collectedCharacters = 0
    var nodeLimitReached = false

    fun add(value: CharSequence?) {
        val line = value?.toString()?.trim()?.takeIf(String::isNotBlank) ?: return
        if (line in lines) return
        val addedCharacters = line.length + if (lines.isEmpty()) 0 else 1
        if (collectedCharacters + addedCharacters > MAX_CAPTURE_CHARACTERS) return
        lines += line
        collectedCharacters += addedCharacters
    }

    val pendingNodes = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    pendingNodes.add(this to 0)
    while (pendingNodes.isNotEmpty() && visitedNodes < MAX_CAPTURE_NODES) {
        val (node, depth) = pendingNodes.removeFirst()
        visitedNodes += 1
        if (node.isPassword || node.isEditable) continue
        if (node.isVisibleToUser) {
            add(node.text)
            add(node.contentDescription)
            add(node.hintText)
            add(node.paneTitle)
            add(node.tooltipText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(node.stateDescription)
        }
        if (depth == MAX_CAPTURE_DEPTH || collectedCharacters >= MAX_CAPTURE_CHARACTERS) continue
        for (index in 0 until node.childCount) {
            if (visitedNodes + pendingNodes.size >= MAX_CAPTURE_NODES) {
                nodeLimitReached = true
                break
            }
            node.getChild(index)?.let { pendingNodes.add(it to depth + 1) }
        }
    }

    return ReadablePageCapture(
        pageText = lines.joinToString("\n"),
        visitedNodeCount = visitedNodes,
        nodeLimitReached = nodeLimitReached || pendingNodes.isNotEmpty()
    )
}

internal const val MAX_CAPTURE_NODES = 512
internal const val MAX_CAPTURE_DEPTH = 24
internal const val MAX_CAPTURE_CHARACTERS = 16 * 1024
internal const val CAPTURE_SETTLE_MILLIS = 500L
internal const val MAX_CAPTURE_INTERVAL_MILLIS = 1_000L
internal const val WAVE_INTERVAL_MILLIS = 1_000L
internal const val MAIN_PROBE_INTERVAL_MILLIS = 2_000L
private const val RUNTIME_PROBE_THREAD_NAME = "BksBillSyncProbe"

private val CAPTURE_EVENT_TYPES = setOf(
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
    AccessibilityEvent.TYPE_WINDOWS_CHANGED
)
