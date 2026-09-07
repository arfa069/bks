package com.bks.model.platform.billsync

import com.bks.model.platform.billsync.ALIPAY_PACKAGE_NAME
import com.bks.model.platform.billsync.CaptureRootSource
import com.bks.model.platform.billsync.collectReadablePageCapture
import com.bks.model.platform.billsync.collectReadableText
import com.bks.model.platform.billsync.MAX_CAPTURE_CHARACTERS
import com.bks.model.platform.billsync.MAX_CAPTURE_DEPTH
import com.bks.model.platform.billsync.MAX_CAPTURE_NODES
import com.bks.model.platform.billsync.selectCaptureRoot
import com.bks.model.platform.billsync.shouldCaptureEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityPageReaderTest {
    @Test
    fun readerReturnsEmptyCaptureForNodeWithoutReadableText() {
        val reader = AccessibilityPageReader(
            rootsProvider = { emptyList() },
            uptimeClock = { 0L }
        )

        val capture = reader.read(node())

        assertEquals("", capture.pageText)
        assertEquals(1, capture.visitedNodeCount)
        assertFalse(capture.nodeLimitReached)
    }

    @Test
    fun readerPropagatesExceptionalNodeReadToCoordinatorBoundary() {
        val expected = IllegalStateException("expected")
        val reader = AccessibilityPageReader(
            rootsProvider = { emptyList() },
            uptimeClock = { 0L },
            readablePageCollector = { throw expected }
        )

        assertSame(expected, assertThrows(IllegalStateException::class.java) { reader.read(node()) })
    }

    @Test
    fun captureRootSelectionUsesMatchingEventSourceWithoutActiveWindowLookup() {
        val eventRoot = node(packageName = PAYMENT_PACKAGE, windowId = 7)
        var activeWindowLookups = 0

        val eventSelection = selectCaptureRoot(
            eventRoot = eventRoot,
            eventWindowId = 7,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(node(packageName = "com.example.unrelated"))
            }
        )

        assertSame(eventRoot, eventSelection.root)
        assertTrue(eventSelection.activeRoots.isEmpty())
        assertEquals(CaptureRootSource.Event, eventSelection.source)
        assertEquals(0, activeWindowLookups)
    }

    @Test
    fun captureRootSelectionFallsBackToMatchingAlipayActiveWindow() {
        var activeWindowLookups = 0
        val unrelatedRoot = node(packageName = "com.example.unrelated", windowId = 7)
        val matchingActiveRoot = node(packageName = PAYMENT_PACKAGE, windowId = 8)

        val mismatchedEventSelection = selectCaptureRoot(
            eventRoot = unrelatedRoot,
            eventWindowId = 7,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(matchingActiveRoot)
            }
        )

        assertSame(matchingActiveRoot, mismatchedEventSelection.root)
        assertEquals(listOf(matchingActiveRoot), mismatchedEventSelection.activeRoots)
        assertEquals(CaptureRootSource.Active, mismatchedEventSelection.source)
        assertEquals(1, activeWindowLookups)

        val mismatchedWindowSelection = selectCaptureRoot(
            eventRoot = node(packageName = PAYMENT_PACKAGE, windowId = 6),
            eventWindowId = 7,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(matchingActiveRoot)
            }
        )

        assertSame(matchingActiveRoot, mismatchedWindowSelection.root)
        assertEquals(2, activeWindowLookups)

        val unrelatedActiveSelection = selectCaptureRoot(
            eventRoot = null,
            eventWindowId = 7,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(unrelatedRoot)
            }
        )

        assertNull(unrelatedActiveSelection.root)
        assertEquals(CaptureRootSource.None, unrelatedActiveSelection.source)
        assertEquals(3, activeWindowLookups)
    }

    @Test
    fun captureGateAcceptsOnlySupportedAlipayEventsAndPackageLessWindowsChanges() {
        val supportedTypes = listOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        supportedTypes.forEach { eventType ->
            assertTrue(shouldCaptureEvent(true, eventType, PAYMENT_PACKAGE))
        }
        assertTrue(shouldCaptureEvent(true, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null))
        assertFalse(shouldCaptureEvent(false, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null))
        assertFalse(shouldCaptureEvent(true, AccessibilityEvent.TYPE_VIEW_SCROLLED, PAYMENT_PACKAGE))
        assertFalse(shouldCaptureEvent(true, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, null))
        assertFalse(shouldCaptureEvent(true, AccessibilityEvent.TYPE_WINDOWS_CHANGED, OTHER_PACKAGE))
    }

    @Test
    fun readableTextUsesAllSemanticFieldsAndSkipsSensitiveNodes() {
        val root = node("root")
        shadowOf(root).addChild(
            node("text").apply {
                contentDescription = "description"
                hintText = "hint"
                paneTitle = "pane"
                tooltipText = "tooltip"
                stateDescription = "state"
            }
        )
        shadowOf(root).addChild(node("password").apply { isPassword = true })
        shadowOf(root).addChild(node("editable").apply { isEditable = true })
        val hiddenContainer = node("hidden").apply { isVisibleToUser = false }
        shadowOf(hiddenContainer).addChild(node("visible descendant"))
        shadowOf(root).addChild(hiddenContainer)
        shadowOf(root).addChild(node("text"))

        val capture = root.collectReadablePageCapture()
        assertEquals(
            "root\ntext\ndescription\nhint\npane\ntooltip\nstate\nvisible descendant",
            capture.pageText
        )
        assertEquals(7, capture.visitedNodeCount)
        assertFalse(capture.nodeLimitReached)
    }

    @Test
    fun readableTextUsesBreadthFirstTraversalWithinNodeDepthAndTextLimits() {
        val wideRoot = node("root")
        repeat(MAX_CAPTURE_NODES) { shadowOf(wideRoot).addChild(node("child-$it")) }
        val wideCapture = wideRoot.collectReadablePageCapture()
        assertEquals(MAX_CAPTURE_NODES, wideCapture.pageText.lineSequence().count())
        assertEquals(MAX_CAPTURE_NODES, wideCapture.visitedNodeCount)
        assertTrue(wideCapture.nodeLimitReached)

        val deepRoot = node("depth-0")
        var parent = deepRoot
        repeat(MAX_CAPTURE_DEPTH + 2) { index ->
            node("depth-${index + 1}").also {
                shadowOf(parent).addChild(it)
                parent = it
            }
        }
        val deepText = deepRoot.collectReadableText()
        assertTrue(deepText.contains("depth-$MAX_CAPTURE_DEPTH"))
        assertFalse(deepText.contains("depth-${MAX_CAPTURE_DEPTH + 1}"))

        val textRoot = node()
        repeat(40) { shadowOf(textRoot).addChild(node("$it-" + "x".repeat(1_000))) }
        assertTrue(textRoot.collectReadableText().length <= MAX_CAPTURE_CHARACTERS)

        val breadthFirstRoot = node()
        val denseFirstBranch = node()
        repeat(MAX_CAPTURE_NODES) { shadowOf(denseFirstBranch).addChild(node("decoy-$it")) }
        shadowOf(breadthFirstRoot).addChild(denseFirstBranch)
        shadowOf(breadthFirstRoot).addChild(node().apply { contentDescription = "支付成功￥4.99" })
        assertTrue(breadthFirstRoot.collectReadableText().contains("支付成功￥4.99"))
    }

    @Suppress("DEPRECATION")
    private fun node(
        text: String = "",
        packageName: String? = null,
        windowId: Int? = null
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isVisibleToUser = true
        this.text = text
        this.packageName = packageName
        windowId?.let { id ->
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(id)
            shadowOf(this).setAccessibilityWindowInfo(window)
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = ALIPAY_PACKAGE_NAME
        const val OTHER_PACKAGE = "com.example.pay"
    }
}
