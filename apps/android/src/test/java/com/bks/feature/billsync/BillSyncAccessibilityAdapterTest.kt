package com.bks.feature.billsync

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncAccessibilityAdapterTest {
    @Test
    fun eventRootSelectionPrefersWindowThenPackageAndReturnsNullForNoMatch() {
        val samePackageWrongWindow = node(packageName = PAYMENT_PACKAGE, windowId = 6)
        val matchingWindow = node(packageName = "com.example.other", windowId = 7)
        val packageFallback = node(packageName = PAYMENT_PACKAGE, windowId = 8)

        assertSame(
            matchingWindow,
            selectEventRoot(
                roots = listOf(samePackageWrongWindow, matchingWindow),
                eventPackage = PAYMENT_PACKAGE,
                eventWindowId = 7
            )
        )
        assertSame(
            packageFallback,
            selectEventRoot(
                roots = listOf(node(packageName = "com.miui.home"), packageFallback),
                eventPackage = PAYMENT_PACKAGE,
                eventWindowId = -1
            )
        )
        assertNull(
            selectEventRoot(
                roots = listOf(node(packageName = "com.miui.home")),
                eventPackage = PAYMENT_PACKAGE,
                eventWindowId = -1
            )
        )
    }

    @Test
    fun captureRootSelectionReadsActiveWindowOnlyWhenEventRootIsMissing() {
        val eventRoot = node(packageName = PAYMENT_PACKAGE, windowId = 7)
        var activeWindowLookups = 0

        val eventSelection = selectCaptureRoot(
            allRoots = listOf(eventRoot),
            eventPackage = PAYMENT_PACKAGE,
            eventWindowId = 7,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(node(packageName = "com.example.unrelated"))
            }
        )

        assertSame(eventRoot, eventSelection.root)
        assertTrue(eventSelection.activeRoots.isEmpty())
        assertEquals(0, activeWindowLookups)

        val activeRoot = node(packageName = "com.example.unrelated")
        val activeSelection = selectCaptureRoot(
            allRoots = listOf(node(packageName = "com.miui.home")),
            eventPackage = PAYMENT_PACKAGE,
            eventWindowId = -1,
            activeRootsProvider = {
                activeWindowLookups += 1
                listOf(activeRoot)
            }
        )

        assertSame(activeRoot, activeSelection.root)
        assertEquals(listOf(activeRoot), activeSelection.activeRoots)
        assertEquals(1, activeWindowLookups)
    }

    @Test
    fun captureGateSeparatesDisabledAndOwnPackageEvents() {
        assertFalse(shouldCapturePackage(false, PAYMENT_PACKAGE, OWN_PACKAGE))
        assertFalse(shouldCapturePackage(true, OWN_PACKAGE, OWN_PACKAGE))
        assertTrue(shouldCapturePackage(true, PAYMENT_PACKAGE, OWN_PACKAGE))
        assertTrue(shouldCapturePackage(true, candidatePackage = null, OWN_PACKAGE))
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
        const val OWN_PACKAGE = "com.bks"
        const val PAYMENT_PACKAGE = "com.example.pay"
    }
}
