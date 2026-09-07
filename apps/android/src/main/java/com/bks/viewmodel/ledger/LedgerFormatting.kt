package com.bks.viewmodel.ledger

import java.math.BigDecimal
import java.math.RoundingMode

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

fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

fun parseAmountMinor(text: String): Long? = runCatching {
    BigDecimal(text.trim())
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
}.getOrNull()
