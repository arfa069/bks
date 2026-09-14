package com.bks.model.domain.billsync

import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** History requires an explicit transaction time; capture time is never a substitute. */
class BillBackfillParser(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun isDetailPage(text: String): Boolean = DETAIL_TITLES.any(text::contains)

    fun canUseOcr(text: String): Boolean = isDetailPage(text) && REJECTED.none(text::contains)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun parse(text: String, source: PaymentSource, capturedAtMillis: Long): PendingEntry? {
        if (source == PaymentSource.OTHER || !canUseOcr(text)) return null
        val lines = text.normalizedLines()
        val status = field(lines, listOf("当前状态", "交易状态", "退款状态"))
        if (COMPLETED.none { text.contains(it) } && status != "成功") return null
        val date = TIME_LABELS.firstNotNullOfOrNull { label ->
            field(lines, listOf(label))?.let { TIME.find(it)?.value }
        } ?: TIME.findAll(text).map { it.value }.distinct().toList().singleOrNull() ?: return null
        val transactionTime = parseTime(date) ?: return null
        val signedAmounts = lines.mapNotNull(::backfillSignedAmount).distinct()
        val labeledAmount = field(lines, AMOUNT_LABELS)?.let { AMOUNT.matchEntire(it)?.groupValues?.get(1) }
        val amountText = labeledAmount ?: signedAmounts.singleOrNull() ?: return null
        val amount = parseAmountMinor(amountText.removePrefix("-")) ?: return null
        val explicitDirection = field(lines, listOf("收支类型", "收/支", "收支"))
        val sign = signedAmounts.singleOrNull()?.takeIf {
            parseAmountMinor(it.removePrefix("-")) == amount
        } ?: amountText
        val inflow = sign.startsWith('+') || explicitDirection == "收入" ||
            listOf("收款成功", "已收款", "退款成功", "退款到账", "已存入零钱").any(text::contains)
        val outflow = sign.startsWith('-') || explicitDirection == "支出" ||
            listOf("支付成功", "付款成功", "已支付", "扣款成功").any { status == it || lines.contains(it) }
        if (inflow == outflow) return null
        val merchant = field(lines, MERCHANT_LABELS)?.takeIf(::isMerchant)
            ?: lines.indexOfFirst { backfillSignedAmount(it) != null }.takeIf { it > 0 }
                ?.let { lines[it - 1] }?.takeIf(::isMerchant)
            ?: return null
        val kind = when {
            inflow && text.contains("退款") -> TransactionKind.REFUND
            inflow -> TransactionKind.INCOME
            text.contains("转账") -> TransactionKind.TRANSFER
            text.contains("红包") -> TransactionKind.RED_PACKET
            else -> TransactionKind.EXPENSE
        }
        val order = field(lines, listOf("交易单号", "订单号", "转账单号", "退款单号"))
            ?.takeIf { ORDER.matches(it) }
        val identity = order?.let { "${source.name}|order|$it" }
            ?: "${source.name}|$transactionTime|$amount|${kind.name}|$merchant"
        val funding = field(lines, listOf("付款方式", "支付方式", "交易方式"))
        return PendingEntry(
            id = "backfill-${digest(identity)}",
            source = source,
            captureReason = CaptureReason.BILL_SYNC,
            confidence = ConfidenceState.NEEDS_REVIEW,
            transactionKind = kind,
            amountMinor = amount,
            currency = "CNY",
            merchantTitle = merchant,
            transactionTimeEpochMillis = transactionTime,
            capturedAtEpochMillis = capturedAtMillis,
            suggestedCategoryId = null,
            fundingAccountId = null,
            fundingAccountLabel = funding,
            note = null,
            evidenceSummary = null,
            parsedFieldsText = buildList {
                add("商户=$merchant")
                add("金额=${amountMinorToText(amount)}")
                add("交易时间=$date")
                order?.let { add("交易单号=$it") }
                funding?.let { add("支付方式=$it") }
            }.joinToString("\n"),
            suggestedCategoryLabel = null
        )
    }

    private fun parseTime(value: String): Long? = runCatching {
        val normalized = value.replace('年', '-').replace('月', '-').replace("日", "")
            .replace('/', '-').replace('.', '-').replace(Regex("\\s+"), " ").trim()
        val pattern = if (normalized.count { it == ':' } == 2) "uuuu-M-d H:mm:ss" else "uuuu-M-d H:mm"
        LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT))
            .atZone(zoneId).toInstant().toEpochMilli()
    }.getOrNull()

    private fun isMerchant(value: String): Boolean = value.length in 1..80 &&
        value !in DETAIL_TITLES && value !in COMPLETED && value !in FIELD_LABELS &&
        value !in listOf("返回", "更多", "支出", "收入", "微信支付", "支付宝", "详情") &&
        !AMOUNT.matches(value) && !TIME.containsMatchIn(value)

    private fun field(lines: List<String>, labels: List<String>): String? {
        lines.forEachIndexed { index, line ->
            labels.forEach { label ->
                if (line.trimEnd(':', '：') == label) {
                    lines.getOrNull(index + 1)?.takeUnless { it.trimEnd(':', '：') in FIELD_LABELS }?.let { return it }
                }
                if (line.startsWith("$label:") || line.startsWith("$label：") || line.startsWith("$label ")) {
                    line.removePrefix(label).trimStart(':', '：', ' ').takeIf(String::isNotBlank)?.let { return it }
                }
            }
        }
        return null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

private val DETAIL_TITLES = listOf("账单详情", "交易详情", "账单明细", "支付凭证")
private val REJECTED = listOf("待支付", "待付款", "处理中", "失败", "已取消", "交易关闭", "支付密码", "输入密码", "验证码")
private val COMPLETED = listOf("支付成功", "付款成功", "交易成功", "已支付", "扣款成功", "已完成", "交易完成",
    "收款成功", "已收款", "退款成功", "退款到账", "已存入零钱")
private val AMOUNT_LABELS = listOf("实付金额", "交易金额", "付款金额", "收款金额", "转账金额", "金额")
private val MERCHANT_LABELS = listOf("商户全称", "商户名称", "商户", "商家", "收款方", "付款方", "交易对象",
    "对方账户", "收款人", "付款人", "商品名称", "商品")
private val FIELD_LABELS = AMOUNT_LABELS + MERCHANT_LABELS + listOf("交易时间", "创建时间", "支付时间", "付款方式",
    "支付方式", "交易方式", "交易单号", "订单号", "转账单号", "退款单号", "当前状态", "交易状态", "退款状态")
private val TIME_LABELS = listOf("交易时间", "支付时间", "付款时间", "转账时间", "退款时间", "创建时间")
private val TIME = Regex("\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?")
private val SIGNED_AMOUNT = Regex("(?:[¥￥]\\s*)?([+-]\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?)(?:\\s*元)?")
private val AMOUNT = Regex("(?:[¥￥]\\s*)?([+-]?\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?)(?:\\s*元)?")
private val ORDER = Regex("[A-Za-z0-9_-]{6,80}")

private fun backfillSignedAmount(line: String): String? =
    SIGNED_AMOUNT.matchEntire(line)?.groupValues?.get(1)
        ?: DIRECTIONAL_AMOUNT.matchEntire(line)?.let {
            (if (it.groupValues[1] == "支出") "-" else "+") + it.groupValues[2]
        }

private val DIRECTIONAL_AMOUNT = Regex("(支出|收入)\\s*([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*元")
