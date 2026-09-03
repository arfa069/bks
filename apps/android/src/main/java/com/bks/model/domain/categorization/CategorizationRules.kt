package com.bks.model.domain.categorization

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind

data class CategorizationRule(
    val id: String,
    val merchantContains: String = "",
    val category: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long = 0
)

typealias CategorizationRulesUpdate =
    (List<CategorizationRule>) -> List<CategorizationRule>

fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> {
    val normalizedRule = rule.copy(
        merchantContains = normalizeMerchantContains(rule.merchantContains)
    )
    val retainedId = firstOrNull { it.id == normalizedRule.id }
        ?.id
        ?: firstOrNull { it.hasSameConditions(normalizedRule) }?.id
    val savedRule = normalizedRule.copy(id = retainedId ?: normalizedRule.id)
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
    val merchantTitle: String
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
            merchantTitle = merchantTitle
        )
    )
    val normalizedCategoryName = suggestion?.category?.trim()
        ?: "退款".takeIf { transactionKind == TransactionKind.REFUND }
        ?: return this
    val categoryId = categories.firstOrNull {
        it.name.equals(normalizedCategoryName, ignoreCase = true)
    }?.id ?: DefaultCategories.idForName(normalizedCategoryName, transactionKind)
        ?.takeIf { defaultId -> categories.any { it.id == defaultId } }
        ?: return this
    return copy(
        suggestedCategoryId = categoryId,
        suggestedCategoryLabel = normalizedCategoryName
    )
}

private fun CategorizationRule.matches(transaction: CategorizationTransaction): Boolean {
    val normalizedMerchantTitle = transaction.merchantTitle.normalizedCondition()
    return merchantContains.merchantKeywords().any { keyword ->
        normalizedMerchantTitle.contains(keyword.normalizedCondition())
    }
}

private fun CategorizationRule.hasSameConditions(other: CategorizationRule): Boolean =
    merchantContains.normalizedMerchantKeywordSet() ==
        other.merchantContains.normalizedMerchantKeywordSet()

private fun CategorizationRule.isUnmodifiedBuiltInRule(): Boolean =
    id.startsWith(BUILT_IN_RULE_PREFIX) && updatedAtEpochMillis == 0L

fun normalizeMerchantContains(value: String): String =
    value.merchantKeywords().joinToString(",")

private fun String.merchantKeywords(): List<String> = split(',')
    .map { keyword -> keyword.trim().replace(CONDITION_WHITESPACE_REGEX, " ") }
    .filter(String::isNotBlank)
    .distinctBy(String::normalizedCondition)

private fun String.normalizedMerchantKeywordSet(): Set<String> =
    merchantKeywords().mapTo(linkedSetOf(), String::normalizedCondition)

private fun String.normalizedCondition(): String =
    trim().replace(CONDITION_WHITESPACE_REGEX, " ").lowercase()

private const val BUILT_IN_RULE_PREFIX = "default-"
private val CONDITION_WHITESPACE_REGEX = Regex("[\\s\\u3000]+")
