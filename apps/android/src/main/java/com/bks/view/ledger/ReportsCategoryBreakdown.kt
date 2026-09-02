package com.bks.view.ledger

import com.bks.viewmodel.ledger.CategoryShareSlice
import com.bks.viewmodel.ledger.CategoryTotal
import com.bks.viewmodel.ledger.formatMoney
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.model.domain.ledger.TransactionKind
import com.bks.view.common.components.TextButton
import com.bks.view.common.visual.CategoryArtwork
import kotlin.math.min

private val ReportChartPalette = listOf(
    Color(0xFF5B5BD6),
    Color(0xFF56C7B7),
    Color(0xFFFF7B7B),
    Color(0xFFFFD45A),
    Color(0xFFB7A8E8)
)
private val ReportChartInk = Color(0xFF252536)

@Composable
internal fun CategoryShareDonut(
    expenseMinor: Long,
    slices: List<CategoryShareSlice>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "支出分类",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
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
            if (slices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "本月暂无支出分类",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val semantics = buildDonutDescription(expenseMinor, slices)
                    if (maxWidth < 520.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            CategoryDonutSummary(
                                expenseMinor = expenseMinor,
                                slices = slices,
                                contentDescription = semantics
                            )
                            CategoryLegend(slices = slices)
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 164.dp)
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryDonutSummary(
                                expenseMinor = expenseMinor,
                                slices = slices,
                                contentDescription = semantics
                            )
                            CategoryLegend(
                                slices = slices,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDonutSummary(
    expenseMinor: Long,
    slices: List<CategoryShareSlice>,
    contentDescription: String
) {
    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        CategoryDonutCanvas(
            slices = slices,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize()
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "本月支出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMoney(expenseMinor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CategoryLegend(
    slices: List<CategoryShareSlice>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        slices.forEachIndexed { index, slice ->
            CategoryLegendRow(
                slice = slice,
                color = ReportChartPalette[index % ReportChartPalette.size]
            )
        }
    }
}

@Composable
private fun CategoryDonutCanvas(
    slices: List<CategoryShareSlice>,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        }
    ) {
        val outlineWidth = 30.dp.toPx()
        val sliceWidth = 24.dp.toPx()
        drawCircle(
            color = ReportChartInk,
            style = Stroke(width = outlineWidth)
        )

        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweepAngle = 360f * slice.percentageTenths / 1000f
            val gapAngle = if (slices.size == 1) {
                0f
            } else {
                min(2f, sweepAngle * 0.35f)
            }
            drawArc(
                color = ReportChartPalette[index % ReportChartPalette.size],
                startAngle = startAngle + gapAngle / 2f,
                sweepAngle = (sweepAngle - gapAngle).coerceAtLeast(0f),
                useCenter = false,
                style = Stroke(width = sliceWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweepAngle
        }
    }
}

@Composable
private fun CategoryLegendRow(
    slice: CategoryShareSlice,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
                .border(1.dp, ReportChartInk, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.category,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatPercentage(slice.percentageTenths),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun CategoryRankingPreview(
    totals: List<CategoryTotal>,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "分类排行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = onShowMore,
                modifier = Modifier.testTag(ReportTestTags.SHOW_ALL_CATEGORIES)
            ) {
                Text("更多")
            }
        }
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
            if (totals.isEmpty()) {
                Text(
                    text = "本月暂无支出分类",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val previewTotals = totals.take(3)
                previewTotals.forEachIndexed { index, total ->
                    CategoryRankingRow(total)
                    if (index < previewTotals.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FullCategoryRankingScreen(
    totals: List<CategoryTotal>,
    listState: LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 880.dp)
                .fillMaxWidth()
                .fillMaxSize()
                .padding(
                    start = horizontalPadding,
                    top = 20.dp,
                    end = horizontalPadding,
                    bottom = 32.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                        text = "分类排行",
                        modifier = Modifier.testTag(ReportTestTags.FULL_CATEGORY_RANKING_TITLE),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "本月支出分类明细",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(ReportTestTags.BACK_TO_REPORTS)
                ) {
                    Text("返回")
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(ReportTestTags.CATEGORY_RANKING_LIST),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 4.dp)
            ) {
                if (totals.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = "本月暂无支出分类",
                                modifier = Modifier.padding(18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(totals, key = { it.category }) { total ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            CategoryRankingRow(total)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRankingRow(
    total: CategoryTotal
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryArtwork(
            categoryName = total.category,
            transactionKind = TransactionKind.EXPENSE,
            modifier = Modifier
                .size(36.dp)
                .clearAndSetSemantics {}
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${total.category} ${formatMoney(total.amountMinor)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun buildDonutDescription(
    expenseMinor: Long,
    slices: List<CategoryShareSlice>
): String = buildString {
    append("本月支出分类环形图，总支出 ")
    append(formatMoney(expenseMinor))
    slices.forEach { slice ->
        append("，")
        append(slice.category)
        append(" ")
        append(formatPercentage(slice.percentageTenths))
    }
}

private fun formatPercentage(percentageTenths: Int): String =
    "${percentageTenths / 10}.${percentageTenths % 10}%"
