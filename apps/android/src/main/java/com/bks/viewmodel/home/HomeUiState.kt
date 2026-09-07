package com.bks.viewmodel.home

import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.LedgerBookUiModel
import com.bks.viewmodel.ledger.LedgerEntryUiModel
import com.bks.viewmodel.ledger.LedgerFlowType
import com.bks.viewmodel.ledger.LedgerReportUiModel
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.formatMoney
import com.bks.viewmodel.ledger.formatSignedMoney
import com.bks.viewmodel.monitoring.AutomaticBookkeepingFeatureState
import com.bks.viewmodel.review.ReviewQueueUiState
import com.bks.viewmodel.sync.LedgerSyncUiState
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HomeUiState(
    val activeLedgerName: String,
    val ledgerBooks: List<HomeLedgerBookUiModel>,
    val ledgerSelectionEnabled: Boolean,
    val overview: HomeMonthlyOverviewUiModel?,
    val attentionItems: List<HomeAttentionUiModel>,
    val recentEntries: List<HomeRecentEntryUiModel>
)

internal data class HomeLedgerBookUiModel(
    val id: String,
    val name: String,
    val isActive: Boolean
)

internal data class HomeMonthlyOverviewUiModel(
    val monthLabel: String,
    val netText: String,
    val expenseText: String,
    val incomeText: String
)

internal data class HomeAttentionUiModel(
    val type: HomeAttentionType,
    val title: String,
    val summary: String
)

internal enum class HomeAttentionType {
    REVIEW,
    AUTOMATIC_BOOKKEEPING,
    LEDGER_SYNC
}

internal data class HomeRecentEntryUiModel(
    val id: String,
    val title: String,
    val category: String,
    val transactionTimeText: String,
    val signedAmountText: String,
    val flowType: LedgerFlowType,
    val categoryId: String?,
    val transactionKind: TransactionKind
)

@Suppress("LongParameterList")
internal fun buildHomeUiState(
    ledgerState: LedgerUiState,
    reviewState: ReviewQueueUiState,
    automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    batteryOptimizationIgnored: Boolean?,
    ledgerSyncState: LedgerSyncUiState
): HomeUiState = HomeUiState(
    activeLedgerName = ledgerState.activeLedgerName,
    ledgerBooks = ledgerState.ledgerBooks.map(LedgerBookUiModel::toHomeUiModel),
    ledgerSelectionEnabled = !ledgerState.localDataReplacementInFlight,
    overview = ledgerState.report.toHomeOverview(),
    attentionItems = buildAttentionItems(
        reviewState = reviewState,
        automaticBookkeepingState = automaticBookkeepingState,
        runtimeStatus = HomeRuntimeStatusInput(
            accessibilityAccessGranted = accessibilityAccessGranted,
            accessibilityServiceConnected = accessibilityServiceConnected,
            batteryOptimizationIgnored = batteryOptimizationIgnored
        ),
        ledgerSyncState = ledgerSyncState
    ),
    recentEntries = ledgerState.entries.asSequence()
        .filter { it.deletedAtEpochMillis == null }
        .sortedWith(compareByDescending<LedgerEntryUiModel> {
            it.transactionTimeEpochMillis
        }.thenBy { it.id })
        .take(RECENT_ENTRY_LIMIT)
        .map { entry ->
            HomeRecentEntryUiModel(
                id = entry.id,
                title = entry.title.ifBlank { "未填写标题" },
                category = entry.category,
                transactionTimeText = entry.transactionTimeText,
                signedAmountText = when (entry.flowType) {
                    LedgerFlowType.INCOME -> "+${formatMoney(entry.amountMinor)}"
                    LedgerFlowType.EXPENSE -> "-${formatMoney(entry.amountMinor)}"
                    LedgerFlowType.NEUTRAL -> formatMoney(entry.amountMinor)
                },
                flowType = entry.flowType,
                categoryId = entry.categoryId,
                transactionKind = entry.transactionKind
            )
        }
        .toList()
)

private fun LedgerBookUiModel.toHomeUiModel() = HomeLedgerBookUiModel(
    id = id,
    name = name,
    isActive = isActive
)

private fun LedgerReportUiModel.toHomeOverview(): HomeMonthlyOverviewUiModel? {
    val monthKey = anchorMonthKey ?: return null
    val monthlySummary = summary ?: return null
    return HomeMonthlyOverviewUiModel(
        monthLabel = monthKey.toMonthLabel(),
        netText = formatSignedMoney(monthlySummary.netMinor),
        expenseText = formatMoney(monthlySummary.expenseMinor),
        incomeText = formatMoney(monthlySummary.incomeMinor)
    )
}

private fun String.toMonthLabel(): String = runCatching {
    YearMonth.parse(this).format(MONTH_LABEL_FORMATTER)
}.getOrDefault(this)

private fun buildAttentionItems(
    reviewState: ReviewQueueUiState,
    automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    runtimeStatus: HomeRuntimeStatusInput,
    ledgerSyncState: LedgerSyncUiState
): List<HomeAttentionUiModel> = buildList {
    if (reviewState.pendingEntries.isNotEmpty()) {
        add(
            HomeAttentionUiModel(
                type = HomeAttentionType.REVIEW,
                title = "${reviewState.pendingEntries.size} 笔待确认",
                summary = reviewSummary(reviewState)
            )
        )
    }
    automaticBookkeepingWarning(
        automaticBookkeepingState = automaticBookkeepingState,
        accessibilityAccessGranted = runtimeStatus.accessibilityAccessGranted,
        accessibilityServiceConnected = runtimeStatus.accessibilityServiceConnected,
        batteryOptimizationIgnored = runtimeStatus.batteryOptimizationIgnored
    )?.let(::add)
    ledgerSyncWarning(ledgerSyncState)?.let(::add)
}

private data class HomeRuntimeStatusInput(
    val accessibilityAccessGranted: Boolean,
    val accessibilityServiceConnected: Boolean,
    val batteryOptimizationIgnored: Boolean?
)

private fun reviewSummary(state: ReviewQueueUiState): String = buildList {
    if (state.duplicateSuspectCount > 0) add("${state.duplicateSuspectCount} 笔疑似重复")
    if (state.todaysNewlyCapturedCount > 0) add("今日新增 ${state.todaysNewlyCapturedCount} 笔")
}.joinToString(" · ").ifBlank { "等待你确认后入账" }

private fun automaticBookkeepingWarning(
    automaticBookkeepingState: AutomaticBookkeepingFeatureState,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    batteryOptimizationIgnored: Boolean?
): HomeAttentionUiModel? {
    if (!automaticBookkeepingState.enabled) return null
    val summary = when {
        !accessibilityAccessGranted -> "无障碍权限尚未开启"
        !accessibilityServiceConnected -> "监听服务当前未运行"
        batteryOptimizationIgnored == false -> "电源策略可能限制后台运行"
        else -> return null
    }
    return HomeAttentionUiModel(
        type = HomeAttentionType.AUTOMATIC_BOOKKEEPING,
        title = "自动记账需要处理",
        summary = summary
    )
}

private fun ledgerSyncWarning(state: LedgerSyncUiState): HomeAttentionUiModel? {
    if (!state.signedIn || !state.enabled) return null
    val summary = when {
        state.conflicts.isNotEmpty() -> "${state.conflicts.size} 项冲突等待选择"
        !state.lastError.isNullOrBlank() -> "最近一次同步失败，请检查"
        else -> return null
    }
    return HomeAttentionUiModel(
        type = HomeAttentionType.LEDGER_SYNC,
        title = "账本同步需要处理",
        summary = summary
    )
}

private val MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
private const val RECENT_ENTRY_LIMIT = 3
