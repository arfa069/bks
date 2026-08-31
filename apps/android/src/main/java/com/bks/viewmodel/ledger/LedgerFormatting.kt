package com.bks.viewmodel.ledger

fun formatMoney(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "¥$yuan.${cents.toString().padStart(2, '0')}"
}

fun formatSignedMoney(amountMinor: Long): String {
    val sign = when {
        amountMinor > 0 -> "+"
        amountMinor < 0 -> "-"
        else -> ""
    }
    return "$sign${formatMoney(kotlin.math.abs(amountMinor))}"
}
