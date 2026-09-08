package com.bks.viewmodel.review

import com.bks.model.domain.ledger.ConfidenceState
import java.time.Instant
import java.time.ZoneId

data class ReviewQueueUiState(
    val pendingEntries: List<ReviewEntryUiModel> = emptyList(),
    val ignoredEntries: List<IgnoredReviewEntryUiModel> = emptyList(),
    val operation: ReviewQueueOperation = ReviewQueueOperation.Idle,
    val undoEvent: ReviewQueueUndoEvent? = null,
    val nowEpochMillis: Long,
    val todayStartEpochMillis: Long,
    val isLoaded: Boolean = true
) {
    val sortedPendingEntries: List<ReviewEntryUiModel>
        get() = pendingEntries.sortedWith(
            compareBy<ReviewEntryUiModel> { it.reviewPriority }
                .thenByDescending { it.capturedAtEpochMillis }
        )

    val recoverableIgnoredEntries: List<IgnoredReviewEntryUiModel>
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

data class ReviewEntryUiModel(
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

data class IgnoredReviewEntryUiModel(
    val id: String,
    val originalPendingId: String,
    val entry: ReviewEntryUiModel,
    val ignoredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    companion object {
        fun fromPending(
            entry: ReviewEntryUiModel,
            ignoredAtEpochMillis: Long,
            expiresAtEpochMillis: Long = ignoredAtEpochMillis + IGNORED_RETENTION_MILLIS
        ): IgnoredReviewEntryUiModel =
            IgnoredReviewEntryUiModel(
                id = "ignored-${entry.id}",
                originalPendingId = entry.id,
                entry = entry,
                ignoredAtEpochMillis = ignoredAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
    }
}

const val IGNORED_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

internal fun emptyReviewQueueUiState(nowEpochMillis: Long, zoneId: ZoneId): ReviewQueueUiState =
    ReviewQueueUiState(
        isLoaded = false,
        nowEpochMillis = nowEpochMillis,
        todayStartEpochMillis = Instant.ofEpochMilli(nowEpochMillis)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    )
