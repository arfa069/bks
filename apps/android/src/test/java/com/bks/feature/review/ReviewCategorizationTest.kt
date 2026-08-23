package com.bks.feature.review

import com.bks.feature.categorization.CategorizationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCategorizationTest {
    @Test
    fun reviewEntryMapsToMinimalAiPayloadWithoutEnhancedAuthorization() {
        val payload = sampleEntry().toAiCategorizationPayload(
            categoryCandidates = listOf("餐饮", "交通"),
            enhancedContextGranted = false
        )

        assertEquals("午餐", payload.merchantTitle)
        assertEquals("微信", payload.sourceLabel)
        assertEquals("支出", payload.transactionKind)
        assertEquals("0-50", payload.amountRangeLabel)
        assertEquals(listOf("餐饮", "交通"), payload.categoryCandidates)
        assertFalse(payload.enhancedContext)
        assertNull(payload.note)
        assertNull(payload.rawEvidenceText)
    }

    @Test
    fun reviewEntryMapsOptionalContextOnlyWithEnhancedAuthorization() {
        val payload = sampleEntry().toAiCategorizationPayload(
            categoryCandidates = listOf("餐饮"),
            enhancedContextGranted = true
        )

        assertTrue(payload.enhancedContext)
        assertEquals("客户会议", payload.note)
        assertEquals("微信支付收款凭证 午餐 35.90", payload.rawEvidenceText)
    }

    @Test
    fun reviewEntryAppliesMatchingLocalRule() {
        val rules = listOf(
            CategorizationRule(
                id = "coffee",
                merchantContains = "午餐",
                sourceLabel = "微信",
                transactionKind = "支出",
                category = "餐饮"
            )
        )

        val suggestedEntry = sampleEntry().copy(category = "").applyCategorizationSuggestion(rules)

        assertEquals("餐饮", suggestedEntry.category)
    }

    private fun sampleEntry(): ReviewQueueEntry = ReviewQueueEntry(
        id = "pending-lunch",
        title = "午餐",
        amountMinor = 3590,
        category = "",
        sourceLabel = "微信",
        kindLabel = "支出",
        note = "客户会议",
        rawEvidenceText = "微信支付收款凭证 午餐 35.90"
    )
}
