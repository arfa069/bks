package com.bks.view.ledger

import com.bks.model.domain.ledger.Category
import com.bks.viewmodel.ledger.LedgerFlowType
import com.bks.viewmodel.ledger.LedgerEntryUiModel
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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
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
@Config(sdk = [35])
class LedgerListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ledgerShowsMonthlySummarySearchFilterAndEntries() {
        composeRule.setContent {
            LedgerScreen(
                entries = sampleEntries(),
                activeLedgerName = "日常账本"
            )
        }

        composeRule.onNodeWithText("日常账本").assertIsDisplayed()
        composeRule.onNodeWithText("账本").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥41.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥12.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月净额").assertIsDisplayed()
        composeRule.onNodeWithText("-¥29.00").assertIsDisplayed()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()

        val searchBounds = composeRule.onNodeWithTag(LedgerTestTags.SEARCH_FIELD)
            .fetchSemanticsNode()
            .boundsInRoot
        val filterBounds = composeRule.onNodeWithTag(LedgerTestTags.FILTER_BUTTON)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(searchBounds.width > filterBounds.width)
        assertEquals(searchBounds.center.y, filterBounds.center.y, 1f)

        composeRule.onNodeWithText("搜索商户或备注").performTextInput("地铁")
        composeRule.onNodeWithText("地铁出行").assertIsDisplayed()
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(3)
        composeRule.onNodeWithText("来源").assertIsDisplayed()
        composeRule.onNodeWithText("分类").assertIsDisplayed()
    }

    @Test
    fun ledgerCanNavigateToHistoricalMonthEntries() {
        val historicalEntry = sampleEntries().first().copy(
            id = "historical-payment",
            title = "历史支付",
            amountMinor = 22_400,
            monthKey = "2026-06",
            transactionTimeText = "2026-06-01 16:57"
        )
        composeRule.setContent {
            LedgerScreen(entries = sampleEntries() + historicalEntry)
        }

        composeRule.onNodeWithText("2026-07 明细").assertIsDisplayed()
        composeRule.onNodeWithText("上一月").performClick()

        composeRule.onNodeWithText("2026-06 明细").assertIsDisplayed()
        composeRule.onNodeWithText("历史支付").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥224.00").assertIsDisplayed()
        composeRule.onNodeWithText("下一月").performClick()
        composeRule.onNodeWithText("2026-07 明细").assertIsDisplayed()
    }

    @Test
    fun ledgerSearchFiltersAndMonthSurviveRestoration() {
        val historicalEntry = sampleEntries().first().copy(
            id = "historical-payment",
            title = "历史支付",
            monthKey = "2026-06",
            transactionTimeText = "2026-06-01 16:57"
        )
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            LedgerList(
                entries = sampleEntries() + historicalEntry,
                entryListState = rememberLazyListState(),
                activeLedgerName = "本地账本",
                onEntryClick = {},
                onLedgerBooksClick = {},
                onFundingAccountsClick = {},
                onRecentlyDeletedClick = {},
                onNavigateHome = {}
            )
        }

        composeRule.onNodeWithTag(LedgerTestTags.SEARCH_FIELD).performTextInput("历史")
        composeRule.onNodeWithText("上一月").performClick()
        composeRule.onNodeWithText("2026-06 明细").assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.FILTER_BUTTON).performClick()
        composeRule.onNodeWithText("来源").performTextInput("微信")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(LedgerTestTags.SEARCH_FIELD).assertTextContains("历史")
        composeRule.onNodeWithText("来源").assertTextContains("微信")
        composeRule.onNodeWithTag(LedgerTestTags.FILTER_BUTTON).performClick()
        composeRule.onNodeWithText("2026-06 明细").assertExists()
        composeRule.onNodeWithText("历史支付").assertExists()
    }

    @Test
    fun ledgerListScrollsToOldestEntryAndKeepsMonthStateReachable() {
        val entries = List(20) { index ->
            sampleEntries().first().copy(
                id = "entry-$index",
                title = "账目 $index",
                transactionTimeEpochMillis = index.toLong()
            )
        }
        composeRule.setContent {
            LedgerScreen(
                entries = entries,
                activeLedgerName = "日常账本"
            )
        }

        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(19)
        composeRule.onNodeWithText("账目 0").assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(3)
        composeRule.onNodeWithText("2026-07 明细").assertIsDisplayed()
    }

    @Test
    fun ledgerRestoresProvidedListPositionAfterLeavingComposition() {
        val entries = List(20) { index ->
            sampleEntries().first().copy(
                id = "entry-$index",
                title = "账目 $index",
                transactionTimeEpochMillis = index.toLong()
            )
        }
        var showLedger by mutableStateOf(true)
        lateinit var entryListState: LazyListState
        composeRule.setContent {
            entryListState = rememberLazyListState()
            if (showLedger) {
                LedgerScreen(
                    entries = entries,
                    entryListState = entryListState
                )
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(19)
        var firstVisibleItemIndex = 0
        composeRule.runOnIdle {
            firstVisibleItemIndex = entryListState.firstVisibleItemIndex
            assertTrue(firstVisibleItemIndex > 0)
            showLedger = false
        }
        composeRule.runOnIdle { showLedger = true }

        composeRule.runOnIdle {
            assertEquals(firstVisibleItemIndex, entryListState.firstVisibleItemIndex)
        }
        composeRule.onNodeWithText("账目 0").assertIsDisplayed()
    }

    @Test
    fun moreMenuProvidesLedgerFundingAccountAndRecentlyDeletedManagement() {
        composeRule.setContent {
            LedgerScreen(entries = emptyList())
        }

        composeRule.onNodeWithTag(LedgerTestTags.MORE_MENU).performClick()

        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_LEDGERS).assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_FUNDING_ACCOUNTS).assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.RECENTLY_DELETED).assertIsDisplayed()
    }

    @Test
    fun redesignedLedgerRemainsReachableAcrossWindowSizesAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                LedgerScreen(
                    entries = sampleEntries().take(1),
                    activeLedgerName = "自适应账本"
                )
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
            composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(0)
            composeRule.onNodeWithText("自适应账本").assertIsDisplayed()
            composeRule.onNodeWithTag("ledger-summary-card").assertIsDisplayed()
            composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(2)
            composeRule.onNodeWithTag(LedgerTestTags.SEARCH_FIELD).assertIsDisplayed()
            composeRule.onNodeWithTag(LedgerTestTags.FILTER_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(4)
            composeRule.onNodeWithText("午餐").assertIsDisplayed()
        }

        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(2)
        composeRule.onNodeWithTag(LedgerTestTags.FILTER_BUTTON).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(3)
        composeRule.onNodeWithText("交易类型").assertIsDisplayed()
    }

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )

    private fun sampleEntries(): List<LedgerEntryUiModel> = listOf(
        LedgerEntryUiModel(
            id = "food",
            title = "午餐",
            amountMinor = 3590,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-08 12:20",
            category = "餐饮",
            sourceLabel = "微信",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE,
            note = "客户会议"
        ),
        LedgerEntryUiModel(
            id = "ride",
            title = "地铁出行",
            amountMinor = 600,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-08 08:10",
            category = "交通",
            sourceLabel = "支付宝",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE
        ),
        LedgerEntryUiModel(
            id = "refund",
            title = "退款到账",
            amountMinor = 1290,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-07 21:10",
            category = "退款",
            sourceLabel = "微信",
            kindLabel = "退款",
            flowType = LedgerFlowType.INCOME
        )
    )
}
