package com.bks.viewmodel.home

import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.LedgerBookUiModel
import com.bks.viewmodel.ledger.LedgerEntryUiModel
import com.bks.viewmodel.ledger.LedgerFlowType
import com.bks.viewmodel.ledger.LedgerReportUiModel
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.MonthlySummary
import com.bks.viewmodel.monitoring.AutomaticBookkeepingFeatureState
import com.bks.viewmodel.review.ReviewEntryUiModel
import com.bks.viewmodel.review.ReviewQueueUiState
import com.bks.viewmodel.sync.LedgerSyncConflictUiModel
import com.bks.viewmodel.sync.LedgerSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun mapsOverviewLedgersAndThreeNewestActiveEntries() {
        val state = buildHomeUiState(
            ledgerState = LedgerUiState(
                entries = listOf(
                    entry("old", 1),
                    entry("same-b", 4),
                    entry("deleted", 9, deleted = true),
                    entry("newest", 7, flowType = LedgerFlowType.INCOME),
                    entry("same-a", 4, title = "")
                ),
                ledgerBooks = listOf(
                    LedgerBookUiModel("daily", "日常账本", isActive = true),
                    LedgerBookUiModel("travel", "旅行账本")
                ),
                activeLedgerName = "日常账本",
                report = LedgerReportUiModel(
                    anchorMonthKey = "2026-09",
                    summary = MonthlySummary(
                        expenseMinor = 327_000,
                        incomeMinor = 570_000,
                        netMinor = 243_000
                    )
                )
            ),
            reviewState = emptyReviewState(),
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(),
            accessibilityAccessGranted = false,
            accessibilityServiceConnected = false,
            batteryOptimizationIgnored = null,
            ledgerSyncState = LedgerSyncUiState()
        )

        assertEquals("日常账本", state.activeLedgerName)
        assertEquals(listOf("daily", "travel"), state.ledgerBooks.map { it.id })
        assertTrue(state.ledgerSelectionEnabled)
        assertEquals("2026年9月", state.overview?.monthLabel)
        assertEquals("+¥2430.00", state.overview?.netText)
        assertEquals(listOf("newest", "same-a", "same-b"), state.recentEntries.map { it.id })
        assertEquals("未填写标题", state.recentEntries[1].title)
        assertEquals("+¥7.00", state.recentEntries.first().signedAmountText)
    }

    @Test
    fun buildsActionableItemsInStableOrderWithoutLeakingSyncError() {
        val reviewState = ReviewQueueUiState(
            pendingEntries = listOf(
                pending("duplicate", ConfidenceState.DUPLICATE_SUSPECT, TODAY_START + 1),
                pending("ordinary", ConfidenceState.HIGH, TODAY_START - 1)
            ),
            nowEpochMillis = NOW,
            todayStartEpochMillis = TODAY_START
        )
        val state = buildHomeUiState(
            ledgerState = LedgerUiState(),
            reviewState = reviewState,
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(enabled = true),
            accessibilityAccessGranted = false,
            accessibilityServiceConnected = false,
            batteryOptimizationIgnored = false,
            ledgerSyncState = LedgerSyncUiState(
                signedIn = true,
                enabled = true,
                lastError = "provider returned secret details",
                conflicts = listOf(
                    LedgerSyncConflictUiModel("conflict", 1, "冲突", "云端", "本机")
                )
            )
        )

        assertEquals(
            listOf(
                HomeAttentionType.REVIEW,
                HomeAttentionType.AUTOMATIC_BOOKKEEPING,
                HomeAttentionType.LEDGER_SYNC
            ),
            state.attentionItems.map { it.type }
        )
        assertEquals("1 笔疑似重复 · 今日新增 1 笔", state.attentionItems[0].summary)
        assertEquals("无障碍权限尚未开启", state.attentionItems[1].summary)
        assertEquals("1 项冲突等待选择", state.attentionItems[2].summary)
        assertFalse(state.attentionItems.any { it.summary.contains("secret") })
    }

    @Test
    fun onlyShowsAutomaticBookkeepingAndSyncWhenAttentionIsRequired() {
        val listeningWarning = homeState(
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(enabled = true),
            accessibilityAccessGranted = true,
            accessibilityServiceConnected = false,
            batteryOptimizationIgnored = false,
            ledgerSyncState = LedgerSyncUiState(
                signedIn = true,
                enabled = true,
                lastError = "network"
            )
        )
        assertEquals("监听服务当前未运行", listeningWarning.attentionItems[0].summary)
        assertEquals("最近一次同步失败，请检查", listeningWarning.attentionItems[1].summary)

        val powerWarning = homeState(
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(enabled = true),
            accessibilityAccessGranted = true,
            accessibilityServiceConnected = true,
            batteryOptimizationIgnored = false
        )
        assertEquals("电源策略可能限制后台运行", powerWarning.attentionItems.single().summary)

        val healthy = homeState(
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(enabled = true),
            accessibilityAccessGranted = true,
            accessibilityServiceConnected = true,
            batteryOptimizationIgnored = true,
            ledgerSyncState = LedgerSyncUiState(
                signedIn = true,
                enabled = false,
                lastError = "stale"
            )
        )
        assertTrue(healthy.attentionItems.isEmpty())
        assertNull(healthy.overview)
    }

    @Test
    fun disablesLedgerSelectionWhileLocalDataIsBeingReplaced() {
        val state = buildHomeUiState(
            ledgerState = LedgerUiState(localDataReplacementInFlight = true),
            reviewState = emptyReviewState(),
            automaticBookkeepingState = AutomaticBookkeepingFeatureState(),
            accessibilityAccessGranted = false,
            accessibilityServiceConnected = false,
            batteryOptimizationIgnored = null,
            ledgerSyncState = LedgerSyncUiState()
        )

        assertFalse(state.ledgerSelectionEnabled)
    }

    private fun homeState(
        automaticBookkeepingState: AutomaticBookkeepingFeatureState,
        accessibilityAccessGranted: Boolean,
        accessibilityServiceConnected: Boolean,
        batteryOptimizationIgnored: Boolean?,
        ledgerSyncState: LedgerSyncUiState = LedgerSyncUiState()
    ): HomeUiState = buildHomeUiState(
        ledgerState = LedgerUiState(),
        reviewState = emptyReviewState(),
        automaticBookkeepingState = automaticBookkeepingState,
        accessibilityAccessGranted = accessibilityAccessGranted,
        accessibilityServiceConnected = accessibilityServiceConnected,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        ledgerSyncState = ledgerSyncState
    )

    private fun entry(
        id: String,
        time: Long,
        title: String = id,
        flowType: LedgerFlowType = LedgerFlowType.EXPENSE,
        deleted: Boolean = false
    ) = LedgerEntryUiModel(
        id = id,
        title = title,
        amountMinor = time * 100,
        monthKey = "2026-09",
        transactionTimeText = "2026-09-07 12:00",
        category = "餐饮",
        sourceLabel = "手动录入",
        kindLabel = "支出",
        flowType = flowType,
        transactionKind = TransactionKind.EXPENSE,
        transactionTimeEpochMillis = time,
        categoryId = "food",
        deletedAtEpochMillis = if (deleted) NOW else null
    )

    private fun pending(
        id: String,
        confidence: ConfidenceState,
        capturedAt: Long
    ) = ReviewEntryUiModel(
        id = id,
        confidence = confidence,
        capturedAtEpochMillis = capturedAt
    )

    private fun emptyReviewState() = ReviewQueueUiState(
        nowEpochMillis = NOW,
        todayStartEpochMillis = TODAY_START
    )

    private companion object {
        const val NOW = 1_788_710_400_000L
        const val TODAY_START = 1_788_681_600_000L
    }
}
