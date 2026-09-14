package com.bks.model.domain.billsync

import java.math.BigDecimal
import java.math.RoundingMode

data class ParsedBillEntry(
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val transactionTimeText: String,
    val parsedFields: List<String>,
    val merchantTitleFromFallback: Boolean
)

enum class BillSyncFailureReason(val code: String) {
    BlankText("blank_text"),
    RejectedStatus("rejected_status"),
    CompletedStatusMissing("completed_status_missing"),
    DirectionMissing("direction_missing"),
    DirectionConflict("direction_conflict"),
    AmountMissing("amount_missing"),
    ContextMissing("context_missing")
}

internal data class BillPageParseResult(
    val entries: List<ParsedBillEntry>,
    val failureReason: BillSyncFailureReason? = null
)

class BillPageParser {
    fun parse(pageText: String, fallbackTransactionTimeText: String): List<ParsedBillEntry> =
        parseWithDiagnostics(pageText, fallbackTransactionTimeText).entries

    @Suppress("CyclomaticComplexMethod")
    internal fun parseWithDiagnostics(
        pageText: String,
        fallbackTransactionTimeText: String
    ): BillPageParseResult {
        val lines = pageText.normalizedLines()
        val normalizedText = lines.joinToString("\n")
        if (normalizedText.isBlank()) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.BlankText)
        }
        if (REJECTED_STATUS.any(normalizedText::contains)) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.RejectedStatus)
        }
        if (COMPLETED_STATUS.none(normalizedText::contains)) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.CompletedStatusMissing)
        }

        val hasInflow = INFLOW_STATUS.any(normalizedText::contains)
        val hasOutflow = OUTFLOW_STATUS.any(normalizedText::contains)
        if (!hasInflow && !hasOutflow) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.DirectionMissing)
        }
        if (hasInflow && hasOutflow) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.DirectionConflict)
        }
        val direction = if (hasInflow) PaymentDirection.Inflow else PaymentDirection.Outflow
        val amount = AMOUNT_REGEX.findAll(normalizedText)
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull(String::isNotBlank) }
            .mapNotNull(::parseAmountMinor)
            .firstOrNull()
            ?: return BillPageParseResult(emptyList(), BillSyncFailureReason.AmountMissing)
        if (CONTEXT_LABELS.none(normalizedText::contains)) {
            return BillPageParseResult(emptyList(), BillSyncFailureReason.ContextMissing)
        }

        val merchant = extractMerchant(lines)
        val title = merchant ?: FALLBACK_MERCHANT_TITLE
        val kind = when {
            normalizedText.contains("退款") -> "退款"
            direction == PaymentDirection.Inflow -> "收入"
            else -> "支出"
        }
        val transactionTime = TRANSACTION_TIME_REGEX.find(normalizedText)?.value
            ?: fallbackTransactionTimeText
        return BillPageParseResult(
            entries = listOf(
                ParsedBillEntry(
                    merchantTitle = title,
                    amountMinor = amount,
                    transactionKindLabel = kind,
                    transactionTimeText = transactionTime,
                    parsedFields = listOf(
                        "商户=$title",
                        "金额=${amountMinorToText(amount)}",
                        "类型=$kind"
                    ),
                    merchantTitleFromFallback = merchant == null
                )
            )
        )
    }

    private fun extractMerchant(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val inline = MERCHANT_LABELS.firstNotNullOfOrNull { label ->
                line.takeIf { it.startsWith("$label：") || it.startsWith("$label:") || it.startsWith("$label ") }
                    ?.removePrefix(label)
                    .orEmpty()
                    .trimStart(' ', ':', '：')
                    .normalizeMerchantValue()
                    .takeIf(::isMerchantValue)
            }
            if (inline != null) return inline
            if (line.trimEnd(' ', ':', '：') in MERCHANT_LABELS) {
                lines.getOrNull(index + 1)
                    ?.normalizeMerchantValue()
                    ?.takeIf(::isMerchantValue)
                    ?.let { return it }
            }
        }
        val detailTitleIndex = lines.indexOfFirst { it in BILL_DETAIL_TITLES }
        if (detailTitleIndex >= 0) {
            val header = lines.drop(detailTitleIndex + 1).takeWhile { line ->
                !PLAIN_AMOUNT_REGEX.matches(line) && !DIRECTIONAL_AMOUNT_REGEX.matches(line) &&
                    COMPLETED_STATUS.none(line::contains)
            }
            return header.map(String::normalizeMerchantValue).filter(::isStandaloneMerchantValue).singleOrNull()
        }
        val completedStatusIndex = lines.indexOfFirst { line ->
            COMPLETED_STATUS.any { status -> line == status }
        }
        if (completedStatusIndex == -1) return null
        return lines.asSequence()
            .drop(completedStatusIndex + 1)
            .takeWhile { line -> !line.startsWithContextLabel() }
            .map(String::normalizeMerchantValue)
            .firstOrNull(::isStandaloneMerchantValue)
    }

    private fun isMerchantValue(value: String): Boolean =
        value.isNotBlank() &&
            value.any(Char::isLetter) &&
            value.length <= MAX_MERCHANT_LENGTH &&
            !PAYMENT_METHOD_REGEX.matches(value) &&
            MERCHANT_PROMOTION_TEXT.none(value::contains) &&
            value !in MERCHANT_NAVIGATION_LABELS &&
            value.trimEnd(' ', ':', '：') !in CONTEXT_LABELS &&
            !AMOUNT_REGEX.containsMatchIn(value) &&
            COMPLETED_STATUS.none(value::contains) &&
            REJECTED_STATUS.none(value::contains)

    private fun isStandaloneMerchantValue(value: String): Boolean =
        isMerchantValue(value) &&
            !PLAIN_AMOUNT_REGEX.matches(value) &&
            value !in MERCHANT_NAVIGATION_LABELS &&
            !value.startsWith("点击")
}

private enum class PaymentDirection { Inflow, Outflow }

internal fun parseAmountMinor(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(",", "").removePrefix("+"))
        .abs()
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
        .takeIf { it > 0 }
}.getOrNull()

internal fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

internal fun String.normalizedLines(): List<String> = lineSequence()
    .map { it.trim() }
    .filter(String::isNotBlank)
    .toList()

// A failed/partial read is not evidence that a completed payment has ended.
internal fun isNewPaymentFlow(pageText: String): Boolean {
    val lines = pageText.normalizedLines()
    return lines.none { line -> COMPLETED_STATUS.any(line::contains) } &&
        lines.any { it in PAYMENT_INITIATION_LABELS }
}

private fun String.normalizeMerchantValue(): String =
    trim().replace(MERCHANT_WHITESPACE_REGEX, " ")

private fun String.startsWithContextLabel(): Boolean =
    CONTEXT_LABELS.any { label -> startsWith(label) }

internal const val FALLBACK_MERCHANT_TITLE = "其他应用支付"

private const val MAX_MERCHANT_LENGTH = 80
private val AMOUNT_REGEX = Regex(
    "(?:[¥￥]\\s*([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)|" +
        "([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*元)"
)
private val PLAIN_AMOUNT_REGEX = Regex(
    "^[+-]?(?:[¥￥]\\s*)?[0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?(?:\\s*元)?$"
)
private val MERCHANT_WHITESPACE_REGEX = Regex("[\\s\\u3000]+")
private val TRANSACTION_TIME_REGEX = Regex(
    "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}(?:日)?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?"
)
private val COMPLETED_STATUS = listOf(
    "支付成功", "付款成功", "交易成功", "收款成功", "收款到账", "已收款",
    "已支付", "扣款成功", "退款成功", "退款到账", "交易完成", "已完成"
)
private val INFLOW_STATUS = listOf("收款成功", "收款到账", "已收款", "收入", "入账", "退款成功", "退款到账")
private val OUTFLOW_STATUS = listOf("支付成功", "付款成功", "已支付", "扣款成功", "支出")
private val REJECTED_STATUS = listOf(
    "待支付", "待付款", "处理中", "支付失败", "付款失败", "交易失败", "已取消", "交易取消",
    "输入密码", "支付密码", "确认付款", "立即支付", "立即付款", "继续支付"
)
private val CONTEXT_LABELS = listOf(
    "商户", "收款方", "付款方", "交易对象", "对方", "商品", "订单", "交易单号", "付款方式",
    "支付方式", "交易时间", "创建时间", "账单", "交易方式"
)
private val MERCHANT_LABELS = listOf("商户名称", "商户", "收款方", "付款方", "交易对象", "对方", "商品")
private val MERCHANT_NAVIGATION_LABELS = setOf("回首页", "完成", "返回", "全部账单", "账单详情", "交易详情", "账单明细", "支付凭证")
private val BILL_DETAIL_TITLES = setOf("账单详情", "交易详情", "账单明细", "支付凭证")
private val DIRECTIONAL_AMOUNT_REGEX = Regex("(?:支出|收入)\\s*[0-9]+(?:\\.[0-9]{1,2})?\\s*元")
private val PAYMENT_METHOD_REGEX = Regex(
    "^(?:花呗(?:分期)?|余额宝|余额|支付宝余额|银行卡|信用卡|储蓄卡|借记卡|信用购|" +
        ".{1,24}银行(?:信用购|信用卡|储蓄卡|借记卡|贷记卡))(?:\\s*[（(][^()（）]{1,24}[）)])?$"
)
private val MERCHANT_PROMOTION_TEXT = listOf("优惠券", "支付宝积分", "立即领", "免费领", "可兑换", "支付遇财神")
private val PAYMENT_INITIATION_LABELS = setOf(
    "确认付款", "立即付款", "立即支付", "继续支付", "待支付", "待付款",
    "输入支付密码", "请输入支付密码", "请输入付款密码"
)
