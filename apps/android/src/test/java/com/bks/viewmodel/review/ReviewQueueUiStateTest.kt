package com.bks.viewmodel.review

import com.bks.model.domain.ledger.ConfidenceState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewQueueUiStateTest {
    @Test
    fun ignoredRecoveryWindowIsDerivedWithoutChangingPersistentLists() {
        val recoverable = IgnoredReviewEntryUiModel.fromPending(
            entry = sampleEntry("recoverable"),
            ignoredAtEpochMillis = NOW - 1,
            expiresAtEpochMillis = NOW + 1
        )
        val expired = IgnoredReviewEntryUiModel.fromPending(
            entry = sampleEntry("expired"),
            ignoredAtEpochMillis = NOW - THIRTY_DAYS_MILLIS - 1,
            expiresAtEpochMillis = NOW - 1
        )
        val state = ReviewQueueUiState(
            ignoredEntries = listOf(recoverable, expired),
            nowEpochMillis = NOW,
            todayStartEpochMillis = TODAY_START
        )

        assertEquals(listOf(recoverable), state.recoverableIgnoredEntries)
        assertEquals(listOf(recoverable, expired), state.ignoredEntries)
    }

    @Test
    fun pendingEntriesAreSortedByRiskThenCaptureTime() {
        val state = ReviewQueueUiState(
            pendingEntries = listOf(
                sampleEntry("quick-new", ConfidenceState.HIGH, NOW + 3),
                sampleEntry("low-old", ConfidenceState.NEEDS_REVIEW, NOW + 1),
                sampleEntry("duplicate-old", ConfidenceState.DUPLICATE_SUSPECT, NOW),
                sampleEntry("duplicate-new", ConfidenceState.DUPLICATE_SUSPECT, NOW + 2)
            ),
            nowEpochMillis = NOW,
            todayStartEpochMillis = TODAY_START
        )

        assertEquals(
            listOf("duplicate-new", "duplicate-old", "low-old", "quick-new"),
            state.sortedPendingEntries.map { it.id }
        )
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAt: Long = NOW
    ): ReviewEntryUiModel = ReviewEntryUiModel(
        id = id,
        title = "午餐",
        amountMinor = 3_590,
        transactionTimeText = "2026-07-08 12:20",
        categoryId = "food",
        category = "餐饮",
        fundingAccountId = 42L,
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = confidence,
        capturedAtEpochMillis = capturedAt,
        captureTimeText = "2026-07-08 12:21"
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val TODAY_START = 1_783_425_600_000L
        const val THIRTY_DAYS_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
