package com.bks.viewmodel.review

import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.categorization.AiCategorizationPayload
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationTransaction
import com.bks.model.domain.categorization.suggestCategory
fun ReviewQueueEntry.toAiCategorizationPayload(
    categoryCandidates: List<String>,
    enhancedContextGranted: Boolean
): AiCategorizationPayload = AiCategorizationPayload(
    merchantTitle = title,
    sourceLabel = sourceLabel,
    transactionKind = kindLabel,
    amountRangeLabel = amountRangeLabel(amountMinor),
    categoryCandidates = categoryCandidates,
    enhancedContext = enhancedContextGranted,
    note = note.takeIf { enhancedContextGranted },
    rawEvidenceText = rawEvidenceText.takeIf { enhancedContextGranted }
)

fun ReviewQueueEntry.applyCategorizationSuggestion(
    rules: List<CategorizationRule>
): ReviewQueueEntry {
    val suggestion = suggestCategory(
        rules = rules,
        transaction = CategorizationTransaction(
            merchantTitle = title
        )
    )
    val category = suggestion?.category ?: "退款".takeIf { kindLabel == "退款" } ?: return this

    return copy(category = category)
}

private fun amountRangeLabel(amountMinor: Long): String {
    val yuan = amountMinor / 100
    return when {
        yuan < 50 -> "0-50"
        yuan < 200 -> "50-200"
        yuan < 500 -> "200-500"
        yuan < 1000 -> "500-1000"
        else -> "1000+"
    }
}
