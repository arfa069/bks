package com.bks.feature.billsync

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BillSyncCaptureCoordinator(
    context: Context,
    private val enabledIntent: Flow<Boolean>,
    private val processor: BillSyncCaptureOperations,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rootsProvider: (AssistsCore.NodeLookupScope) -> List<AccessibilityNodeInfo> =
        AssistsCore::getAccessibilityRootNodes
) {
    private val applicationContext = context.applicationContext
    private val ownPackageName = context.packageName
    private val acceptedWindows = AcceptedWindowMemory()
    private var enabledObserver: Job? = null
    private var captureSession: CoroutineScope? = null
    @Volatile
    private var automaticBookkeepingEnabled = false

    @Synchronized
    fun start() {
        if (enabledObserver != null) return
        enabledObserver = applicationScope.launch {
            enabledIntent.collect { automaticBookkeepingEnabled = it }
        }
    }

    @Synchronized
    fun stop() {
        enabledObserver?.cancel()
        enabledObserver = null
        automaticBookkeepingEnabled = false
        endCaptureSession()
    }

    @Synchronized
    fun onServiceConnected() {
        endCaptureSession()
        captureSession = applicationScope.childScope()
        BillSyncServiceHealth.markServiceConnected(applicationContext, true)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            BillSyncCaptureLog.event(event)
            captureCurrentWindow(
                CaptureTrigger(
                    packageName = event.packageName?.toString()?.takeIf(String::isNotBlank),
                    windowId = event.windowId
                )
            )
        } catch (error: Throwable) {
            BillSyncCaptureLog.failed(event.packageName?.toString(), event.windowId, error)
        }
    }

    fun onUnbind() {
        onServiceDestroyed()
    }

    @Synchronized
    fun onServiceDestroyed() {
        BillSyncServiceHealth.markServiceConnected(applicationContext, false)
        endCaptureSession()
    }

    private fun captureCurrentWindow(trigger: CaptureTrigger) {
        val captureEnabled = automaticBookkeepingEnabled
        BillSyncCaptureLog.capture(trigger.packageName, trigger.windowId, captureEnabled)
        if (!shouldCapturePackage(captureEnabled, trigger.packageName, ownPackageName)) return
        val session = synchronized(this) { captureSession } ?: return
        if (acceptedWindows.deferIfPersistenceInFlight(trigger)) {
            BillSyncCaptureLog.ignored(
                "capture_deferred",
                "package=${trigger.packageName} window=${trigger.windowId}"
            )
            return
        }

        val allRoots = rootsProvider(AssistsCore.NodeLookupScope.AllWindows)
        val selection = selectCaptureRoot(allRoots, trigger.packageName, trigger.windowId) {
            rootsProvider(AssistsCore.NodeLookupScope.ActiveWindow)
        }
        val root = selection.root
        BillSyncCaptureLog.roots(
            allRoots,
            selection.activeRoots,
            root,
            trigger.packageName,
            trigger.windowId
        )
        if (root == null) {
            BillSyncCaptureLog.ignored("root_missing")
            return
        }
        val activePackage = root.packageName?.toString().orEmpty()
        if (!shouldCapturePackage(captureEnabled = true, activePackage, ownPackageName)) {
            BillSyncCaptureLog.ignored("own_package", "package=$activePackage")
            return
        }

        val pageText = root.collectReadableText()
        BillSyncCaptureLog.collected(activePackage, root.windowId, pageText)
        synchronized(this) {
            if (captureSession !== session) return
            handoffSanitizedCapture(
                capture = SanitizedBillSyncCapture(activePackage, root.windowId, pageText),
                acceptedWindows = acceptedWindows,
                recognize = processor.recognize,
                persist = { result -> withContext(ioDispatcher) { processor.persist(result) } },
                launch = { block ->
                    session.launch {
                        block()
                        acceptedWindows
                            .takeDeferredCapture(activePackage, root.windowId)
                            ?.let(::captureCurrentWindow)
                    }
                }
            )
        }
    }

    @Synchronized
    private fun endCaptureSession() {
        captureSession?.cancel()
        captureSession = null
        acceptedWindows.clear()
    }
}

internal data class CaptureTrigger(
    val packageName: String?,
    val windowId: Int
)

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

internal fun shouldCapturePackage(
    captureEnabled: Boolean,
    candidatePackage: String?,
    ownPackage: String
): Boolean = captureEnabled && candidatePackage != ownPackage

internal fun handoffSanitizedCapture(
    capture: SanitizedBillSyncCapture,
    acceptedWindows: AcceptedWindowMemory,
    recognize: (String) -> BillSyncResult,
    persist: suspend (BillSyncResult) -> BillSyncResult,
    launch: (suspend () -> Unit) -> Unit
) {
    val recognized = recognize(capture.pageText)
    if (!recognized.recognized) {
        acceptedWindows.release(capture.packageName, capture.windowId)
        BillSyncCaptureLog.processed(recognized = false, createdEntries = 0)
        return
    }
    if (!acceptedWindows.acceptIfNew(capture.packageName, capture.windowId)) {
        BillSyncCaptureLog.ignored(
            "already_captured_window",
            "package=${capture.packageName} window=${capture.windowId}"
        )
        return
    }
    launch {
        try {
            val result = persist(recognized)
            acceptedWindows.persistenceSucceeded(capture.packageName, capture.windowId)
            BillSyncCaptureLog.processed(recognized = true, createdEntries = result.createdEntries.size)
        } catch (error: CancellationException) {
            acceptedWindows.persistenceCancelled(capture.packageName, capture.windowId)
            throw error
        } catch (error: Throwable) {
            acceptedWindows.persistenceFailed(capture.packageName, capture.windowId)
            BillSyncCaptureLog.failed(capture.packageName, capture.windowId, error)
        }
    }
}

internal data class CaptureRootSelection(
    val activeRoots: List<AccessibilityNodeInfo>,
    val root: AccessibilityNodeInfo?
)

internal fun selectCaptureRoot(
    allRoots: List<AccessibilityNodeInfo>,
    eventPackage: String?,
    eventWindowId: Int,
    activeRootsProvider: () -> List<AccessibilityNodeInfo>
): CaptureRootSelection {
    val eventRoot = selectEventRoot(allRoots, eventPackage, eventWindowId)
    val activeRoots = if (eventRoot == null) activeRootsProvider() else emptyList()
    return CaptureRootSelection(
        activeRoots = activeRoots,
        root = eventRoot ?: activeRoots.firstOrNull()
    )
}

internal fun selectEventRoot(
    roots: List<AccessibilityNodeInfo>,
    eventPackage: String?,
    eventWindowId: Int
): AccessibilityNodeInfo? = roots
    .firstOrNull { eventWindowId >= 0 && it.windowId == eventWindowId }
    ?: eventPackage?.let { expected ->
        roots.firstOrNull { it.packageName?.toString() == expected }
    }

internal class AcceptedWindowMemory {
    private val accepted = mutableMapOf<Pair<String, Int>, AcceptedWindow>()
    private val deferredCaptures = mutableMapOf<Pair<String, Int>, CaptureTrigger>()

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
    fun deferIfPersistenceInFlight(trigger: CaptureTrigger): Boolean {
        val packageName = trigger.packageName ?: return false
        if (trigger.windowId < 0) return false
        val key = packageName to trigger.windowId
        if (accepted[key]?.persistenceInFlight != true) return false
        deferredCaptures[key] = trigger
        return true
    }

    @Synchronized
    fun takeDeferredCapture(packageName: String, windowId: Int): CaptureTrigger? =
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

@Suppress("CyclomaticComplexMethod")
internal fun AccessibilityNodeInfo.collectReadableText(): String {
    val lines = linkedSetOf<String>()
    var visitedNodes = 0
    var collectedCharacters = 0

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
            if (visitedNodes + pendingNodes.size >= MAX_CAPTURE_NODES) break
            node.getChild(index)?.let { pendingNodes.add(it to depth + 1) }
        }
    }

    return lines.joinToString("\n")
}

internal const val MAX_CAPTURE_NODES = 512
internal const val MAX_CAPTURE_DEPTH = 24
internal const val MAX_CAPTURE_CHARACTERS = 16 * 1024
