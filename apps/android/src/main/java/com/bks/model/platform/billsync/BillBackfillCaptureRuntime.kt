package com.bks.model.platform.billsync

import android.view.accessibility.AccessibilityNodeInfo
import com.bks.model.application.billsync.BillBackfillCoordinator
import com.bks.model.domain.billsync.BillBackfillParser
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class BackfillPageSnapshot(
    val packageName: String,
    val windowId: Int,
    val text: String,
    val safeForOcr: Boolean
)

/** All reads belong to a live, explicit session. No screenshot is taken by the automatic path. */
internal class BillBackfillCaptureRuntime(
    private val coordinator: BillBackfillCoordinator,
    private val scope: CoroutineScope,
    private val readPage: () -> BackfillPageSnapshot?,
    private val recognizeScreenshot: suspend (Int, () -> Boolean) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val parser: BillBackfillParser = BillBackfillParser()
) {
    private var job: Job? = null
    private var revision = 0L
    private var lastOcrAt = -OCR_INTERVAL_MILLIS
    private var recognizedOcrRevision: Long? = null

    fun start() {
        job?.cancel()
        coordinator.serviceConnectionChanged(true)
        job = scope.launch {
            coordinator.state.map { if (it.isRunning) it.sessionId else null }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                lastOcrAt = -OCR_INTERVAL_MILLIS
                recognizedOcrRevision = null
                while (isActive && coordinator.state.value.sessionId == id) {
                    delay(READ_INTERVAL_MILLIS)
                    try {
                        capturePage(id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        coordinator.report(id, "读取失败，请重新打开账单详情")
                    }
                }
            }
        }
    }

    fun pageChanged() {
        revision += 1
    }

    fun stop() {
        coordinator.serviceConnectionChanged(false)
        job?.cancel()
        job = null
    }

    private suspend fun capturePage(id: Long) {
        val source = coordinator.state.value.source
        if (!coordinator.accepts(id, source)) return
        val page = readPage()?.takeIf { it.packageName == source.backfillPackageName() } ?: return
        val entry = parser.parse(page.text, source, clock())
        if (entry != null) {
            coordinator.submit(id, entry, usedOcr = false)
        } else if (!parser.canUseOcr(page.text) || !page.safeForOcr) {
            coordinator.report(id, "请打开单笔账单详情，列表和支付中页面不会补录")
        } else {
            captureWithOcr(id, source, page)
        }
    }

    private suspend fun captureWithOcr(id: Long, source: PaymentSource, page: BackfillPageSnapshot) {
        if (recognizedOcrRevision == revision || clock() - lastOcrAt < OCR_INTERVAL_MILLIS) return
        lastOcrAt = clock()
        val capturedRevision = revision
        val stillCurrent = { isCurrentPage(id, source, page, capturedRevision) }
        if (!stillCurrent()) return
        coordinator.report(id, "文字不完整，正在识别当前账单画面")
        val text = recognizeScreenshot(page.windowId, stillCurrent)
        if (!stillCurrent()) return
        val entry = text?.let { parser.parse(it, source, clock()) }
        if (entry != null) {
            coordinator.submit(id, entry, usedOcr = true)
            recognizedOcrRevision = capturedRevision
        } else {
            coordinator.report(id, "未读全账单，请展开详情后停留片刻；受保护的画面无法识别")
        }
    }

    private fun isCurrentPage(id: Long, source: PaymentSource, page: BackfillPageSnapshot, expectedRevision: Long): Boolean =
        coordinator.accepts(id, source) && expectedRevision == revision && readPage()?.let {
            it.safeForOcr && it.windowId == page.windowId && it.packageName == page.packageName && it.text == page.text
        } == true
}

internal fun PaymentSource.backfillPackageName(): String = when (this) {
    PaymentSource.ALIPAY -> ALIPAY_PACKAGE_NAME
    PaymentSource.WECHAT -> WECHAT_PACKAGE_NAME
    PaymentSource.OTHER -> ""
}

internal fun AccessibilityNodeInfo.hasSensitiveBackfillInput(): Boolean {
    val nodes = ArrayDeque<AccessibilityNodeInfo>()
    nodes.add(this)
    var visited = 0
    while (nodes.isNotEmpty() && visited < MAX_CAPTURE_NODES) {
        val node = nodes.removeFirst()
        visited += 1
        if (node.isVisibleToUser && (node.isPassword || node.isEditable)) return true
        for (index in 0 until node.childCount) {
            if (visited + nodes.size >= MAX_CAPTURE_NODES) return true
            node.getChild(index)?.let(nodes::add)
        }
    }
    return nodes.isNotEmpty()
}

private const val READ_INTERVAL_MILLIS = 800L
private const val OCR_INTERVAL_MILLIS = 5_000L
