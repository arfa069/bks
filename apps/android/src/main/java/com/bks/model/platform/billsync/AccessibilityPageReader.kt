package com.bks.model.platform.billsync

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore

internal class AccessibilityPageReader(
    private val rootsProvider: (AssistsCore.NodeLookupScope) -> List<AccessibilityNodeInfo>,
    private val uptimeClock: () -> Long,
    private val readablePageCollector: (AccessibilityNodeInfo) -> ReadablePageCapture =
        AccessibilityNodeInfo::collectReadablePageCapture
) {
    fun lookup(
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

    fun read(root: AccessibilityNodeInfo): ReadablePageCapture = readablePageCollector(root)
}

internal data class TimedCaptureRootLookup(
    val selection: CaptureRootSelection,
    val durationMillis: Long
)

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
