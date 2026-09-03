package com.bks.model.data.local.settings

import com.bks.model.data.local.settings.DefaultCategorizationRules
import com.bks.model.domain.ledger.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCategorizationRulesTest {
    @Test
    fun defaultsAreVisibleEditableRuleRecordsWithStableCoverage() {
        val rules = DefaultCategorizationRules.rules

        assertEquals(9, rules.size)
        assertEquals(rules.size, rules.map { it.id }.distinct().size)
        assertTrue(rules.all { it.id.startsWith("default-") })
        assertTrue(rules.all { it.enabled && it.category.isNotBlank() })
        assertEquals(
            setOf("餐饮", "交通", "购物", "住房", "医疗", "通讯", "工资", "退款"),
            rules.map { it.category }.toSet()
        )
        assertEquals(
            "餐饮",
            rules.single { it.merchantContains == "麦当劳" }.category
        )
        assertEquals(
            "通讯",
            rules.single { it.merchantContains == "中国电信" }.category
        )
    }
}
