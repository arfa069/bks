package com.bks.view.ledger

import com.bks.viewmodel.ledger.buildLedgerReportUiModel
import com.bks.viewmodel.ledger.formatMoney
import com.bks.viewmodel.ledger.formatSignedMoney
import com.bks.viewmodel.ledger.LedgerReportUiModel
import com.bks.viewmodel.ledger.LedgerEntryUiModel
import com.bks.viewmodel.ledger.MonthlySummary
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.view.common.components.HomeReturnButton
import com.bks.view.common.components.SlidePageTransition
import com.bks.view.common.visual.CachedResourceImage

private val ReportsContentMaxWidth = 880.dp

@Composable
fun ReportsScreen(
    entries: List<LedgerEntryUiModel>,
    reportUiModel: LedgerReportUiModel? = null,
    categoryRankingListState: LazyListState = rememberLazyListState(),
    onNavigateHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val report = reportUiModel ?: remember(entries) { buildLedgerReportUiModel(entries) }
    var showFullCategoryRanking by remember { mutableStateOf(false) }
    val overviewListState = rememberLazyListState()

    BackHandler(enabled = showFullCategoryRanking) {
        showFullCategoryRanking = false
    }

    SlidePageTransition(
        targetState = showFullCategoryRanking,
        modifier = modifier.fillMaxSize()
    ) { showFullRanking ->
        if (showFullRanking) {
            FullCategoryRankingScreen(
                totals = report.categoryTotals,
                listState = categoryRankingListState,
                onBack = { showFullCategoryRanking = false }
            )
        } else {
            ReportsOverviewScreen(
                report = report,
                listState = overviewListState,
                onShowFullCategoryRanking = { showFullCategoryRanking = true },
                onNavigateHome = onNavigateHome
            )
        }
    }
}

@Composable
private fun ReportsOverviewScreen(
    report: LedgerReportUiModel,
    listState: LazyListState,
    onShowFullCategoryRanking: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp
        val anchorMonthKey = report.anchorMonthKey
        val summary = report.summary

        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ReportsContentMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(ReportTestTags.OVERVIEW_LIST),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = 20.dp,
                end = horizontalPadding,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "reports-header") {
                ReportsHeader(onNavigateHome)
            }

            if (anchorMonthKey == null || summary == null) {
                item(key = "reports-empty") {
                    ReportEmptyState()
                }
            } else {
                item(key = "reports-summary") {
                    ReportOverview(summary)
                }
                item(key = "reports-category-chart") {
                    CategoryShareDonut(
                        expenseMinor = summary.expenseMinor,
                        slices = report.categorySlices,
                        modifier = Modifier.testTag(ReportTestTags.CATEGORY_CHART)
                    )
                }
                item(key = "reports-category-ranking") {
                    CategoryRankingPreview(
                        totals = report.categoryTotals,
                        onShowMore = onShowFullCategoryRanking
                    )
                }
                item(key = "reports-cash-flow") {
                    CashFlowPanel(
                        anchorMonthKey = anchorMonthKey,
                        totals = report.cashFlowTotals,
                        modifier = Modifier.testTag(ReportTestTags.CASH_FLOW)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportsHeader(onNavigateHome: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "报表",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "当前账本收支分析",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        HomeReturnButton(onClick = onNavigateHome)
    }
}

@Composable
private fun ReportEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedResourceImage(
                imageRes = R.drawable.aa_reports_empty,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(88.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "当前账本暂无可分析的收支",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "记入收支后，这里会生成分类与现金流分析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportOverview(summary: MonthlySummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("report-summary-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "本月结余",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatSignedMoney(summary.netMinor),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center
                ) {
                    CachedResourceImage(
                        imageRes = R.drawable.aa_nav_reports_art,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(80.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReportMetric(
                    text = "本月支出 ${formatMoney(summary.expenseMinor)}",
                    modifier = Modifier.weight(1f)
                )
                ReportMetric(
                    text = "本月收入 ${formatMoney(summary.incomeMinor)}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReportMetric(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

internal object ReportTestTags {
    const val OVERVIEW_LIST = "report-overview-list"
    const val CATEGORY_CHART = "report-category-chart"
    const val CATEGORY_RANKING_LIST = "report-category-ranking-list"
    const val CASH_FLOW = "report-cash-flow"
    const val SHOW_ALL_CATEGORIES = "report-show-all-categories"
    const val BACK_TO_REPORTS = "report-back-to-overview"
    const val FULL_CATEGORY_RANKING_TITLE = "report-full-category-ranking-title"
}
