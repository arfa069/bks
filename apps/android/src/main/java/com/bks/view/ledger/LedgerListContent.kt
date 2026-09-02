package com.bks.view.ledger

import com.bks.viewmodel.ledger.monthlySummary
import com.bks.viewmodel.ledger.formatMoney
import com.bks.viewmodel.ledger.latestMonthKey
import com.bks.viewmodel.ledger.LedgerFlowType
import com.bks.viewmodel.ledger.LedgerUiEntry
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.bks.view.common.components.HomeReturnButton
import androidx.compose.material3.MaterialTheme
import com.bks.view.common.components.OutlinedButton
import com.bks.view.common.components.OutlinedTextField
import androidx.compose.material3.Text
import com.bks.view.common.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.view.common.visual.CategoryArtwork
import kotlinx.coroutines.launch

private val LedgerContentMaxWidth = 880.dp

@Composable
@Suppress("LongMethod")
internal fun LedgerList(
    entries: List<LedgerUiEntry>,
    entryListState: LazyListState,
    activeLedgerName: String,
    onEntryClick: (String) -> Unit,
    onLedgerBooksClick: () -> Unit,
    onFundingAccountsClick: () -> Unit,
    onRecentlyDeletedClick: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf("") }

    val availableMonthKeys = remember(entries) {
        entries.map { it.monthKey }.distinct().sorted()
    }
    var monthKey by rememberSaveable { mutableStateOf(latestMonthKey(entries)) }
    LaunchedEffect(availableMonthKeys) {
        if (monthKey !in availableMonthKeys) {
            monthKey = availableMonthKeys.lastOrNull() ?: latestMonthKey(entries)
        }
    }
    val monthIndex = availableMonthKeys.indexOf(monthKey)
    val summary = remember(entries, monthKey) { monthlySummary(entries, monthKey) }
    val hasCurrentMonthEntries = remember(entries, monthKey) {
        entries.any { it.monthKey == monthKey }
    }
    val hasActiveFilters = searchText.isNotBlank() ||
        sourceFilter.isNotBlank() ||
        categoryFilter.isNotBlank() ||
        kindFilter.isNotBlank()
    val filteredEntries = remember(
        entries,
        monthKey,
        searchText,
        sourceFilter,
        categoryFilter,
        kindFilter
    ) {
        entries.filterLedgerEntries(
            monthKey = monthKey,
            searchText = searchText,
            sourceFilter = sourceFilter,
            categoryFilter = categoryFilter,
            kindFilter = kindFilter
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp

        LazyColumn(
            state = entryListState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = LedgerContentMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(LedgerTestTags.ENTRY_LIST),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = 20.dp,
                end = horizontalPadding,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "ledger-header") {
                LedgerListHeader(
                    activeLedgerName = activeLedgerName,
                    onLedgerBooksClick = onLedgerBooksClick,
                    onFundingAccountsClick = onFundingAccountsClick,
                    onRecentlyDeletedClick = onRecentlyDeletedClick,
                    onNavigateHome = onNavigateHome
                )
            }
            item(key = "ledger-summary") {
                LedgerSummary(summary)
            }
            item(key = "ledger-search") {
                LedgerSearchBar(
                    searchText = searchText,
                    onSearchChange = { searchText = it },
                    onToggleFilters = { showFilters = !showFilters }
                )
            }
            if (showFilters) {
                item(key = "ledger-filters") {
                    FilterPanel(
                        sourceFilter = sourceFilter,
                        categoryFilter = categoryFilter,
                        kindFilter = kindFilter,
                        onSourceChange = { sourceFilter = it },
                        onCategoryChange = { categoryFilter = it },
                        onKindChange = { kindFilter = it }
                    )
                }
            }
            item(key = "ledger-month-navigation") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "账目明细",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    LedgerMonthNavigation(
                        monthKey = monthKey,
                        monthIndex = monthIndex,
                        availableMonthKeys = availableMonthKeys,
                        onMonthChange = { monthKey = it }
                    )
                }
            }

            ledgerEntryItems(
                entries = filteredEntries,
                hasCurrentMonthEntries = hasCurrentMonthEntries,
                hasActiveFilters = hasActiveFilters,
                onEntryClick = onEntryClick
            )
        }
    }
}

internal fun List<LedgerUiEntry>.filterLedgerEntries(
    monthKey: String,
    searchText: String,
    sourceFilter: String,
    categoryFilter: String,
    kindFilter: String
): List<LedgerUiEntry> {
    val searchQuery = searchText.trim()
    val sourceQuery = sourceFilter.trim()
    val categoryQuery = categoryFilter.trim()
    val kindQuery = kindFilter.trim()
    return asSequence()
        .filter { it.monthKey == monthKey }
        .filter {
            val searchableText = "${it.title} ${it.note.orEmpty()} ${it.category}"
            searchQuery.isBlank() || searchableText.contains(searchQuery, ignoreCase = true)
        }
        .filter { sourceQuery.isBlank() || it.sourceLabel.contains(sourceQuery) }
        .filter { categoryQuery.isBlank() || it.category.contains(categoryQuery) }
        .filter { kindQuery.isBlank() || it.kindLabel.contains(kindQuery) }
        .sortedByDescending { it.transactionTimeEpochMillis }
        .toList()
}

@Composable
private fun LedgerListHeader(
    activeLedgerName: String,
    onLedgerBooksClick: () -> Unit,
    onFundingAccountsClick: () -> Unit,
    onRecentlyDeletedClick: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
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
                text = "账本",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = activeLedgerName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag(LedgerTestTags.MORE_MENU)
                ) {
                    Text("更多")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag(LedgerTestTags.MANAGE_LEDGERS),
                        text = { Text("账本管理") },
                        onClick = {
                            showMenu = false
                            onLedgerBooksClick()
                        }
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag(LedgerTestTags.MANAGE_FUNDING_ACCOUNTS),
                        text = { Text("资金账户") },
                        onClick = {
                            showMenu = false
                            onFundingAccountsClick()
                        }
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag(LedgerTestTags.RECENTLY_DELETED),
                        text = { Text("最近删除") },
                        onClick = {
                            showMenu = false
                            onRecentlyDeletedClick()
                        }
                    )
                }
            }
            HomeReturnButton(onClick = onNavigateHome)
        }
    }
}

@Composable
private fun LedgerSearchBar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onToggleFilters: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchChange,
            label = { Text("搜索商户或备注") },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .testTag(LedgerTestTags.SEARCH_FIELD)
        )
        OutlinedButton(
            onClick = onToggleFilters,
            modifier = Modifier
                .widthIn(min = 88.dp)
                .heightIn(min = 56.dp)
                .testTag(LedgerTestTags.FILTER_BUTTON)
        ) {
            Text("筛选")
        }
    }
}

@Composable
private fun LedgerMonthNavigation(
    monthKey: String,
    monthIndex: Int,
    availableMonthKeys: List<String>,
    onMonthChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { onMonthChange(availableMonthKeys[monthIndex - 1]) },
            enabled = monthIndex > 0
        ) {
            Text("上一月")
        }
        Text(
            "$monthKey 明细",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(
            onClick = { onMonthChange(availableMonthKeys[monthIndex + 1]) },
            enabled = monthIndex in 0 until availableMonthKeys.lastIndex
        ) {
            Text("下一月")
        }
    }
}

private fun LazyListScope.ledgerEntryItems(
    entries: List<LedgerUiEntry>,
    hasCurrentMonthEntries: Boolean,
    hasActiveFilters: Boolean,
    onEntryClick: (String) -> Unit
) {
    if (entries.isEmpty()) {
        item(key = "ledger-empty") {
            if (!hasCurrentMonthEntries && !hasActiveFilters) {
                LedgerEmptyState(
                    title = "当前没有已确认账目",
                    description = "确认后的收支记录会显示在这里。"
                )
            } else {
                LedgerEmptyState(
                    title = "没有符合当前筛选条件的账目",
                    description = "可以调整关键词或筛选条件后再试。"
                )
            }
        }
    } else {
        items(entries, key = { it.id }) { entry ->
            LedgerEntryRow(entry) { onEntryClick(entry.id) }
        }
    }
}

@Composable
private fun FilterPanel(
    sourceFilter: String,
    categoryFilter: String,
    kindFilter: String,
    onSourceChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onKindChange: (String) -> Unit
) {
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
        BoxWithConstraints(modifier = Modifier.padding(16.dp)) {
            if (maxWidth >= 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LedgerFilterField("来源", sourceFilter, onSourceChange, Modifier.weight(1f))
                    LedgerFilterField("分类", categoryFilter, onCategoryChange, Modifier.weight(1f))
                    LedgerFilterField("交易类型", kindFilter, onKindChange, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LedgerFilterField("来源", sourceFilter, onSourceChange)
                    LedgerFilterField("分类", categoryFilter, onCategoryChange)
                    LedgerFilterField("交易类型", kindFilter, onKindChange)
                }
            }
        }
    }
}

@Composable
private fun LedgerFilterField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun LedgerEntryRow(entry: LedgerUiEntry, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressedOverlayColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val pressIndication = remember(pressedOverlayColor) {
        LedgerEntryPressIndication(pressedOverlayColor)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = pressIndication,
                onClick = onClick
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryArtwork(
                categoryId = entry.categoryId,
                categoryName = entry.category,
                transactionKind = entry.transactionKind,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = entry.title.ifBlank { "未填写标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.category} · ${entry.sourceLabel} · ${entry.transactionTimeText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            val signedAmount = when (entry.flowType) {
                LedgerFlowType.INCOME -> "+${formatMoney(entry.amountMinor)}"
                LedgerFlowType.EXPENSE -> "-${formatMoney(entry.amountMinor)}"
                LedgerFlowType.NEUTRAL -> formatMoney(entry.amountMinor)
            }
            Text(
                text = signedAmount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class LedgerEntryPressIndication(
    private val color: Color
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        LedgerEntryPressIndicationNode(interactionSource, color)
}

private class LedgerEntryPressIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color
) : Modifier.Node(), DrawModifierNode {
    private val activePresses = mutableSetOf<PressInteraction.Press>()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val wasPressed = activePresses.isNotEmpty()
                when (interaction) {
                    is PressInteraction.Press -> activePresses += interaction
                    is PressInteraction.Release -> activePresses -= interaction.press
                    is PressInteraction.Cancel -> activePresses -= interaction.press
                }
                if (wasPressed != activePresses.isNotEmpty()) invalidateDraw()
            }
        }
    }

    override fun onDetach() {
        activePresses.clear()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (activePresses.isNotEmpty()) drawRect(color)
    }
}

@Composable
internal fun <T> SelectionMenu(
    label: String,
    selected: T,
    options: List<T>,
    itemLabel: (T) -> String,
    leadingContent: (@Composable (T) -> Unit)? = null,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leadingContent?.let {
                        it(selected)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("$label：${itemLabel(selected)}")
                }
                Text("⌄", style = MaterialTheme.typography.titleMedium)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            leadingContent?.let {
                                it(option)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(itemLabel(option))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}
