package com.bks.model.domain.categorization

import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationTransaction
import com.bks.model.domain.categorization.suggestCategory
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizationRulesTest {
    @Test
    fun commaSeparatedMerchantsMatchAnyNormalizedKeyword() {
        val rules = listOf(
            sampleRule(
                id = "coffee",
                merchantContains = " 星巴克, 瑞幸咖啡,星巴克 ",
                category = "餐饮"
            )
        )

        val suggestion = suggestCategory(rules, sampleTransaction("瑞幸咖啡深圳店"))

        assertEquals("餐饮", suggestion?.category)
        assertEquals("coffee", suggestion?.ruleId)
        assertEquals("星巴克,瑞幸咖啡", normalizeMerchantContains(rules.single().merchantContains))
    }

    @Test
    fun higherPriorityRuleWinsConflict() {
        val rules = listOf(
            sampleRule(id = "general-food", merchantContains = "星巴克", category = "餐饮", priority = 10),
            sampleRule(id = "work", merchantContains = "星巴克", category = "工作餐", priority = 100)
        )

        val suggestion = suggestCategory(rules, sampleTransaction(merchantTitle = "星巴克"))

        assertEquals("工作餐", suggestion?.category)
        assertEquals("work", suggestion?.ruleId)
    }

    @Test
    fun disabledRuleIsIgnoredAndTieUsesNewestRule() {
        val rules = listOf(
            sampleRule(id = "disabled", merchantContains = "地铁", category = "交通", priority = 100, enabled = false),
            sampleRule(id = "old", merchantContains = "地铁", category = "交通", priority = 10, updatedAtEpochMillis = 1),
            sampleRule(id = "new", merchantContains = "地铁", category = "通勤", priority = 10, updatedAtEpochMillis = 2)
        )

        val suggestion = suggestCategory(rules, sampleTransaction(merchantTitle = "地铁出行"))

        assertEquals("通勤", suggestion?.category)
        assertEquals("new", suggestion?.ruleId)
    }

    @Test
    fun unmatchedRuleReturnsNull() {
        val rules = listOf(sampleRule(merchantContains = "便利店", category = "购物"))

        assertNull(suggestCategory(rules, sampleTransaction(merchantTitle = "地铁")))
    }

    @Test
    fun userRuleWinsBuiltInRuleRegardlessOfNumericPriority() {
        val rules = listOf(
            sampleRule(
                id = "default-telecom",
                merchantContains = "中国电信",
                category = "通讯",
                priority = 200
            ),
            sampleRule(
                id = "rule-1",
                merchantContains = "中国电信",
                category = "家庭",
                priority = 0,
                updatedAtEpochMillis = 1
            )
        )

        val suggestion = suggestCategory(rules, sampleTransaction(merchantTitle = "中国电信"))

        assertEquals("家庭", suggestion?.category)
        assertEquals("rule-1", suggestion?.ruleId)
    }

    @Test
    fun emptyMerchantRuleNeverMatches() {
        assertNull(
            suggestCategory(
                listOf(sampleRule(merchantContains = " ,  ,", category = "通讯")),
                sampleTransaction("中国电信")
            )
        )
    }

    @Test
    fun upsertMergesSameMerchantSetAndKeepsExistingId() {
        val existing = sampleRule(
            id = "rule-bill-old-1",
            merchantContains = "中国电信,麦当劳",
            category = "日用"
        )
        val correction = sampleRule(
            id = "rule-bill-new-2",
            merchantContains = " 麦当劳, 中国电信,麦当劳 ",
            category = "通讯",
            updatedAtEpochMillis = 2
        )

        val merged = listOf(existing).upsert(correction)

        assertEquals(1, merged.size)
        assertEquals("rule-bill-old-1", merged.single().id)
        assertEquals("通讯", merged.single().category)
        assertEquals("麦当劳,中国电信", merged.single().merchantContains)
    }

    @Test
    fun pendingSuggestionUsesRealCustomCategoryId() {
        val entry = samplePendingEntry()
        val customCategory = Category(
            id = "custom-telecom",
            name = "固定支出",
            kind = TransactionKind.EXPENSE,
            sortOrder = 1,
            isSystem = false,
            createdAtEpochMillis = 1
        )
        val rules = listOf(
            sampleRule(
                merchantContains = "中国电信",
                category = "固定支出"
            )
        )

        val suggested = entry.applyCategorizationSuggestion(rules, listOf(customCategory))

        assertEquals("custom-telecom", suggested.suggestedCategoryId)
        assertEquals("固定支出", suggested.suggestedCategoryLabel)
    }

    @Test
    fun missingCategoryTargetLeavesPendingEntryUncategorized() {
        val rules = listOf(
            sampleRule(merchantContains = "中国电信", category = "已删除分类")
        )

        val suggested = samplePendingEntry().applyCategorizationSuggestion(rules, emptyList())

        assertNull(suggested.suggestedCategoryId)
        assertNull(suggested.suggestedCategoryLabel)
    }

    @Test
    fun refundUsesDeterministicCategoryWhenNoMerchantRuleMatches() {
        val refund = samplePendingEntry().copy(transactionKind = TransactionKind.REFUND)

        val suggested = refund.applyCategorizationSuggestion(
            rules = emptyList(),
            categories = DefaultCategories.systemDefaults(createdAtEpochMillis = 0)
        )

        assertEquals("refund", suggested.suggestedCategoryId)
        assertEquals("退款", suggested.suggestedCategoryLabel)
    }

    @Suppress("LongParameterList")
    private fun sampleRule(
        id: String = "rule-1",
        merchantContains: String = "",
        category: String,
        priority: Int = 0,
        enabled: Boolean = true,
        updatedAtEpochMillis: Long = 0
    ): CategorizationRule = CategorizationRule(
        id = id,
        merchantContains = merchantContains,
        category = category,
        priority = priority,
        enabled = enabled,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    private fun sampleTransaction(merchantTitle: String): CategorizationTransaction =
        CategorizationTransaction(merchantTitle = merchantTitle)

    private fun samplePendingEntry(): PendingEntry = PendingEntry(
        id = "bill-1",
        source = PaymentSource.ALIPAY,
        captureReason = CaptureReason.ACCESSIBILITY_AUTO,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 399,
        currency = "CNY",
        merchantTitle = "中国电信",
        transactionTimeEpochMillis = 1,
        capturedAtEpochMillis = 1,
        suggestedCategoryId = null,
        fundingAccountId = null,
        fundingAccountLabel = null,
        note = null,
        evidenceSummary = null,
        parsedFieldsText = null,
        suggestedCategoryLabel = null
    )
}
