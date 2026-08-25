package com.bks.feature.billsync

import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.bksAppContainer
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillSyncAccessibilityService : AssistsService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val acceptedWindows = AcceptedWindowMemory()
    private var automaticBookkeepingEnabled = false
    private val preferencesRepository by lazy { bksAppContainer.preferencesRepository }
    private val processor by lazy { bksAppContainer.createBillSyncCaptureProcessor() }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            preferencesRepository.userPreferences.collect { preferences ->
                automaticBookkeepingEnabled = preferences.automaticBookkeepingEnabled
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BillSyncServiceHealth.markServiceConnected(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
        try {
            captureCurrentWindow(event)
        } catch (error: Throwable) {
            BillSyncCaptureLog.failed(event.packageName?.toString(), event.windowId, error)
        }
    }

    private fun captureCurrentWindow(event: AccessibilityEvent) {
        BillSyncCaptureLog.event(event)
        val eventPackage = event.packageName?.toString()?.takeIf(String::isNotBlank)
        BillSyncCaptureLog.capture(eventPackage, event.windowId, automaticBookkeepingEnabled)
        if (!shouldCapturePackage(automaticBookkeepingEnabled, eventPackage, packageName)) return

        val allRoots = AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.AllWindows)
        val selection = selectCaptureRoot(allRoots, eventPackage, event.windowId) {
            AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
        }
        val root = selection.root
        BillSyncCaptureLog.roots(allRoots, selection.activeRoots, root, eventPackage, event.windowId)
        if (root == null) {
            BillSyncCaptureLog.ignored("root_missing")
            return
        }
        val activePackage = root.packageName?.toString().orEmpty()
        if (!shouldCapturePackage(captureEnabled = true, activePackage, packageName)) {
            BillSyncCaptureLog.ignored("own_package", "package=$activePackage")
            return
        }

        val pageText = root.collectReadableText()
        BillSyncCaptureLog.collected(activePackage, root.windowId, pageText)
        handoffSanitizedCapture(
            capture = SanitizedBillSyncCapture(activePackage, root.windowId, pageText),
            acceptedWindows = acceptedWindows,
            recognize = processor::recognize,
            persist = processor::persist,
            launch = { block -> serviceScope.launch { block() } }
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        BillSyncServiceHealth.markServiceConnected(this, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        BillSyncServiceHealth.markServiceConnected(this, false)
        super.onDestroy()
    }
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
            val result = withContext(Dispatchers.IO) { persist(recognized) }
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

    fun release(packageName: String, windowId: Int) {
        val key = packageName to windowId
        val existing = accepted[key] ?: return
        if (existing.persistenceInFlight) {
            existing.releaseRequested = true
        } else {
            accepted.remove(key)
        }
    }

    fun persistenceSucceeded(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = false)
    }

    fun persistenceFailed(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = true)
    }

    fun persistenceCancelled(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = false)
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
