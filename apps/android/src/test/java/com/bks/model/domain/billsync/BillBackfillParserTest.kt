package com.bks.model.domain.billsync

import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillBackfillParserTest {
    private val parser = BillBackfillParser(ZoneId.of("Asia/Shanghai"))

    @Test fun alipayAccessibleDirectionAmountAndUnlabeledMerchantAreAccepted() {
        val text = "账单详情\n肯德基（测试咖啡店）\n支出10.2元\n交易成功\n管理极速付款\n极速付款\n" +
            "支付时间\n2026-09-14 08:23:53\n付款方式\n上海银行信用购\n商品说明\nKFC_TEST_ORDER"
        val entry = requireNotNull(parser.parse(text, PaymentSource.ALIPAY, 1))
        assertEquals("肯德基（测试咖啡店）", entry.merchantTitle)
        assertEquals(1020L, entry.amountMinor)
        assertEquals(TransactionKind.EXPENSE, entry.transactionKind)
        assertEquals(TransactionKind.INCOME, parser.parse(text.replace("支出", "收入"), PaymentSource.ALIPAY, 1)?.transactionKind)
        assertNull(parser.parse(text.replace("交易成功", "收款成功"), PaymentSource.ALIPAY, 1))
    }

    @Test fun detailUsesHistoricalTimeSourceAndStableOrderIdentity() {
        val entry = requireNotNull(parser.parse(BACKFILL_DETAIL, PaymentSource.ALIPAY, 1))
        assertEquals(1234L, entry.amountMinor)
        assertEquals("测试便利店", entry.merchantTitle)
        assertEquals(CaptureReason.BILL_SYNC, entry.captureReason)
        assertEquals(TransactionKind.EXPENSE, entry.transactionKind)
        assertEquals(LocalDateTime.of(2026, 8, 19, 10, 20, 30).atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli(), entry.transactionTimeEpochMillis)
        assertEquals(entry.id, parser.parse(BACKFILL_DETAIL, PaymentSource.ALIPAY, 9_999)?.id)
        assertNotEquals(entry.id, parser.parse(BACKFILL_DETAIL, PaymentSource.WECHAT, 1)?.id)
        assertNotEquals(entry.id, parser.parse(BACKFILL_DETAIL.replace("202608190001", "202608190002"), PaymentSource.ALIPAY, 1)?.id)
    }

    @Test fun wechatSignedAmountAndChineseDateAreAccepted() {
        val text = BACKFILL_DETAIL.replace("2026-08-19", "2026年8月19日")
            .replace("交易成功", "支付成功")
        assertEquals(1234L, parser.parse(text, PaymentSource.WECHAT, 1)?.amountMinor)
        val income = text.replace("-12.34", "+12.34").replace("支付成功", "收款成功")
        assertEquals(TransactionKind.INCOME, parser.parse(income, PaymentSource.WECHAT, 1)?.transactionKind)
    }

    @Test fun listPendingPaymentAmbiguousAmountAndMissingDateAreRejected() {
        listOf(
            BACKFILL_DETAIL.replace("账单详情", "账单"),
            BACKFILL_DETAIL.replace("交易成功", "待付款"),
            BACKFILL_DETAIL.replace("2026-08-19 10:20:30", "今天"),
            BACKFILL_DETAIL.replace("-12.34", "-12.34\n-23.45"),
            BACKFILL_DETAIL.replace("2026-08-19", "2026-02-30"),
            BACKFILL_DETAIL.replace("-12.34", "12.34"),
            BACKFILL_DETAIL + "\n支付密码"
        ).forEach { assertNull(it, parser.parse(it, PaymentSource.ALIPAY, 1)) }
    }

    @Test fun fieldLabelsPreferTransactionTimeAndHandleLabeledAmount() {
        val text = BACKFILL_DETAIL.replace("-12.34", "交易金额：￥12.34")
            .replace("交易成功", "支付成功")
            .replace("交易时间", "创建时间\n2026-08-18 09:00\n交易时间")
        val entry = requireNotNull(parser.parse(text, PaymentSource.ALIPAY, 1))
        assertEquals(parser.parse(BACKFILL_DETAIL, PaymentSource.ALIPAY, 1)?.transactionTimeEpochMillis,
            entry.transactionTimeEpochMillis)
    }

    @Test fun noOrderFallbackIsStableAcrossSessions() {
        val text = BACKFILL_DETAIL.substringBefore("交易单号")
        assertEquals(parser.parse(text, PaymentSource.ALIPAY, 1)?.id, parser.parse(text, PaymentSource.ALIPAY, 2)?.id)
    }
}

const val BACKFILL_DETAIL = "账单详情\n测试便利店\n-12.34\n当前状态\n交易成功\n商户\n测试便利店\n交易时间\n2026-08-19 10:20:30\n付款方式\n余额\n交易单号\n202608190001"
