package com.bks.model.domain.billsync

import com.bks.model.domain.billsync.BillPageParser
import com.bks.model.domain.billsync.BillSyncFailureReason
import com.bks.model.domain.billsync.FALLBACK_MERCHANT_TITLE
import com.bks.model.domain.billsync.ParsedBillEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPageParserTest {
    private val parser = BillPageParser()

    @Test
    fun recognizesGenericOutflowAndExtractsMerchant() {
        val entry = parse("支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12").single()

        assertEquals("社区便利店", entry.merchantTitle)
        assertEquals(3_590L, entry.amountMinor)
        assertEquals("支出", entry.transactionKindLabel)
        assertEquals("2026-08-21 09:12", entry.transactionTimeText)
    }

    @Test
    fun recognizesGenericInflowAndFallsBackMerchant() {
        val entry = parse("收款成功\n12.00元\n交易单号 20260821001").single()

        assertEquals(FALLBACK_MERCHANT_TITLE, entry.merchantTitle)
        assertEquals("收入", entry.transactionKindLabel)
        assertTrue(entry.merchantTitleFromFallback)
    }

    @Test
    fun extractsStandaloneMerchantFromAlipayResult() {
        val telecom = parse(
            "回首页\n完成\n支付成功￥4.99\n支付成功\n4.99\n中国电信\n￥4.99\n交易方式\n花呗"
        ).single()
        val restaurant = parse(
            "回首页\n完成\n支付成功￥49.98\n支付成功\n49.98\n珍友缘\n￥50.00\n" +
                "点击给商家对账\n交易方式\n支付遇财神\n花呗\n-￥0.02"
        ).single()

        assertEquals("中国电信", telecom.merchantTitle)
        assertEquals("珍友缘", restaurant.merchantTitle)
        assertEquals(4_998L, restaurant.amountMinor)
    }

    @Test
    fun standaloneAmountAndPaymentMethodDoNotBecomeMerchant() {
        val entry = parse("支付成功￥5.99\n支付成功\n5.99\n交易方式\n花呗").single()

        assertEquals(FALLBACK_MERCHANT_TITLE, entry.merchantTitle)
        assertTrue(entry.merchantTitleFromFallback)
    }

    @Test
    fun paymentMethodsAndPromotionsBeforeMerchantAreSkipped() {
        listOf("花呗", "余额宝", "上海银行信用购", "招商银行储蓄卡(1234)").forEach { method ->
            val entry = parse(
                "支付成功\n￥48.40\n$method\n支付宝积分\n美团外卖专享优惠券\n立即领\n美团\n交易方式"
            ).single()
            assertEquals("美团", entry.merchantTitle)
            assertEquals(4_840L, entry.amountMinor)
        }
    }

    @Test
    fun invalidMerchantDoesNotPreventFirstCapture() {
        listOf("花呗", "上海银行信用购").forEach { method ->
            val entry = parse("支付成功\n￥48.40\n商户：$method\n交易方式").single()
            assertEquals(FALLBACK_MERCHANT_TITLE, entry.merchantTitle)
            assertTrue(entry.merchantTitleFromFallback)
        }
    }

    @Test
    fun merchantLabelInsidePromotionIsNotAField() {
        val entry = parse("支付成功\n￥48.40\n美团\n交易方式\n花呗\n商品优惠券").single()
        assertEquals("美团", entry.merchantTitle)
        assertEquals("花呗便利店", parse("支付成功\n￥1.00\n商户：花呗便利店").single().merchantTitle)
    }

    @Test
    fun usesFirstAmountWhenPageShowsDifferentAmounts() {
        assertEquals(880L, parse("支付成功\n¥8.80\n¥9.90\n订单 1").single().amountMinor)
    }

    @Test
    fun rejectsMissingOrAmbiguousRequiredEvidence() {
        listOf(
            "支付成功\n订单 1",
            "交易成功\n¥8.80\n订单 1",
            "支付成功\n¥8.80",
            "支付成功\n收款成功\n¥8.80\n订单 1"
        ).forEach { assertTrue(parse(it).isEmpty()) }
    }

    @Test
    fun rejectsInitiationPendingFailureAndCancellation() {
        listOf("确认付款", "待支付", "处理中", "支付失败", "已取消").forEach { status ->
            assertTrue(parse("$status\n支付成功\n¥8.80\n订单 1").isEmpty())
        }
    }

    @Test
    fun reportsStableReasonForEveryExistingRejectionBranch() {
        val cases = listOf(
            "" to BillSyncFailureReason.BlankText,
            "处理中\n支付成功\n¥8.80\n订单 1" to BillSyncFailureReason.RejectedStatus,
            "支出\n¥8.80\n订单 1" to BillSyncFailureReason.CompletedStatusMissing,
            "交易成功\n¥8.80\n订单 1" to BillSyncFailureReason.DirectionMissing,
            "支付成功\n收款成功\n¥8.80\n订单 1" to BillSyncFailureReason.DirectionConflict,
            "支付成功\n订单 1" to BillSyncFailureReason.AmountMissing,
            "支付成功\n¥8.80" to BillSyncFailureReason.ContextMissing
        )

        cases.forEach { (pageText, expectedReason) ->
            assertEquals(
                expectedReason,
                parser.parseWithDiagnostics(pageText, FALLBACK_TIME).failureReason
            )
        }
    }

    private fun parse(text: String): List<ParsedBillEntry> =
        parser.parse(text, fallbackTransactionTimeText = FALLBACK_TIME)

    private companion object {
        const val FALLBACK_TIME = "2026-08-21 10:00"
    }
}
