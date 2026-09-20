package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncMerchantRetryTest {
    private val pipeline = BillSyncPipeline()
    private fun result(merchant: String = "", amount: String = "10.20", time: Long = 1_000) =
        pipeline.sync("支付成功\n￥$amount\n${merchant.takeIf { it.isNotEmpty() }?.let { "商户：$it\n" }.orEmpty()}交易方式\n花呗",
            time, PaymentSource.ALIPAY)

    @Test fun lateMerchantKeepsOriginalIdentityAmountAndCaptureTime() = runTest {
        val original = result()
        var reads = 0
        val completed = completeMissingMerchant(original) {
            if (++reads == 1) result() else result("肯德基（测试咖啡店）", time = 9_000)
        }
        assertEquals(2, reads)
        assertFalse(completed.merchantMissing)
        val entry = completed.createdEntries.single()
        assertEquals("肯德基（测试咖啡店）", entry.merchantTitle)
        assertEquals(original.createdEntries.single().id, entry.id)
        assertEquals(original.createdEntries.single().transactionTimeEpochMillis, entry.transactionTimeEpochMillis)
        assertEquals(1_000L, entry.capturedAtEpochMillis)
        assertEquals(null, entry.note)
    }

    @Test fun knownMerchantNeverWaitsOrReadsAgain() = runTest {
        val original = result("乐途车行", "0.43")
        assertEquals(original, completeMissingMerchant(original) { error("must not read") })
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun missingMerchantHasBoundedRetriesAndKeepsFallback() = runTest {
        val original = result()
        var reads = 0
        assertEquals(original, completeMissingMerchant(original) { reads++; original })
        assertEquals(3, reads)
        assertEquals(1_200L, testScheduler.currentTime)
    }

    @Test fun changedWindowTransactionOrUnreadablePageKeepsOriginal() = runTest {
        val original = result()
        listOf(null, result("其他商户", "0.43"), original.copy(recognized = false, createdEntries = emptyList()))
            .forEach { latest ->
                var reads = 0
                assertEquals(original, completeMissingMerchant(original) { reads++; latest })
                assertEquals(1, reads)
            }
        assertEquals(original, completeMissingMerchant(original) { error("read failed") })
    }

    @Test fun endedSessionCancelsRetryWithoutFinishingPersistence() = runTest {
        var persisted = false
        val job = launch { completeMissingMerchant(result()) { result() }; persisted = true }
        advanceTimeBy(200)
        job.cancel()
        job.join()
        assertFalse(persisted)
        assertTrue(job.isCancelled)
    }

    @Test fun duplicateEventsDuringMerchantRetryPersistOnlyOneCompletedEntry() = runTest {
        val tracker = CaptureWindowTracker()
        val saved = mutableListOf<com.bks.model.domain.billsync.BillSyncResult>()
        repeat(2) {
            handoffSanitizedCapture(
                SanitizedBillSyncCapture(ALIPAY_PACKAGE_NAME, 1, "test"), tracker,
                recognize = { _, _ -> result() },
                persist = { first ->
                    completeMissingMerchant(first) { result("肯德基（测试咖啡店）") }
                        .also { saved += it }.copy(insertedCount = 1)
                },
                launch = { launch { it() } }
            )
        }
        advanceTimeBy(401)
        assertEquals(1, saved.size)
        assertFalse(saved.single().merchantMissing)
        assertFalse(tracker.acceptIfNew(ALIPAY_PACKAGE_NAME, 1))
    }

    @Test fun newPaymentInvalidatesMerchantCompletionWhileWriteIsAdmitted() {
        val tracker = CaptureWindowTracker()
        tracker.acceptIfNew(ALIPAY_PACKAGE_NAME, 1)
        assertTrue(tracker.canCompleteMerchant(ALIPAY_PACKAGE_NAME, 1))
        tracker.releaseForNewPayment(ALIPAY_PACKAGE_NAME)
        assertFalse(tracker.canCompleteMerchant(ALIPAY_PACKAGE_NAME, 1))
    }
}
