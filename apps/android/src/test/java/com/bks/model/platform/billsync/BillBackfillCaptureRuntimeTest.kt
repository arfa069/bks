package com.bks.model.platform.billsync

import com.bks.model.application.billsync.BillBackfillCoordinator
import com.bks.model.domain.billsync.BACKFILL_DETAIL
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillBackfillCaptureRuntimeTest {
    @Test fun readableDetailNeverInvokesOcrAndRepeatedReadsInsertOnce() = runTest {
        var ocrCalls = 0
        var writes = 0
        val coordinator = BillBackfillCoordinator(backgroundScope, { writes++; it.copy(insertedCount = 1) }, { testScheduler.currentTime })
        val runtime = BillBackfillCaptureRuntime(coordinator, backgroundScope,
            { BackfillPageSnapshot(ALIPAY_PACKAGE_NAME, 1, BACKFILL_DETAIL, true) },
            { _, _ -> ocrCalls++; BACKFILL_DETAIL }, { testScheduler.currentTime })
        runtime.start()
        coordinator.start(PaymentSource.ALIPAY)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, writes)
        assertEquals(0, ocrCalls)
        coordinator.finish()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, writes)
    }

    @Test fun incompleteDetailUsesOcrOnlyDuringSession() = runTest {
        var ocrCalls = 0
        val coordinator = BillBackfillCoordinator(backgroundScope, { it.copy(insertedCount = 1) }, { testScheduler.currentTime })
        val runtime = BillBackfillCaptureRuntime(coordinator, backgroundScope,
            { BackfillPageSnapshot(ALIPAY_PACKAGE_NAME, 1, "账单详情", true) },
            { _, allowed -> check(allowed()); ocrCalls++; BACKFILL_DETAIL }, { testScheduler.currentTime })
        runtime.start()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, ocrCalls)
        coordinator.start(PaymentSource.ALIPAY)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, ocrCalls)
        assertEquals(1, coordinator.state.value.insertedCount)
        coordinator.finish()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, ocrCalls)
    }

    @Test fun otherPackagesInputScreensAndBillListsCannotTriggerOcr() = runTest {
        var ocrCalls = 0
        var page = BackfillPageSnapshot(WECHAT_PACKAGE_NAME, 1, "账单详情", true)
        val coordinator = BillBackfillCoordinator(backgroundScope, { error("unexpected write") }, { testScheduler.currentTime })
        val runtime = BillBackfillCaptureRuntime(coordinator, backgroundScope, { page },
            { _, _ -> ocrCalls++; BACKFILL_DETAIL }, { testScheduler.currentTime })
        runtime.start()
        coordinator.start(PaymentSource.ALIPAY)
        advanceTimeBy(2_000); runCurrent()
        page = page.copy(packageName = ALIPAY_PACKAGE_NAME, safeForOcr = false)
        advanceTimeBy(2_000); runCurrent()
        page = page.copy(text = "账单\n本月支出\n¥100.00", safeForOcr = true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, ocrCalls)
    }

    @Test fun changingPageDuringOcrDiscardsTheOldResult() = runTest {
        val result = CompletableDeferred<String>()
        var writes = 0
        val coordinator = BillBackfillCoordinator(backgroundScope, { writes++; it }, { testScheduler.currentTime })
        val runtime = BillBackfillCaptureRuntime(coordinator, backgroundScope,
            { BackfillPageSnapshot(ALIPAY_PACKAGE_NAME, 1, "账单详情", true) },
            { _, _ -> result.await() }, { testScheduler.currentTime })
        runtime.start()
        coordinator.start(PaymentSource.ALIPAY)
        advanceTimeBy(1_000); runCurrent()
        runtime.pageChanged()
        result.complete(BACKFILL_DETAIL)
        runCurrent()
        assertEquals(0, writes)
        runtime.stop()
        runCurrent()
    }
}
