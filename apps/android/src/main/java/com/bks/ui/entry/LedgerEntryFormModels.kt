package com.bks.ui.entry

import androidx.compose.runtime.saveable.listSaver
import com.bks.data.local.FlowDirection
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LedgerCategoryUiModel(
    val id: String,
    val name: String,
    val kind: TransactionKind?
)

data class FundingAccountUiModel(
    val id: Long,
    val syncId: String?,
    val paymentSource: PaymentSource?,
    val label: String
)

data class LedgerEntryFormSubmission(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String?,
    val fundingAccountId: Long?,
    val newFundingAccountLabel: String?,
    val note: String?,
    val paymentSource: PaymentSource?
)

internal data class LedgerEntryFormState(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountText: String,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String,
    val fundingAccountId: Long?,
    val creatingFundingAccount: Boolean,
    val newFundingAccountLabel: String,
    val note: String,
    val paymentSource: PaymentSource?
) {
    fun toSubmission(nowEpochMillis: Long): LedgerEntryFormSubmission {
        val normalizedAmount = amountText.trim()
        require(AMOUNT_PATTERN.matches(normalizedAmount)) {
            "金额必须大于 0，且最多保留两位小数"
        }
        val amountMinor = try {
            BigDecimal(normalizedAmount).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("金额超出支持范围")
        }
        require(amountMinor > 0) { "金额必须大于 0" }
        require(transactionTimeEpochMillis <= nowEpochMillis) { "交易时间不能晚于当前时间" }
        if (creatingFundingAccount) {
            require(newFundingAccountLabel.isNotBlank()) { "请输入资金账户名称" }
        }
        return LedgerEntryFormSubmission(
            flowDirection = flowDirection,
            transactionKind = transactionKind,
            amountMinor = amountMinor,
            transactionTimeEpochMillis = transactionTimeEpochMillis,
            merchantTitle = merchantTitle,
            categoryId = categoryId,
            fundingAccountId = if (creatingFundingAccount) null else fundingAccountId,
            newFundingAccountLabel = if (creatingFundingAccount) newFundingAccountLabel else null,
            note = note,
            paymentSource = paymentSource
        )
    }

    companion object {
        val Saver = listSaver<LedgerEntryFormState, Any>(
            save = { state ->
                listOf(
                    state.flowDirection.name,
                    state.transactionKind.name,
                    state.amountText,
                    state.transactionTimeEpochMillis,
                    state.merchantTitle,
                    state.categoryId,
                    state.fundingAccountId ?: NO_FUNDING_ACCOUNT_ID,
                    state.creatingFundingAccount,
                    state.newFundingAccountLabel,
                    state.note,
                    state.paymentSource?.name.orEmpty()
                )
            },
            restore = { values ->
                LedgerEntryFormState(
                    flowDirection = FlowDirection.valueOf(values[0] as String),
                    transactionKind = TransactionKind.valueOf(values[1] as String),
                    amountText = values[2] as String,
                    transactionTimeEpochMillis = values[3] as Long,
                    merchantTitle = values[4] as String,
                    categoryId = values[5] as String,
                    fundingAccountId = (values[6] as Long)
                        .takeUnless { it == NO_FUNDING_ACCOUNT_ID },
                    creatingFundingAccount = values[7] as Boolean,
                    newFundingAccountLabel = values[8] as String,
                    note = values[9] as String,
                    paymentSource = (values[10] as String)
                        .takeIf(String::isNotEmpty)
                        ?.let(PaymentSource::valueOf)
                )
            }
        )

        fun newEntry(nowEpochMillis: Long = System.currentTimeMillis()): LedgerEntryFormState =
            LedgerEntryFormState(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountText = "",
                transactionTimeEpochMillis = nowEpochMillis,
                merchantTitle = "",
                categoryId = UNCATEGORIZED_ENTRY_CATEGORY_ID,
                fundingAccountId = null,
                creatingFundingAccount = false,
                newFundingAccountLabel = "",
                note = "",
                paymentSource = null
            )

        private const val NO_FUNDING_ACCOUNT_ID = Long.MIN_VALUE
    }
}

internal const val UNCATEGORIZED_ENTRY_CATEGORY_ID = "uncategorized"

internal fun formatLedgerEpoch(epochMillis: Long): String =
    if (epochMillis <= 0) "—" else ENTRY_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

internal fun PaymentSource?.labelOrNone(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
    null -> "未指定"
}

internal fun FlowDirection.label(): String = when (this) {
    FlowDirection.INFLOW -> "流入"
    FlowDirection.OUTFLOW -> "流出"
    FlowDirection.NEUTRAL -> "不计收支"
}

internal fun TransactionKind.label(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

private val AMOUNT_PATTERN = Regex("^\\d+(\\.\\d{1,2})?$")
private val ENTRY_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
