package com.bks.feature.review

import com.bks.data.local.ConfidenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueStateTest {
    @Test
    fun evidenceMergeKeepsLegacyAndNamedSourcesSeparate() {
        val merged = mergeReviewEvidenceText(
            "旧版原始文本",
            "[来源 A]\n支付成功 中国电信",
            "[来源 B]\n支付成功 中国电信 ¥2.98"
        )

        assertEquals(
            listOf("原始文本", "来源 A", "来源 B"),
            parseReviewEvidenceText(merged).map { it.first }
        )
        assertTrue(merged.contains("旧版原始文本"))
    }

    @Test
    fun ignoredRecoveryWindowIsDerivedWithoutChangingPersistentLists() {
        val recoverable = ReviewQueueIgnoredEntry.fromPending(
            entry = sampleEntry("recoverable"),
            ignoredAtEpochMillis = NOW - 1,
            expiresAtEpochMillis = NOW + 1
        )
        val expired = ReviewQueueIgnoredEntry.fromPending(
            entry = sampleEntry("expired"),
            ignoredAtEpochMillis = NOW - THIRTY_DAYS_MILLIS - 1,
            expiresAtEpochMillis = NOW - 1
        )
        val state = ReviewQueueState(
            ignoredEntries = listOf(recoverable, expired),
            nowEpochMillis = NOW
        )

        assertEquals(listOf(recoverable), state.recoverableIgnoredEntries)
        assertEquals(listOf(recoverable, expired), state.ignoredEntries)
    }

    @Test
    fun pendingEntriesAreSortedByRiskThenCaptureTime() {
        val state = ReviewQueueState(
            pendingEntries = listOf(
                sampleEntry("quick-new", ConfidenceState.HIGH, NOW + 3),
                sampleEntry("low-old", ConfidenceState.NEEDS_REVIEW, NOW + 1),
                sampleEntry("duplicate-old", ConfidenceState.DUPLICATE_SUSPECT, NOW),
                sampleEntry("duplicate-new", ConfidenceState.DUPLICATE_SUSPECT, NOW + 2)
            )
        )

        assertEquals(
            listOf("duplicate-new", "duplicate-old", "low-old", "quick-new"),
            state.sortedPendingEntries.map { it.id }
        )
    }

    @Test
    fun amountParsingRejectsInvalidValuesWithoutPartialStateMutation() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))

        assertNull(parseReviewAmountMinor("abc"))
        assertEquals(4_580L, parseReviewAmountMinor("45.80"))
        assertEquals("午餐", state.pendingEntries.single().title)
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAt: Long = NOW
    ): ReviewQueueEntry = ReviewQueueEntry(
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
        const val THIRTY_DAYS_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
