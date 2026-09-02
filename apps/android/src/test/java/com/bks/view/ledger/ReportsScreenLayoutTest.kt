package com.bks.view.ledger

import com.bks.model.domain.ledger.Category
import com.bks.viewmodel.ledger.LedgerFlowType
import com.bks.viewmodel.ledger.LedgerUiEntry
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w384dp-h853dp-450dpi")
class ReportsScreenLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullCategoryRankingScrollsAndRestoresItsPosition() {
        val entries = List(16) { index ->
            LedgerUiEntry(
                id = "category-$index",
                title = "账目 $index",
                amountMinor = 2_000L - index,
                monthKey = "2026-07",
                transactionTimeText = "2026-07-18 12:00",
                category = "分类 $index",
                sourceLabel = "支付宝",
                kindLabel = "支出",
                flowType = LedgerFlowType.EXPENSE
            )
        }
        var showReports by mutableStateOf(true)
        lateinit var rankingListState: LazyListState
        composeRule.setContent {
            rankingListState = rememberLazyListState()
            if (showReports) {
                ReportsScreen(
                    entries = entries,
                    categoryRankingListState = rankingListState
                )
            }
        }

        composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(2)
        composeRule.onNodeWithTag(ReportTestTags.CATEGORY_CHART)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(4)
        composeRule.onNodeWithTag(ReportTestTags.CASH_FLOW)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(3)
        composeRule.onNodeWithText("分类 2 ¥19.98").assertIsDisplayed()
        composeRule.onNodeWithText("分类 3 ¥19.97").assertDoesNotExist()

        composeRule.onNodeWithTag(ReportTestTags.SHOW_ALL_CATEGORIES)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(ReportTestTags.FULL_CATEGORY_RANKING_TITLE)
            .assertIsDisplayed()

        val rankingBounds = composeRule.onNodeWithTag(ReportTestTags.CATEGORY_RANKING_LIST)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "ranking=$rankingBounds",
            rankingBounds.height > 0f
        )
        composeRule.onNodeWithTag(ReportTestTags.CATEGORY_RANKING_LIST)
            .performScrollToIndex(15)

        var retainedFirstVisibleIndex = 0
        composeRule.runOnIdle {
            retainedFirstVisibleIndex = rankingListState.firstVisibleItemIndex
            assertTrue(retainedFirstVisibleIndex > 0)
        }
        composeRule.onNodeWithText("分类 15 ¥19.85").assertIsDisplayed()
        composeRule.onNodeWithTag(ReportTestTags.BACK_TO_REPORTS).performClick()
        composeRule.onNodeWithTag(ReportTestTags.SHOW_ALL_CATEGORIES).assertIsDisplayed()

        composeRule.runOnIdle { showReports = false }
        composeRule.runOnIdle { showReports = true }
        composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(3)
        composeRule.onNodeWithTag(ReportTestTags.SHOW_ALL_CATEGORIES).performClick()

        composeRule.runOnIdle {
            assertEquals(retainedFirstVisibleIndex, rankingListState.firstVisibleItemIndex)
        }
    }

    @Test
    fun redesignedReportsRemainReachableAcrossWindowSizesAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                ReportsScreen(entries = adaptiveEntries())
            }
        }
        composeRule.waitForIdle()

        listOf(
            TestConfiguration(DpSize(400.dp, 500.dp)),
            TestConfiguration(DpSize(400.dp, 800.dp)),
            TestConfiguration(DpSize(610.dp, 500.dp)),
            TestConfiguration(DpSize(900.dp, 1_000.dp)),
            TestConfiguration(DpSize(400.dp, 500.dp), fontScale = 1.5f)
        ).forEach { configuration ->
            composeRule.runOnIdle {
                forcedSize = configuration.size
                fontScale = configuration.fontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(0)
            composeRule.onNodeWithText("报表").assertIsDisplayed()
            composeRule.onNodeWithTag("report-summary-card").assertIsDisplayed()
            composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(2)
            composeRule.onNodeWithTag(ReportTestTags.CATEGORY_CHART).assertIsDisplayed()
            composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(3)
            composeRule.onNodeWithTag(ReportTestTags.SHOW_ALL_CATEGORIES).assertIsDisplayed()
            composeRule.onNodeWithTag(ReportTestTags.OVERVIEW_LIST).performScrollToIndex(4)
            composeRule.onNodeWithTag(ReportTestTags.CASH_FLOW).assertIsDisplayed()
        }
    }

    private fun adaptiveEntries(): List<LedgerUiEntry> = listOf(
        LedgerUiEntry(
            id = "adaptive-food",
            title = "午餐",
            amountMinor = 3_590,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-18 12:00",
            category = "餐饮",
            sourceLabel = "微信",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE
        ),
        LedgerUiEntry(
            id = "adaptive-ride",
            title = "地铁",
            amountMinor = 600,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-18 08:00",
            category = "交通",
            sourceLabel = "支付宝",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE
        )
    )

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )
}
