package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncFailureReason
import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.platform.billsync.CaptureWindowTracker
import com.bks.model.platform.billsync.BillSyncCaptureOutcome
import com.bks.model.platform.billsync.collectReadableText
import com.bks.model.platform.billsync.handoffSanitizedCapture
import com.bks.model.platform.billsync.SanitizedBillSyncCapture
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncCaptureHandoffTest {
    @Test
    fun sanitizedCaptureIsHandedOffOnceAndSuccessfulWindowRemainsSuppressed() {
        val root = node("safe")
        shadowOf(root).addChild(node("password").apply { isPassword = true })
        val capture = SanitizedBillSyncCapture(PAYMENT_PACKAGE, WINDOW_ID, root.collectReadableText())
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(recognized = true)

        handoff(capture, windowTracker, coordinator)
        handoff(capture, windowTracker, coordinator)

        assertEquals(listOf("safe", "safe"), coordinator.recognizedPageTexts)
        assertEquals(
            listOf(PaymentSource.ALIPAY, PaymentSource.ALIPAY),
            coordinator.recognizedPaymentSources
        )
        assertEquals(1, coordinator.persistedResults.size)
        assertFalse(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun unrecognizedCaptureKeepsPreviouslySuccessfulWindow() {
        val windowTracker = CaptureWindowTracker()
        assertTrue(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        windowTracker.persistenceSucceeded(PAYMENT_PACKAGE, WINDOW_ID)
        val coordinator = FakeCaptureCoordinator(recognized = false)

        handoff(capture(), windowTracker, coordinator)

        assertFalse(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        assertTrue(coordinator.persistedResults.isEmpty())
    }

    @Test
    fun unrecognizedCaptureDuringPersistenceKeepsSuppressionAfterCompletion() {
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        var pendingPersistence: (suspend () -> Unit)? = null

        handoffSanitizedCapture(
            capture = capture(),
            windowTracker = windowTracker,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> pendingPersistence = block }
        )
        coordinator.recognized = false
        handoff(capture(), windowTracker, coordinator)

        runBlocking { requireNotNull(pendingPersistence).invoke() }

        assertFalse(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
        assertEquals(1, coordinator.persistedResults.size)
    }

    @Test
    fun recognitionReturningDuringPersistenceKeepsWindowSuppressed() {
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        var pendingPersistence: (suspend () -> Unit)? = null

        handoffSanitizedCapture(
            capture = capture(),
            windowTracker = windowTracker,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> pendingPersistence = block }
        )
        coordinator.recognized = false
        handoff(capture(), windowTracker, coordinator)
        coordinator.recognized = true
        handoff(capture(), windowTracker, coordinator)

        runBlocking { requireNotNull(pendingPersistence).invoke() }

        assertEquals(1, coordinator.persistedResults.size)
        assertFalse(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun processorFailureReleasesWindowForRetry() {
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(
            recognized = true,
            persistFailure = IllegalStateException("expected")
        )

        handoff(capture(), windowTracker, coordinator)

        assertTrue(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun cancellationPropagatesWithoutReleasingSuccessfulWindow() {
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(
            recognized = true,
            persistFailure = CancellationException("expected")
        )

        val cancellation = runCatching {
            handoff(capture(), windowTracker, coordinator)
        }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertFalse(windowTracker.acceptIfNew(PAYMENT_PACKAGE, WINDOW_ID))
    }

    @Test
    fun outcomeReportsActualInsertDuplicateAndParserFailureReasons() {
        val windowTracker = CaptureWindowTracker()
        val coordinator = FakeCaptureCoordinator(recognized = true)
        val outcomes = mutableListOf<BillSyncCaptureOutcome>()

        handoff(capture(), windowTracker, coordinator, outcomes)
        handoff(capture(), windowTracker, coordinator, outcomes)
        coordinator.recognized = false
        handoff(capture(), windowTracker, coordinator, outcomes)

        assertEquals(BillSyncCaptureOutcome.Inserted(1), outcomes[0])
        assertEquals(
            BillSyncCaptureOutcome.NotInserted("already_captured_window"),
            outcomes[1]
        )
        assertEquals(
            BillSyncCaptureOutcome.Failed(BillSyncFailureReason.CompletedStatusMissing.code),
            outcomes[2]
        )
    }

    @Test
    fun longStayWithPartialAndPromotionalCapturesPersistsOnlyOnce() {
        val pages = listOf(
            SUCCESS_PAGE, "", "处理中", "立即领\n优惠券", SUCCESS_PAGE,
            "支付成功\n￥48.40\n确认付款\n交易方式", SUCCESS_PAGE,
            SUCCESS_PAGE.replace("美团", "花呗"), SUCCESS_PAGE
        )

        assertEquals(1, persistPages(pages.map { it to WINDOW_ID }).size)
    }

    @Test
    fun nextSameMerchantAndAmountPaymentCanUseTheSameWindow() {
        val entries = persistPages(
            listOf(SUCCESS_PAGE to WINDOW_ID, "确认付款" to WINDOW_ID, SUCCESS_PAGE to WINDOW_ID)
        )
        assertEquals(2, entries.size)
        assertEquals(entries[0].createdEntries.single().merchantTitle, entries[1].createdEntries.single().merchantTitle)
        assertEquals(entries[0].createdEntries.single().amountMinor, entries[1].createdEntries.single().amountMinor)
    }

    @Test
    fun checkoutInAnotherWindowRearmsPreviousResultWindow() {
        val entries = persistPages(
            listOf(SUCCESS_PAGE to WINDOW_ID, "待支付\n确认付款" to 99, SUCCESS_PAGE to WINDOW_ID)
        )
        assertEquals(2, entries.size)
    }

    @Test
    fun incompleteFirstCaptureStillAllowsImmediateFirstSuccess() {
        val entries = persistPages(listOf("" to WINDOW_ID, SUCCESS_PAGE to WINDOW_ID))
        assertEquals(1, entries.size)
    }

    private fun persistPages(pages: List<Pair<String, Int>>): List<BillSyncResult> {
        val tracker = CaptureWindowTracker()
        val pipeline = BillSyncPipeline()
        val persisted = mutableListOf<BillSyncResult>()
        pages.forEachIndexed { index, (text, windowId) ->
            handoffSanitizedCapture(
                capture = SanitizedBillSyncCapture(PAYMENT_PACKAGE, windowId, text),
                windowTracker = tracker,
                recognize = { page, source -> pipeline.sync(page, 1_000_000L + index * 60_000L, source) },
                persist = { result -> result.copy(insertedCount = 1).also(persisted::add) },
                launch = { block -> runBlocking { block() } }
            )
        }
        return persisted
    }

    @Test
    fun packageNameMapsToKnownPaymentSourceWithOtherFallback() {
        assertEquals(PaymentSource.ALIPAY, paymentSourceForPackage(ALIPAY_PACKAGE_NAME))
        assertEquals(PaymentSource.WECHAT, paymentSourceForPackage(WECHAT_PACKAGE_NAME))
        assertEquals(PaymentSource.OTHER, paymentSourceForPackage("com.example.pay"))
    }

    private fun handoff(
        capture: SanitizedBillSyncCapture,
        windowTracker: CaptureWindowTracker,
        coordinator: FakeCaptureCoordinator,
        outcomes: MutableList<BillSyncCaptureOutcome>? = null
    ) {
        handoffSanitizedCapture(
            capture = capture,
            windowTracker = windowTracker,
            recognize = coordinator::recognize,
            persist = coordinator::persist,
            launch = { block -> runBlocking { block() } },
            onOutcome = { outcome -> outcomes?.add(outcome) }
        )
    }

    private fun capture(): SanitizedBillSyncCapture = SanitizedBillSyncCapture(
        packageName = PAYMENT_PACKAGE,
        windowId = WINDOW_ID,
        pageText = "支付成功\n￥4.99\n订单 1"
    )

    @Suppress("DEPRECATION")
    private fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isVisibleToUser = true
        this.text = text
    }

    private class FakeCaptureCoordinator(
        var recognized: Boolean,
        private val persistFailure: Throwable? = null
    ) {
        val recognizedPageTexts = mutableListOf<String>()
        val recognizedPaymentSources = mutableListOf<PaymentSource>()
        val persistedResults = mutableListOf<BillSyncResult>()

        fun recognize(pageText: String, paymentSource: PaymentSource): BillSyncResult {
            recognizedPageTexts += pageText
            recognizedPaymentSources += paymentSource
            return BillSyncResult(
                recognized = recognized,
                createdEntries = emptyList(),
                failureReason = BillSyncFailureReason.CompletedStatusMissing.takeIf { !recognized }
            )
        }

        suspend fun persist(result: BillSyncResult): BillSyncResult {
            persistFailure?.let { throw it }
            val persisted = result.copy(insertedCount = 1)
            persistedResults += persisted
            return persisted
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = ALIPAY_PACKAGE_NAME
        const val WINDOW_ID = 7
        const val SUCCESS_PAGE = "支付成功\n￥48.40\n美团\n交易方式\n花呗"
    }
}
