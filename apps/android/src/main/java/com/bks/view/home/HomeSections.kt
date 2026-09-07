package com.bks.view.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.view.common.components.TextButton
import com.bks.view.common.visual.CachedResourceImage
import com.bks.viewmodel.home.HomeAttentionType
import com.bks.viewmodel.home.HomeAttentionUiModel
import com.bks.viewmodel.home.HomeLedgerBookUiModel
import com.bks.viewmodel.home.HomeMonthlyOverviewUiModel

@Composable
internal fun HomeHeader(
    activeLedgerName: String,
    ledgerBooks: List<HomeLedgerBookUiModel>,
    selectionEnabled: Boolean,
    onSelectLedger: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "主页",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "看看近期的收支与待办",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        LedgerSelector(
            activeLedgerName = activeLedgerName,
            ledgerBooks = ledgerBooks,
            enabled = selectionEnabled,
            onSelectLedger = onSelectLedger
        )
    }
}

@Composable
private fun LedgerSelector(
    activeLedgerName: String,
    ledgerBooks: List<HomeLedgerBookUiModel>,
    enabled: Boolean,
    onSelectLedger: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleLedgers = ledgerBooks.size > 1
    Box {
        val selectorModifier = Modifier
            .widthIn(max = 180.dp)
            .testTag(HomeTestTags.LEDGER_SELECTOR)
        val interactiveModifier = if (hasMultipleLedgers) {
            selectorModifier.clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = { expanded = true }
            )
        } else {
            selectorModifier
        }
        Surface(
            modifier = interactiveModifier,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = if (hasMultipleLedgers) "$activeLedgerName  ▾" else activeLedgerName,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ledgerBooks.forEach { ledger ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (ledger.isActive) "${ledger.name} · 当前" else ledger.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        if (!ledger.isActive) onSelectLedger(ledger.id)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(HomeTestTags.ledgerOption(ledger.id))
                )
            }
        }
    }
}

@Composable
internal fun HomeMonthlyOverviewCard(
    overview: HomeMonthlyOverviewUiModel?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .testTag(HomeTestTags.OVERVIEW)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (overview == null) {
            HomeOverviewEmptyContent()
        } else {
            HomeOverviewContent(overview)
        }
    }
}

@Composable
private fun HomeOverviewContent(overview: HomeMonthlyOverviewUiModel) {
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
                    text = "${overview.monthLabel}结余",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = overview.netText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CachedResourceImage(
                imageRes = R.drawable.aa_nav_reports_art,
                contentDescription = null,
                modifier = Modifier.size(80.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HomeMetric(
                text = "支出 ${overview.expenseText}",
                modifier = Modifier.weight(1f)
            )
            HomeMetric(
                text = "收入 ${overview.incomeText}",
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "查看完整报表  ›",
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HomeMetric(text: String, modifier: Modifier = Modifier) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeOverviewEmptyContent() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CachedResourceImage(
            imageRes = R.drawable.aa_reports_empty,
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "暂无可分析的收支",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "记入收支后，这里会显示月度概览。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun HomeSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.testTag(HomeTestTags.VIEW_ALL)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun HomeAttentionRow(item: HomeAttentionUiModel, onClick: () -> Unit) {
    val artwork = when (item.type) {
        HomeAttentionType.REVIEW -> R.drawable.aa_nav_review_art
        HomeAttentionType.AUTOMATIC_BOOKKEEPING -> R.drawable.aa_profile_automatic
        HomeAttentionType.LEDGER_SYNC -> R.drawable.aa_profile_backup
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .testTag(HomeTestTags.attention(item.type))
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedResourceImage(
                imageRes = artwork,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
