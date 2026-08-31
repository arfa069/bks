package com.bks.viewmodel.ledger

import java.math.BigInteger
import java.time.YearMonth

data class MonthlySummary(val expenseMinor: Long, val incomeMinor: Long, val netMinor: Long)

data class CategoryTotal(val category: String, val amountMinor: Long)

data class MonthlyCashFlowTotal(
    val monthKey: String,
    val expenseMinor: Long,
    val incomeMinor: Long
)

data class CategoryShareSlice(
    val category: String,
    val amountMinor: Long,
    val percentageTenths: Int
)

data class LedgerReportUiModel(
    val anchorMonthKey: String? = null,
    val summary: MonthlySummary? = null,
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val categorySlices: List<CategoryShareSlice> = emptyList(),
    val cashFlowTotals: List<MonthlyCashFlowTotal> = emptyList()
)

fun buildLedgerReportUiModel(entries: List<LedgerUiEntry>): LedgerReportUiModel {
    val anchorMonthKey = latestCashFlowMonthKey(entries) ?: return LedgerReportUiModel()
    val summary = monthlySummary(entries, anchorMonthKey)
    val categoryTotals = categoryExpenseTotals(entries, anchorMonthKey)
    return LedgerReportUiModel(
        anchorMonthKey = anchorMonthKey,
        summary = summary,
        categoryTotals = categoryTotals,
        categorySlices = categoryShareSlices(categoryTotals),
        cashFlowTotals = monthlyCashFlowRange(entries, anchorMonthKey)
    )
}

fun monthlySummary(entries: List<LedgerUiEntry>, monthKey: String): MonthlySummary {
    val currentMonthEntries = entries.filter { it.isActive() && it.monthKey == monthKey }
    val expense = currentMonthEntries.filter { it.flowType == LedgerFlowType.EXPENSE }.sumOf { it.amountMinor }
    val income = currentMonthEntries.filter { it.flowType == LedgerFlowType.INCOME }.sumOf { it.amountMinor }
    return MonthlySummary(expense, income, income - expense)
}

fun categoryExpenseTotals(entries: List<LedgerUiEntry>, monthKey: String): List<CategoryTotal> = entries
    .filter { it.isActive() && it.monthKey == monthKey && it.flowType == LedgerFlowType.EXPENSE }
    .groupBy { it.category }
    .map { (category, values) -> CategoryTotal(category, values.sumOf { it.amountMinor }) }
    .sortedByDescending { it.amountMinor }

fun latestCashFlowMonthKey(entries: List<LedgerUiEntry>): String? = entries
    .asSequence()
    .filter { it.isActiveCashFlow() }
    .map { YearMonth.parse(it.monthKey) }
    .maxOrNull()
    ?.toString()

fun monthlyCashFlowRange(
    entries: List<LedgerUiEntry>,
    anchorMonthKey: String,
    radius: Int = 3
): List<MonthlyCashFlowTotal> {
    require(radius >= 0) { "radius must be non-negative" }
    val anchorMonth = YearMonth.parse(anchorMonthKey)
    val entriesByMonth = entries.asSequence()
        .filter { it.isActiveCashFlow() }
        .groupBy { YearMonth.parse(it.monthKey) }
    return (-radius..radius).map { offset ->
        val month = anchorMonth.plusMonths(offset.toLong())
        val monthEntries = entriesByMonth[month].orEmpty()
        MonthlyCashFlowTotal(
            month.toString(),
            monthEntries.filter { it.flowType == LedgerFlowType.EXPENSE }.sumOf { it.amountMinor },
            monthEntries.filter { it.flowType == LedgerFlowType.INCOME }.sumOf { it.amountMinor }
        )
    }
}

fun categoryShareSlices(
    totals: List<CategoryTotal>,
    maxVisibleCategories: Int = 4
): List<CategoryShareSlice> {
    require(maxVisibleCategories > 0) { "maxVisibleCategories must be positive" }
    val rankedTotals = totals.filter { it.amountMinor > 0 }
        .sortedWith(compareByDescending<CategoryTotal> { it.amountMinor }.thenBy { it.category })
    if (rankedTotals.isEmpty()) return emptyList()
    val displayedTotals = if (rankedTotals.size <= maxVisibleCategories) {
        rankedTotals
    } else {
        rankedTotals.take(maxVisibleCategories) +
            CategoryTotal("其他", rankedTotals.drop(maxVisibleCategories).sumOf { it.amountMinor })
    }
    val totalAmount = displayedTotals.fold(BigInteger.ZERO) { sum, total ->
        sum.add(BigInteger.valueOf(total.amountMinor))
    }
    val percentageTenths = MutableList(displayedTotals.size) { 0 }
    val remainders = displayedTotals.mapIndexed { index, total ->
        val quotientAndRemainder = BigInteger.valueOf(total.amountMinor)
            .multiply(BigInteger.valueOf(1000))
            .divideAndRemainder(totalAmount)
        percentageTenths[index] = quotientAndRemainder[0].toInt()
        index to quotientAndRemainder[1]
    }
    remainders.sortedWith(compareByDescending<Pair<Int, BigInteger>> { it.second }.thenBy { it.first })
        .take(1000 - percentageTenths.sum())
        .forEach { (index) -> percentageTenths[index] += 1 }
    return displayedTotals.mapIndexed { index, total ->
        CategoryShareSlice(total.category, total.amountMinor, percentageTenths[index])
    }
}

fun latestMonthKey(entries: List<LedgerUiEntry>): String =
    entries.maxOfOrNull { it.monthKey } ?: "2026-07"

private fun LedgerUiEntry.isActive(): Boolean = deletedAtEpochMillis == null

private fun LedgerUiEntry.isActiveCashFlow(): Boolean =
    isActive() && flowType != LedgerFlowType.NEUTRAL
