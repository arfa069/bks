@file:Suppress("TooManyFunctions")

package com.bks.model.platform.billsync

import android.view.accessibility.AccessibilityEvent
import com.bks.model.domain.diagnostics.BILL_SYNC_APP_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_CAPTURE_BATCH_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_COORDINATOR_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_RUNTIME_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_SERVICE_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_SETTING_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_WAVE_EVENT
import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
internal enum class BillSyncCaptureCause(val logValue: String) {
    Leading("leading"),
    Trailing("trailing"),
    Deadline("deadline"),
    Deferred("deferred")
}

internal data class BillSyncAccessibilityCallbackTiming(
    val callbackEnterAtEpochMillis: Long,
    val callbackEnterUptimeMillis: Long,
    val superReturnUptimeMillis: Long
) {
    val superDurationMillis: Long
        get() = (superReturnUptimeMillis - callbackEnterUptimeMillis).coerceAtLeast(0L)
}

internal data class BillSyncCaptureBatch(
    val trigger: CaptureTrigger,
    val timestampEpochMillis: Long,
    val eventTypeCounts: Map<Int, Int>,
    val sessionId: Long? = null,
    val captureCause: BillSyncCaptureCause = BillSyncCaptureCause.Trailing,
    val eventAtFirstEpochMillis: Long = timestampEpochMillis,
    val eventAtLastEpochMillis: Long = timestampEpochMillis,
    val receivedAtFirstEpochMillis: Long = timestampEpochMillis,
    val receivedAtLastEpochMillis: Long = timestampEpochMillis,
    val callbackEnterAtFirstEpochMillis: Long = receivedAtFirstEpochMillis,
    val callbackEnterAtLastEpochMillis: Long = receivedAtLastEpochMillis,
    val firstReceivedUptimeMillis: Long = 0L,
    val lastReceivedUptimeMillis: Long = firstReceivedUptimeMillis,
    val maxDeliveryDelayMillis: Long = 0L,
    val maxFrameworkDeliveryDelayMillis: Long = 0L,
    val maxSuperDurationMillis: Long = 0L,
    val maxCoordinatorDelayMillis: Long = 0L,
    val captureDueUptimeMillis: Long = firstReceivedUptimeMillis
) {
    fun merge(other: BillSyncCaptureBatch): BillSyncCaptureBatch {
        val mergedCounts = eventTypeCounts.toMutableMap()
        other.eventTypeCounts.forEach { (eventType, count) ->
            mergedCounts[eventType] = mergedCounts.getOrDefault(eventType, 0) + count
        }
        return copy(
            timestampEpochMillis = minOf(timestampEpochMillis, other.timestampEpochMillis),
            eventTypeCounts = mergedCounts,
            eventAtFirstEpochMillis = minOf(eventAtFirstEpochMillis, other.eventAtFirstEpochMillis),
            eventAtLastEpochMillis = maxOf(eventAtLastEpochMillis, other.eventAtLastEpochMillis),
            receivedAtFirstEpochMillis = minOf(
                receivedAtFirstEpochMillis,
                other.receivedAtFirstEpochMillis
            ),
            receivedAtLastEpochMillis = maxOf(
                receivedAtLastEpochMillis,
                other.receivedAtLastEpochMillis
            ),
            callbackEnterAtFirstEpochMillis = minOf(
                callbackEnterAtFirstEpochMillis,
                other.callbackEnterAtFirstEpochMillis
            ),
            callbackEnterAtLastEpochMillis = maxOf(
                callbackEnterAtLastEpochMillis,
                other.callbackEnterAtLastEpochMillis
            ),
            firstReceivedUptimeMillis = minPositive(
                firstReceivedUptimeMillis,
                other.firstReceivedUptimeMillis
            ),
            lastReceivedUptimeMillis = maxOf(
                lastReceivedUptimeMillis,
                other.lastReceivedUptimeMillis
            ),
            maxDeliveryDelayMillis = maxOf(
                maxDeliveryDelayMillis,
                other.maxDeliveryDelayMillis
            ),
            maxFrameworkDeliveryDelayMillis = maxOf(
                maxFrameworkDeliveryDelayMillis,
                other.maxFrameworkDeliveryDelayMillis
            ),
            maxSuperDurationMillis = maxOf(
                maxSuperDurationMillis,
                other.maxSuperDurationMillis
            ),
            maxCoordinatorDelayMillis = maxOf(
                maxCoordinatorDelayMillis,
                other.maxCoordinatorDelayMillis
            )
        )
    }

    fun asDeferred(captureDueUptimeMillis: Long): BillSyncCaptureBatch = copy(
        captureCause = BillSyncCaptureCause.Deferred,
        captureDueUptimeMillis = captureDueUptimeMillis
    )
}

internal sealed interface BillSyncCaptureOutcome {
    data class Inserted(val insertedCount: Int) : BillSyncCaptureOutcome
    data class NotInserted(val reason: String) : BillSyncCaptureOutcome
    data class Failed(val reason: String) : BillSyncCaptureOutcome
}

internal data class BillSyncCaptureDiagnosticDetails(
    val captureAtEpochMillis: Long,
    val captureEndAtEpochMillis: Long,
    val queueDelayMillis: Long,
    val capturedPackageName: String?,
    val capturedWindowId: Int?,
    val rootSelection: String,
    val allRootCount: Int,
    val activeRootCount: Int,
    val lookupMillis: Long,
    val collectMillis: Long? = null,
    val recognizeMillis: Long? = null,
    val persistMillis: Long? = null,
    val captureMillis: Long
)

internal data class BillSyncWaveDiagnostic(
    val timestampEpochMillis: Long,
    val sessionId: Long?,
    val allCallbacks: Int,
    val eligibleCallbacks: Int,
    val filteredCallbacks: Int,
    val uniqueWindows: Int,
    val captures: Int,
    val leadingCaptures: Int,
    val trailingCaptures: Int,
    val deadlineCaptures: Int,
    val deferredCaptures: Int,
    val rootMissing: Int,
    val ownPackage: Int,
    val nodesVisited: Int,
    val totalLookupMillis: Long,
    val maxQueueMillis: Long,
    val maxFrameworkDeliveryMillis: Long,
    val maxSuperDurationMillis: Long,
    val maxCoordinatorDelayMillis: Long
)

internal class BillSyncWaveAccumulator(
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

internal data class BillSyncDispatchProbeDiagnostic(
    val timestampEpochMillis: Long,
    val sessionId: Long?,
    val expectedAtEpochMillis: Long,
    val workerDelayMillis: Long,
    val workerElapsedDelayMillis: Long,
    val mainPostDelayMillis: Long,
    val mainTotalDelayMillis: Long,
    val outcome: String
)

internal fun formatAccessibilityEventCounts(eventTypeCounts: Map<Int, Int>): String =
    eventTypeCounts.toSortedMap().entries.joinToString(",") { (eventType, count) ->
        val name = AccessibilityEvent.eventTypeToString(eventType).removePrefix("TYPE_")
        "$name×$count"
    }

internal fun DiagnosticRecorder.recordBillSyncCaptureBatch(
    batch: BillSyncCaptureBatch,
    readableCapture: ReadablePageCapture?,
    details: BillSyncCaptureDiagnosticDetails,
    outcome: BillSyncCaptureOutcome
) {
    runCatching {
        val result = outcome.toDiagnosticCaptureResult()
        val pageText = readableCapture?.pageText.orEmpty()
            .takeIf { details.capturedPackageName == ALIPAY_PACKAGE_NAME }
            .orEmpty()
        record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    timestampEpochMillis = details.captureAtEpochMillis,
                    level = result.level,
                    component = DiagnosticComponent.AutomaticBookkeeping,
                    event = BILL_SYNC_CAPTURE_BATCH_EVENT,
                    sessionId = batch.sessionId?.toString(),
                    source = DiagnosticSource.Accessibility,
                    outcome = result.outcome,
                    reason = result.reason,
                    durationMillis = details.captureMillis,
                    triggeredEvents = formatAccessibilityEventCounts(batch.eventTypeCounts),
                    nodeCount = readableCapture?.visitedNodeCount,
                    nodeLimitReached = readableCapture?.nodeLimitReached == true,
                    insertedCount = result.insertedCount,
                    attributes = captureAttributes(batch, details)
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(DiagnosticSensitiveField.PageText to pageText)
                )
            )
        )
    }
}

@Suppress("LongParameterList")
internal fun DiagnosticRecorder.recordBillSyncCoordinatorEvent(
    eventName: String,
    timestampEpochMillis: Long,
    sessionId: Long?,
    pid: Int,
    enabled: Boolean,
    reason: String? = null
) = recordBillSyncEvent(
    internalEvent = BILL_SYNC_COORDINATOR_EVENT,
    stage = "coordinator",
    eventName = eventName,
    timestampEpochMillis = timestampEpochMillis,
    sessionId = sessionId,
    attributes = linkedMapOf<String, String>().apply {
        put("pid", pid.toString())
        put("enabled", enabled.toString())
        reason?.let { put("reason", it) }
    }
)

@Suppress("LongParameterList")
internal fun DiagnosticRecorder.recordBillSyncServiceEvent(
    eventName: String,
    timestampEpochMillis: Long,
    sessionId: Long?,
    pid: Int,
    enabled: Boolean,
    processStartElapsedMillis: Long? = null,
    reason: String? = null
) = recordBillSyncEvent(
    internalEvent = BILL_SYNC_SERVICE_EVENT,
    stage = "service",
    eventName = eventName,
    timestampEpochMillis = timestampEpochMillis,
    sessionId = sessionId,
    attributes = linkedMapOf<String, String>().apply {
        put("pid", pid.toString())
        put("enabled", enabled.toString())
        processStartElapsedMillis?.let { put("processStartElapsedMs", it.toString()) }
        reason?.let { put("reason", it) }
    }
)

internal fun DiagnosticRecorder.recordBillSyncSettingEvent(
    enabled: Boolean,
    timestampEpochMillis: Long,
    sessionId: Long?
) = recordBillSyncEvent(
    internalEvent = BILL_SYNC_SETTING_EVENT,
    stage = "setting",
    eventName = if (enabled) "AUTOMATIC_BOOKKEEPING_ENABLED" else "AUTOMATIC_BOOKKEEPING_DISABLED",
    timestampEpochMillis = timestampEpochMillis,
    sessionId = sessionId,
    attributes = linkedMapOf("enabled" to enabled.toString())
)

@Suppress("LongParameterList")
internal fun DiagnosticRecorder.recordBillSyncAppEvent(
    eventName: String,
    timestampEpochMillis: Long,
    sessionId: Long?,
    serviceConnected: Boolean,
    enabled: Boolean,
    lastEventAtEpochMillis: Long?,
    lastEventPackage: String?,
    eventSilenceMillis: Long?
) = recordBillSyncEvent(
    internalEvent = BILL_SYNC_APP_EVENT,
    stage = "app",
    eventName = eventName,
    timestampEpochMillis = timestampEpochMillis,
    sessionId = sessionId,
    source = DiagnosticSource.System,
    attributes = linkedMapOf<String, String>().apply {
        put("service", if (serviceConnected) "connected" else "disconnected")
        put("enabled", enabled.toString())
        put("lastEventAt", lastEventAtEpochMillis?.toString() ?: "-")
        put("lastEventPkg", lastEventPackage ?: "-")
        put("eventSilenceMs", eventSilenceMillis?.toString() ?: "-")
    }
)

internal fun DiagnosticRecorder.recordBillSyncWave(diagnostic: BillSyncWaveDiagnostic) =
    recordBillSyncEvent(
        internalEvent = BILL_SYNC_WAVE_EVENT,
        stage = "wave",
        eventName = "TYPE_ALL_MASK",
        timestampEpochMillis = diagnostic.timestampEpochMillis,
        sessionId = diagnostic.sessionId,
        outcome = if (
            diagnostic.allCallbacks >= EVENT_FLOOD_CALLBACK_THRESHOLD ||
            diagnostic.maxQueueMillis >= MAIN_DELAY_WARNING_MILLIS
        ) {
            "event_flood"
        } else {
            "ok"
        },
        attributes = linkedMapOf(
            "allCallbacks" to diagnostic.allCallbacks.toString(),
            "eligibleCallbacks" to diagnostic.eligibleCallbacks.toString(),
            "filteredCallbacks" to diagnostic.filteredCallbacks.toString(),
            "uniqueWindows" to diagnostic.uniqueWindows.toString(),
            "captures" to diagnostic.captures.toString(),
            "leading" to diagnostic.leadingCaptures.toString(),
            "trailing" to diagnostic.trailingCaptures.toString(),
            "deadline" to diagnostic.deadlineCaptures.toString(),
            "deferred" to diagnostic.deferredCaptures.toString(),
            "rootMissing" to diagnostic.rootMissing.toString(),
            "ownPackage" to diagnostic.ownPackage.toString(),
            "nodesVisited" to diagnostic.nodesVisited.toString(),
            "totalLookupMs" to diagnostic.totalLookupMillis.toString(),
            "maxQueueMs" to diagnostic.maxQueueMillis.toString(),
            "maxFrameworkDeliveryMs" to diagnostic.maxFrameworkDeliveryMillis.toString(),
            "maxSuperDurationMs" to diagnostic.maxSuperDurationMillis.toString(),
            "maxCoordinatorDelayMs" to diagnostic.maxCoordinatorDelayMillis.toString()
        )
    )

internal fun DiagnosticRecorder.recordBillSyncDispatchDelay(
    diagnostic: BillSyncDispatchProbeDiagnostic
) = recordBillSyncEvent(
    internalEvent = BILL_SYNC_RUNTIME_EVENT,
    stage = "runtime",
    eventName = "DISPATCH_DELAY",
    timestampEpochMillis = diagnostic.timestampEpochMillis,
    sessionId = diagnostic.sessionId,
    level = DiagnosticLevel.Warning,
    outcome = diagnostic.outcome,
    attributes = linkedMapOf(
        "expectedAt" to diagnostic.expectedAtEpochMillis.toString(),
        "actualAt" to diagnostic.timestampEpochMillis.toString(),
        "workerDelayMs" to diagnostic.workerDelayMillis.toString(),
        "workerElapsedDelayMs" to diagnostic.workerElapsedDelayMillis.toString(),
        "mainPostDelayMs" to diagnostic.mainPostDelayMillis.toString(),
        "mainTotalDelayMs" to diagnostic.mainTotalDelayMillis.toString(),
        "processWidePaused" to (diagnostic.outcome == DISPATCH_OUTCOME_PROCESS_PAUSED).toString()
    )
)

internal fun classifyBillSyncDispatchDelay(
    workerDelayMillis: Long,
    workerElapsedDelayMillis: Long,
    mainPostDelayMillis: Long
): String? = when {
    workerDelayMillis >= MAIN_DELAY_WARNING_MILLIS -> DISPATCH_OUTCOME_PROCESS_PAUSED
    mainPostDelayMillis >= MAIN_DELAY_WARNING_MILLIS -> DISPATCH_OUTCOME_MAIN_THREAD_PAUSED
    workerElapsedDelayMillis >= MAIN_DELAY_WARNING_MILLIS -> DISPATCH_OUTCOME_DEVICE_SLEEP
    else -> null
}

@Suppress("LongParameterList")
private fun DiagnosticRecorder.recordBillSyncEvent(
    internalEvent: String,
    stage: String,
    eventName: String,
    timestampEpochMillis: Long,
    sessionId: Long?,
    source: DiagnosticSource = DiagnosticSource.Accessibility,
    level: DiagnosticLevel = DiagnosticLevel.Info,
    outcome: String = "ok",
    reason: String? = null,
    attributes: LinkedHashMap<String, String>
) {
    runCatching {
        record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    timestampEpochMillis = timestampEpochMillis,
                    level = level,
                    component = DiagnosticComponent.AutomaticBookkeeping,
                    event = internalEvent,
                    sessionId = sessionId?.toString(),
                    source = source,
                    outcome = outcome,
                    reason = reason,
                    attributes = linkedMapOf(
                        "stage" to stage,
                        "eventName" to eventName
                    ) + attributes
                )
            )
        )
    }
}

private fun captureAttributes(
    batch: BillSyncCaptureBatch,
    details: BillSyncCaptureDiagnosticDetails
): Map<String, String> = linkedMapOf(
    "stage" to "capture",
    "trigger" to batch.captureCause.logValue,
    "eventAtFirst" to batch.eventAtFirstEpochMillis.toString(),
    "eventAtLast" to batch.eventAtLastEpochMillis.toString(),
    "callbackEnterAtFirst" to batch.callbackEnterAtFirstEpochMillis.toString(),
    "callbackEnterAtLast" to batch.callbackEnterAtLastEpochMillis.toString(),
    "receivedAtFirst" to batch.receivedAtFirstEpochMillis.toString(),
    "receivedAtLast" to batch.receivedAtLastEpochMillis.toString(),
    "deliveryDelayMs" to batch.maxDeliveryDelayMillis.toString(),
    "frameworkDeliveryMs" to batch.maxFrameworkDeliveryDelayMillis.toString(),
    "superDurationMs" to batch.maxSuperDurationMillis.toString(),
    "coordinatorDelayMs" to batch.maxCoordinatorDelayMillis.toString(),
    "queueDelayMs" to details.queueDelayMillis.toString(),
    "triggerPkg" to (batch.trigger.packageName ?: "-"),
    "triggerWin" to batch.trigger.windowId.toString(),
    "rootPkg" to (details.capturedPackageName ?: "-"),
    "rootWin" to (details.capturedWindowId?.toString() ?: "-"),
    "rootBy" to details.rootSelection,
    "allRoots" to details.allRootCount.toString(),
    "activeRoots" to details.activeRootCount.toString(),
    "lookupMs" to details.lookupMillis.toString(),
    "collectMs" to (details.collectMillis?.toString() ?: "-"),
    "recognizeMs" to (details.recognizeMillis?.toString() ?: "-"),
    "persistMs" to (details.persistMillis?.toString() ?: "-"),
    "captureAt" to details.captureAtEpochMillis.toString(),
    "captureEndAt" to details.captureEndAtEpochMillis.toString(),
    "captureMs" to details.captureMillis.toString()
)

private fun BillSyncCaptureOutcome.toDiagnosticCaptureResult(): DiagnosticCaptureResult = when (this) {
    is BillSyncCaptureOutcome.Inserted -> DiagnosticCaptureResult(
        level = DiagnosticLevel.Info,
        outcome = "inserted",
        reason = null,
        insertedCount = insertedCount
    )
    is BillSyncCaptureOutcome.NotInserted -> DiagnosticCaptureResult(
        level = DiagnosticLevel.Info,
        outcome = "not_inserted",
        reason = reason,
        insertedCount = 0
    )
    is BillSyncCaptureOutcome.Failed -> DiagnosticCaptureResult(
        level = DiagnosticLevel.Warning,
        outcome = "failed",
        reason = reason,
        insertedCount = null
    )
}

private fun minPositive(first: Long, second: Long): Long = when {
    first <= 0L -> second
    second <= 0L -> first
    else -> minOf(first, second)
}

private data class DiagnosticCaptureResult(
    val level: DiagnosticLevel,
    val outcome: String,
    val reason: String?,
    val insertedCount: Int?
)

internal const val ALIPAY_PACKAGE_NAME = "com.eg.android.AlipayGphone"
internal const val MAIN_DELAY_WARNING_MILLIS = 2_000L
internal const val DISPATCH_OUTCOME_PROCESS_PAUSED = "process_paused"
internal const val DISPATCH_OUTCOME_MAIN_THREAD_PAUSED = "main_thread_paused"
internal const val DISPATCH_OUTCOME_DEVICE_SLEEP = "device_sleep"
private const val EVENT_FLOOD_CALLBACK_THRESHOLD = 50
