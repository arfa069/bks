package com.bks.model.domain.categorization

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
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

fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> {
    val retainedId = firstOrNull { it.id == rule.id }
        ?.id
        ?: firstOrNull { it.hasSameConditions(rule) }?.id
    val savedRule = rule.copy(id = retainedId ?: rule.id)
    return asSequence()
        .filterNot { existing ->
            existing.id != savedRule.id && existing.hasSameConditions(savedRule)
        }
        .map { existing -> if (existing.id == savedRule.id) savedRule else existing }
        .toList()
        .let { rules ->
            if (rules.any { it.id == savedRule.id }) rules else rules + savedRule
        }
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
        compareBy<CategorizationRule> { it.isUnmodifiedBuiltInRule() }
            .thenByDescending { it.priority }
            .thenByDescending { it.updatedAtEpochMillis }
    )
    .firstOrNull()
    ?.let { rule ->
        CategorizationSuggestion(
            ruleId = rule.id,
            category = rule.category
        )
    }

fun PendingEntry.applyCategorizationSuggestion(
    rules: List<CategorizationRule>,
    categories: List<Category>
): PendingEntry {
    val suggestion = suggestCategory(
        rules,
        CategorizationTransaction(
            merchantTitle = merchantTitle,
            sourceLabel = source.categorizationLabel(),
            transactionKind = transactionKind.categorizationLabel()
        )
    ) ?: return this
    val normalizedCategoryName = suggestion.category.trim()
    val categoryId = categories.firstOrNull {
        it.name.equals(normalizedCategoryName, ignoreCase = true)
    }?.id ?: DefaultCategories.idForName(normalizedCategoryName, transactionKind)
        ?.takeIf { defaultId -> categories.any { it.id == defaultId } }
        ?: return this
    return copy(
        suggestedCategoryId = categoryId,
        suggestedCategoryLabel = suggestion.category
    )
}

private fun CategorizationRule.matches(transaction: CategorizationTransaction): Boolean {
    return merchantContains.matchesBlankOrContains(transaction.merchantTitle) &&
        titleContains.matchesBlankOrContains(transaction.merchantTitle) &&
        effectiveSourceLabel().matchesBlankOrEquals(transaction.sourceLabel) &&
        transactionKind.matchesBlankOrEquals(transaction.transactionKind)
}

private fun CategorizationRule.hasSameConditions(other: CategorizationRule): Boolean =
    merchantContains.normalizedCondition() == other.merchantContains.normalizedCondition() &&
        titleContains.normalizedCondition() == other.titleContains.normalizedCondition() &&
        effectiveSourceLabel().normalizedCondition() == other.effectiveSourceLabel().normalizedCondition() &&
        transactionKind.normalizedCondition() == other.transactionKind.normalizedCondition()

private fun CategorizationRule.effectiveSourceLabel(): String =
    sourceLabel.takeUnless {
        id.startsWith(LEARNED_BILL_RULE_PREFIX) &&
            it.equals(LEGACY_OTHER_SOURCE_LABEL, ignoreCase = true)
    }.orEmpty()

private fun CategorizationRule.isUnmodifiedBuiltInRule(): Boolean =
    id.startsWith(BUILT_IN_RULE_PREFIX) && updatedAtEpochMillis == 0L

private fun String.matchesBlankOrContains(candidate: String): Boolean {
    val condition = normalizedCondition()
    return condition.isBlank() || candidate.normalizedCondition().contains(condition)
}

private fun String.matchesBlankOrEquals(candidate: String): Boolean {
    val condition = normalizedCondition()
    return condition.isBlank() || condition == candidate.normalizedCondition()
}

private fun String.normalizedCondition(): String =
    trim().replace(CONDITION_WHITESPACE_REGEX, " ").lowercase()

private const val BUILT_IN_RULE_PREFIX = "default-"
private const val LEARNED_BILL_RULE_PREFIX = "rule-bill-"
private const val LEGACY_OTHER_SOURCE_LABEL = "其他应用"
private val CONDITION_WHITESPACE_REGEX = Regex("[\\s\\u3000]+")
