package com.bks.viewmodel.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewEvidenceUiTest {
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
}
