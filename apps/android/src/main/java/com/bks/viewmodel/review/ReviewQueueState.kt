package com.bks.viewmodel.review

import com.bks.viewmodel.ledger.label
import com.bks.model.domain.ledger.ConfidenceState
import java.math.BigDecimal
import java.math.RoundingMode

data class ReviewQueueState(
    val pendingEntries: List<ReviewQueueEntry> = emptyList(),
    val ignoredEntries: List<ReviewQueueIgnoredEntry> = emptyList(),
    val operation: ReviewQueueOperation = ReviewQueueOperation.Idle,
    val undoEvent: ReviewQueueUndoEvent? = null,
    val nowEpochMillis: Long = SAMPLE_NOW_EPOCH_MILLIS,
    val todayStartEpochMillis: Long = SAMPLE_TODAY_START_EPOCH_MILLIS
) {
    val sortedPendingEntries: List<ReviewQueueEntry>
        get() = pendingEntries.sortedWith(
            compareBy<ReviewQueueEntry> { it.reviewPriority }
                .thenByDescending { it.capturedAtEpochMillis }
        )

    val recoverableIgnoredEntries: List<ReviewQueueIgnoredEntry>
        get() = ignoredEntries.filter { it.expiresAtEpochMillis > nowEpochMillis }

    val duplicateSuspectCount: Int
        get() = pendingEntries.count { it.confidence == ConfidenceState.DUPLICATE_SUSPECT }

    val todaysNewlyCapturedCount: Int
        get() = pendingEntries.count { it.capturedAtEpochMillis >= todayStartEpochMillis }
}

sealed interface ReviewQueueOperation {
    data object Idle : ReviewQueueOperation

    data class InFlight(
        val stableId: String,
        val kind: ReviewQueueOperationKind
    ) : ReviewQueueOperation
}

enum class ReviewQueueOperationKind {
    EDIT,
    CONFIRM,
    IGNORE,
    RECOVER,
    UNDO_CONFIRM,
    UNDO_IGNORE
}

data class ReviewQueueUndoEvent(
    val eventId: String,
    val message: String
)

data class ReviewAiSuggestionResult(
    val category: String? = null,
    val message: String
)

data class ReviewQueueEntry(
    val id: String,
    val title: String = "",
    val amountMinor: Long = 0,
    val transactionTimeText: String = "",
    val categoryId: String? = null,
    val category: String = "",
    val fundingAccountId: Long? = null,
    val fundingAccountLabel: String = "",
    val sourceLabel: String = "",
    val kindLabel: String = "",
    val captureReasonLabel: String = "",
    val confidence: ConfidenceState = ConfidenceState.HIGH,
    val capturedAtEpochMillis: Long = 0,
    val captureTimeText: String = "",
    val note: String? = null,
    val rawEvidenceText: String = "",
    val parsedFields: List<String> = emptyList(),
    val originPendingId: String? = null
) {
    val reviewPriority: Int
        get() = when (confidence) {
            ConfidenceState.DUPLICATE_SUSPECT -> 0
            ConfidenceState.NEEDS_REVIEW -> 1
            ConfidenceState.HIGH -> 2
        }
}

internal fun mergeReviewEvidenceText(vararg evidenceTexts: String): String {
    val sections = linkedMapOf<String, String>()
    evidenceTexts.filter(String::isNotBlank).forEach { evidence ->
        parseReviewEvidenceText(evidence).forEach { (label, text) ->
            val existing = sections[label]
            when {
                existing == null -> sections[label] = text
                existing != text -> sections[label] = "$existing\n---\n$text"
            }
        }
    }
    return sections.entries.joinToString("\n\n") { (label, text) -> "[$label]\n$text" }
}

internal fun parseReviewEvidenceText(evidenceText: String): List<Pair<String, String>> {
    val matches = REVIEW_EVIDENCE_HEADER_REGEX.findAll(evidenceText).toList()
    if (matches.isEmpty()) {
        return evidenceText.trim().takeIf(String::isNotBlank)
            ?.let { listOf("原始文本" to it) }
            .orEmpty()
    }
    return matches.mapIndexedNotNull { index, match ->
        val start = match.range.last + 1
        val end = matches.getOrNull(index + 1)?.range?.first ?: evidenceText.length
        evidenceText.substring(start, end).trim().takeIf(String::isNotBlank)?.let { text ->
            match.groupValues[1] to text
        }
    }
}

private val REVIEW_EVIDENCE_HEADER_REGEX = Regex(
    pattern = "(?m)^\\[([^]\\r\\n]+)]\\s*$"
)

data class ReviewQueueIgnoredEntry(
    val id: String,
    val originalPendingId: String,
    val entry: ReviewQueueEntry,
    val ignoredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    companion object {
        fun fromPending(
            entry: ReviewQueueEntry,
            ignoredAtEpochMillis: Long = SAMPLE_NOW_EPOCH_MILLIS,
            expiresAtEpochMillis: Long = ignoredAtEpochMillis + IGNORED_RETENTION_MILLIS
        ): ReviewQueueIgnoredEntry =
            ReviewQueueIgnoredEntry(
                id = "ignored-${entry.id}",
                originalPendingId = entry.id,
                entry = entry,
                ignoredAtEpochMillis = ignoredAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
    }
}

fun formatAmount(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "¥$yuan.${cents.toString().padStart(2, '0')}"
}

fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

fun parseReviewAmountMinor(text: String): Long? = runCatching {
    BigDecimal(text.trim())
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
}.getOrNull()

const val IGNORED_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
const val SAMPLE_NOW_EPOCH_MILLIS: Long = 1_783_468_800_000L
const val SAMPLE_TODAY_START_EPOCH_MILLIS: Long = 1_783_425_600_000L
