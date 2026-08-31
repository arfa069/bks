package com.bks.model.domain.billsync

import com.bks.model.domain.billsync.BillSyncFailureReason
import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.billsync.FALLBACK_MERCHANT_TITLE
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.PaymentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncPipelineTest {
    private val pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-21 10:00" })

    @Test
    fun genericCaptureCreatesReviewOnlyCandidateWithoutRawTextOrAccount() {
        val result = sync("支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12")
        val entry = result.createdEntries.single()

        assertTrue(result.recognized)
        assertEquals(PaymentSource.OTHER, entry.source)
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, entry.captureReason)
        assertEquals(ConfidenceState.NEEDS_REVIEW, entry.confidence)
        assertNull(entry.fundingAccountLabel)
        assertNull(entry.fundingAccountId)
        assertNull(entry.evidenceSummary)
    }

    @Test
    fun fallbackMerchantRemainsPendingWithReviewNote() {
        val entry = sync("收款成功\n¥12.00\n交易单号 123").createdEntries.single()

        assertEquals(FALLBACK_MERCHANT_TITLE, entry.merchantTitle)
        assertTrue(entry.note != null)
    }

    @Test
    fun repeatedCandidateCreatesANewPendingEntry() {
        val first = sync("支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12")
            .createdEntries.single()
        val second = pipeline.sync(
            pageText = "支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12",
            capturedAtEpochMillis = NOW + 31_000
        ).createdEntries.single()

        assertTrue(first.id != second.id)
    }

    @Test
    fun unrelatedPageCreatesNothing() {
        val result = sync("聊天内容\n¥35.90")

        assertFalse(result.recognized)
        assertTrue(result.createdEntries.isEmpty())
        assertEquals(BillSyncFailureReason.CompletedStatusMissing, result.failureReason)
    }

    private fun sync(pageText: String): BillSyncResult = pipeline.sync(
        pageText = pageText,
        capturedAtEpochMillis = NOW
    )

    private companion object {
        const val NOW = 1_755_741_600_000L
    }
}
