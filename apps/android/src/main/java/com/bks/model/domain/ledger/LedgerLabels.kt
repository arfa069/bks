package com.bks.model.domain.ledger

fun PaymentSource.categorizationLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
}

fun TransactionKind.categorizationLabel(): String = when (this) {
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
