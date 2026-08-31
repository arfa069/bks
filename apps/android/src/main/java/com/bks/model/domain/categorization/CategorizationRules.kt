package com.bks.model.domain.categorization

import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.categorizationLabel
data class CategorizationRule(
    val id: String,
    val merchantContains: String = "",
    val titleContains: String = "",
    val sourceLabel: String = "",
    val transactionKind: String = "",
    val category: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long = 0
)

typealias CategorizationRulesUpdate =
    (List<CategorizationRule>) -> List<CategorizationRule>

fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> =
    if (any { it.id == rule.id }) {
        map { existing -> if (existing.id == rule.id) rule else existing }
    } else {
        this + rule
    }

data class CategorizationTransaction(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String
)

data class CategorizationSuggestion(
    val ruleId: String,
    val category: String
)

fun suggestCategory(
    rules: List<CategorizationRule>,
    transaction: CategorizationTransaction
): CategorizationSuggestion? = rules
    .asSequence()
    .filter { it.enabled && it.category.isNotBlank() }
    .filter { it.matches(transaction) }
    .sortedWith(
        compareByDescending<CategorizationRule> { it.priority }
            .thenByDescending { it.updatedAtEpochMillis }
    )
    .firstOrNull()
    ?.let { rule ->
        CategorizationSuggestion(
            ruleId = rule.id,
            category = rule.category
        )
    }

fun PendingEntry.applyCategorizationSuggestion(rules: List<CategorizationRule>): PendingEntry {
    val suggestion = suggestCategory(
        rules,
        CategorizationTransaction(
            merchantTitle = merchantTitle,
            sourceLabel = source.categorizationLabel(),
            transactionKind = transactionKind.categorizationLabel()
        )
    ) ?: return this
    return copy(
        suggestedCategoryId = DefaultCategories.idForName(suggestion.category, transactionKind),
        suggestedCategoryLabel = suggestion.category
    )
}

private fun CategorizationRule.matches(transaction: CategorizationTransaction): Boolean {
    return merchantContains.matchesBlankOrContains(transaction.merchantTitle) &&
        titleContains.matchesBlankOrContains(transaction.merchantTitle) &&
        sourceLabel.matchesBlankOrEquals(transaction.sourceLabel) &&
        transactionKind.matchesBlankOrEquals(transaction.transactionKind)
}

private fun String.matchesBlankOrContains(candidate: String): Boolean {
    return isBlank() || candidate.contains(this, ignoreCase = true)
}

private fun String.matchesBlankOrEquals(candidate: String): Boolean {
    return isBlank() || equals(candidate, ignoreCase = true)
}
