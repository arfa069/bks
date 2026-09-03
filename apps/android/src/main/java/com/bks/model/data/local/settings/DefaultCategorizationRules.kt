package com.bks.model.data.local.settings

import com.bks.model.data.local.database.CategorizationRuleEntity
import androidx.sqlite.db.SupportSQLiteDatabase

object DefaultCategorizationRules {
    private val merchantRules = listOf(
        defaultMerchantRule(
            id = "default-mcdonalds",
            merchantContains = "麦当劳",
            transactionKind = "支出",
            category = "餐饮",
            priority = 100
        ),
        defaultMerchantRule(
            id = "default-china-telecom",
            merchantContains = "中国电信",
            transactionKind = "支出",
            category = "通讯",
            priority = 100
        )
    )

    val rules: List<CategorizationRuleEntity> = listOf(
        defaultRule(
            id = "default-refund",
            transactionKind = "退款",
            category = "退款",
            priority = 200
        ),
        defaultRule(
            id = "default-salary",
            titleContains = "工资",
            transactionKind = "收入",
            category = "工资",
            priority = 190
        ),
        defaultRule(
            id = "default-food",
            titleContains = "餐饮",
            transactionKind = "支出",
            category = "餐饮",
            priority = 100
        ),
        defaultRule(
            id = "default-transport",
            titleContains = "地铁",
            transactionKind = "支出",
            category = "交通",
            priority = 100
        ),
        defaultRule(
            id = "default-shopping",
            titleContains = "超市",
            transactionKind = "支出",
            category = "购物",
            priority = 100
        ),
        defaultRule(
            id = "default-housing",
            titleContains = "物业",
            transactionKind = "支出",
            category = "住房",
            priority = 100
        ),
        defaultRule(
            id = "default-healthcare",
            titleContains = "医院",
            transactionKind = "支出",
            category = "医疗",
            priority = 100
        )
    ) + merchantRules

    fun insertMissing(db: SupportSQLiteDatabase) {
        insertMissing(db, rules)
    }

    fun insertNewMerchantRules(db: SupportSQLiteDatabase) {
        insertMissing(db, merchantRules)
    }

    private fun insertMissing(
        db: SupportSQLiteDatabase,
        rules: List<CategorizationRuleEntity>
    ) {
        rules.forEach { rule ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO `categorization_rules` (
                    `id`, `merchant_contains`, `title_contains`, `source_label`,
                    `transaction_kind`, `category`, `priority`, `enabled`,
                    `updated_at_epoch_millis`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    rule.id,
                    rule.merchantContains,
                    rule.titleContains,
                    rule.sourceLabel,
                    rule.transactionKind,
                    rule.category,
                    rule.priority,
                    if (rule.enabled) 1 else 0,
                    rule.updatedAtEpochMillis
                )
            )
        }
    }

    private fun defaultRule(
        id: String,
        titleContains: String = "",
        transactionKind: String,
        category: String,
        priority: Int
    ): CategorizationRuleEntity = CategorizationRuleEntity(
        id = id,
        merchantContains = "",
        titleContains = titleContains,
        sourceLabel = "",
        transactionKind = transactionKind,
        category = category,
        priority = priority,
        enabled = true,
        updatedAtEpochMillis = 0
    )

    private fun defaultMerchantRule(
        id: String,
        merchantContains: String,
        transactionKind: String,
        category: String,
        priority: Int
    ): CategorizationRuleEntity = CategorizationRuleEntity(
        id = id,
        merchantContains = merchantContains,
        titleContains = "",
        sourceLabel = "",
        transactionKind = transactionKind,
        category = category,
        priority = priority,
        enabled = true,
        updatedAtEpochMillis = 0
    )
}
