@file:Suppress("TooManyFunctions")

package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.ledger.PaymentSource
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.domain.diagnostics.NoOpDiagnosticRecorder
import com.ven.assists.AssistsCore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val windowTracker = CaptureWindowTracker()
    private val pageReader = AccessibilityPageReader(rootsProvider, uptimeClock)
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
        val runtimeProbe = runCatching {
            runtimeProbeHandleFactory().toSession(
                BillSyncRuntimeProbeEnvironment(
                    uptimeClock = uptimeClock,
                    elapsedRealtimeClock = elapsedRealtimeClock,
                    clock = clock,
                    diagnosticRecorder = diagnosticRecorder
                )
            )
        }.getOrNull()
        val session = BillSyncCaptureSession(
            id = sessionGeneration,
            scope = applicationScope.childScope(),
            runtimeProbe = runtimeProbe
        )
        captureSession = session
        BillSyncServiceHealth.markServiceConnected(true)
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
        runtimeProbe?.start(
            sessionId = session.id,
            mainScope = session.scope,
            isSessionActive = {
                synchronized(this@BillSyncCaptureCoordinator) { captureSession === session }
            }
        )
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
        BillSyncServiceHealth.markServiceConnected(false)
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
        BillSyncServiceHealth.markServiceConnected(false)
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
        if (windowTracker.deferIfPersistenceInFlight(batch)) {
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
            val lookup = pageReader.lookup(trigger, eventSnapshot)
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
            readableCapture = pageReader.read(root)
            collectMillis = (uptimeClock() - collectStartedAt).coerceAtLeast(0L)
            val captured = requireNotNull(readableCapture)
            BillSyncCaptureLog.collected(activePackage, root.windowId, captured.pageText)
            recordWaveMetrics(outcome = null)
            val handedOff = synchronized(this) {
                if (captureSession !== session) return@synchronized false
                handoffSanitizedCapture(
                    capture = SanitizedBillSyncCapture(activePackage, root.windowId, captured.pageText),
                    windowTracker = windowTracker,
                    recognize = { pageText, paymentSource ->
                        recognizeWithTiming(pageText, paymentSource) { recognizeMillis = it }
                    },
                    persist = { result ->
                        persistWithTiming(result) { persistMillis = it }
                    },
                    launch = { block ->
                        session.scope.launch {
                            block()
                            windowTracker
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

    private fun recognizeWithTiming(
        pageText: String,
        paymentSource: PaymentSource,
        onMeasured: (Long) -> Unit
    ): BillSyncResult {
        val startedAt = uptimeClock()
        return try {
            processor.recognize(pageText, paymentSource)
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

    @Synchronized
    private fun endCaptureSession(reason: String) {
        flushCurrentWave()
        val endingSession = captureSession
        endingSession?.scope?.cancel()
        endingSession?.runtimeProbe?.close()
        captureSession = null
        pendingCaptures.clear()
        windowTracker.clear()
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

private data class ImmediateCapture(
    val session: BillSyncCaptureSession,
    val batch: BillSyncCaptureBatch,
    val eventSnapshot: AccessibilityEvent? = null
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

private fun CoroutineScope.childScope(): CoroutineScope {
    val parentContext = coroutineContext
    val childJob = SupervisorJob(parentContext[Job])
    val childContext: CoroutineContext = parentContext.minusKey(Job) + childJob
    return CoroutineScope(childContext)
}

internal fun shouldCaptureEvent(
    captureEnabled: Boolean,
    eventType: Int,
    candidatePackage: String?
): Boolean {
    if (!captureEnabled || eventType !in CAPTURE_EVENT_TYPES) return false
    return candidatePackage == ALIPAY_PACKAGE_NAME ||
        (candidatePackage == null && eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
}

internal const val CAPTURE_SETTLE_MILLIS = 500L
internal const val MAX_CAPTURE_INTERVAL_MILLIS = 1_000L
internal const val WAVE_INTERVAL_MILLIS = 1_000L
internal const val WECHAT_PACKAGE_NAME = "com.tencent.mm"

private val CAPTURE_EVENT_TYPES = setOf(
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
    AccessibilityEvent.TYPE_WINDOWS_CHANGED
)
