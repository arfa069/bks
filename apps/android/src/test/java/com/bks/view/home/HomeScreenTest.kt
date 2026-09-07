package com.bks.view.home

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bks.model.domain.ledger.TransactionKind
import com.bks.view.common.theme.BksTheme
import com.bks.viewmodel.home.HomeAttentionType
import com.bks.viewmodel.home.HomeAttentionUiModel
import com.bks.viewmodel.home.HomeLedgerBookUiModel
import com.bks.viewmodel.home.HomeMonthlyOverviewUiModel
import com.bks.viewmodel.home.HomeRecentEntryUiModel
import com.bks.viewmodel.home.HomeUiState
import com.bks.viewmodel.ledger.LedgerFlowType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOverviewAttentionAndRecentEntriesWithActions() {
        val reportsClicks = AtomicInteger()
        val reviewClicks = AtomicInteger()
        val automaticClicks = AtomicInteger()
        val syncClicks = AtomicInteger()
        val ledgerClicks = AtomicInteger()
        val openedEntry = AtomicReference<String?>()
        composeRule.setContent {
            BksTheme {
                HomeScreen(
                    state = populatedState(),
                    actions = HomeScreenActions(
                        onOpenReports = reportsClicks::incrementAndGet,
                        onOpenReview = reviewClicks::incrementAndGet,
                        onOpenAutomaticBookkeeping = automaticClicks::incrementAndGet,
                        onOpenLedgerSync = syncClicks::incrementAndGet,
                        onOpenLedger = ledgerClicks::incrementAndGet,
                        onOpenLedgerEntry = openedEntry::set
                    )
                )
            }
        }

        composeRule.onNodeWithText("主页").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月结余").assertIsDisplayed()
        composeRule.onNodeWithText("+¥2430.00").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.OVERVIEW).performClick()
        composeRule.onNodeWithTag(HomeTestTags.attention(HomeAttentionType.REVIEW))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(HomeTestTags.attention(HomeAttentionType.AUTOMATIC_BOOKKEEPING))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(5)
        composeRule.onNodeWithTag(HomeTestTags.attention(HomeAttentionType.LEDGER_SYNC))
            .performClick()
        composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(6)
        composeRule.onNodeWithTag(HomeTestTags.VIEW_ALL).performScrollTo().performClick()
        composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(7)
        composeRule.onNodeWithTag(HomeTestTags.recentEntry("lunch"))
            .performClick()

        assertEquals(1, reportsClicks.get())
        assertEquals(1, reviewClicks.get())
        assertEquals(1, automaticClicks.get())
        assertEquals(1, syncClicks.get())
        assertEquals(1, ledgerClicks.get())
        assertEquals("lunch", openedEntry.get())
    }

    @Test
    fun multipleLedgerSelectorSelectsANonActiveLedger() {
        val selectedLedger = AtomicReference<String?>()
        composeRule.setContent {
            BksTheme {
                HomeScreen(
                    state = populatedState(),
                    actions = HomeScreenActions(onSelectLedger = selectedLedger::set)
                )
            }
        }

        composeRule.onNodeWithTag(HomeTestTags.LEDGER_SELECTOR).performClick()
        composeRule.onNodeWithTag(HomeTestTags.ledgerOption("travel")).performClick()

        assertEquals("travel", selectedLedger.get())
    }

    @Test
    fun emptyStateHidesAttentionAndKeepsOverviewAndRecentDestinations() {
        composeRule.setContent {
            BksTheme {
                HomeScreen(
                    state = HomeUiState(
                        activeLedgerName = "默认账本",
                        ledgerBooks = listOf(HomeLedgerBookUiModel("default", "默认账本", true)),
                        ledgerSelectionEnabled = true,
                        overview = null,
                        attentionItems = emptyList(),
                        recentEntries = emptyList()
                    ),
                    actions = HomeScreenActions()
                )
            }
        }

        composeRule.onNodeWithText("需要处理").assertDoesNotExist()
        composeRule.onNodeWithText("暂无可分析的收支").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(3)
        composeRule.onNodeWithTag(HomeTestTags.RECENT_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("还没有账目").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.VIEW_ALL).assertIsDisplayed()
    }

    @Test
    fun contentRemainsReachableAcrossWindowSizesAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                BksTheme {
                    HomeScreen(state = populatedState(), actions = HomeScreenActions())
                }
            }
        }

        listOf(
            TestConfiguration(DpSize(400.dp, 500.dp)),
            TestConfiguration(DpSize(610.dp, 700.dp)),
            TestConfiguration(DpSize(900.dp, 1_000.dp)),
            TestConfiguration(DpSize(400.dp, 600.dp), fontScale = 1.5f)
        ).forEach { configuration ->
            composeRule.runOnIdle {
                forcedSize = configuration.size
                fontScale = configuration.fontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(0)
            composeRule.onNodeWithText("主页").assertIsDisplayed()
            composeRule.onNodeWithTag(HomeTestTags.OVERVIEW).assertIsDisplayed()
            composeRule.onNodeWithTag(HomeTestTags.SCREEN).performScrollToIndex(7)
            composeRule.onNodeWithTag(HomeTestTags.recentEntry("lunch"))
                .assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag(HomeTestTags.recentEntry("lunch"))
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(bounds.width > 0f && bounds.height > 0f)
        }
    }

    private fun populatedState() = HomeUiState(
        activeLedgerName = "日常账本",
        ledgerBooks = listOf(
            HomeLedgerBookUiModel("daily", "日常账本", true),
            HomeLedgerBookUiModel("travel", "旅行账本", false)
        ),
        ledgerSelectionEnabled = true,
        overview = HomeMonthlyOverviewUiModel(
            monthLabel = "2026年9月",
            netText = "+¥2430.00",
            expenseText = "¥3270.00",
            incomeText = "¥5700.00"
        ),
        attentionItems = listOf(
            HomeAttentionUiModel(HomeAttentionType.REVIEW, "3 笔待确认", "1 笔疑似重复"),
            HomeAttentionUiModel(
                HomeAttentionType.AUTOMATIC_BOOKKEEPING,
                "自动记账需要处理",
                "监听服务当前未运行"
            ),
            HomeAttentionUiModel(
                HomeAttentionType.LEDGER_SYNC,
                "账本同步需要处理",
                "1 项冲突等待选择"
            )
        ),
        recentEntries = listOf(
            HomeRecentEntryUiModel(
                id = "lunch",
                title = "这是一个很长但仍然必须保持布局稳定的午餐商户名称",
                category = "餐饮",
                transactionTimeText = "2026-09-07 12:30",
                signedAmountText = "-¥28.00",
                flowType = LedgerFlowType.EXPENSE,
                categoryId = "food",
                transactionKind = TransactionKind.EXPENSE
            )
        )
    )
}

private data class TestConfiguration(
    val size: DpSize,
    val fontScale: Float = 1f
)
