package com.bks.view.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bks.viewmodel.home.HomeAttentionType
import com.bks.viewmodel.home.HomeUiState

internal data class HomeScreenActions(
    val onSelectLedger: (String) -> Unit = {},
    val onOpenReports: () -> Unit = {},
    val onOpenReview: () -> Unit = {},
    val onOpenAutomaticBookkeeping: () -> Unit = {},
    val onOpenLedgerSync: () -> Unit = {},
    val onOpenLedger: () -> Unit = {},
    val onOpenLedgerEntry: (String) -> Unit = {}
)

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    actions: HomeScreenActions,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 610.dp) 32.dp else 20.dp
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = HomeContentMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .testTag(HomeTestTags.SCREEN),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = 20.dp,
                end = horizontalPadding,
                bottom = 32.dp
            )
        ) {
            item(key = "home-header") {
                HomeHeader(
                    activeLedgerName = state.activeLedgerName,
                    ledgerBooks = state.ledgerBooks,
                    selectionEnabled = state.ledgerSelectionEnabled,
                    onSelectLedger = actions.onSelectLedger
                )
            }
            item(key = "home-overview") {
                HomeMonthlyOverviewCard(
                    overview = state.overview,
                    onClick = actions.onOpenReports
                )
            }
            if (state.attentionItems.isNotEmpty()) {
                item(key = "home-attention-title") {
                    HomeSectionHeader(title = "需要处理")
                }
                items(
                    items = state.attentionItems,
                    key = { "attention-${it.type}" }
                ) { item ->
                    HomeAttentionRow(
                        item = item,
                        onClick = when (item.type) {
                            HomeAttentionType.REVIEW -> actions.onOpenReview
                            HomeAttentionType.AUTOMATIC_BOOKKEEPING -> actions.onOpenAutomaticBookkeeping
                            HomeAttentionType.LEDGER_SYNC -> actions.onOpenLedgerSync
                        }
                    )
                }
            }
            item(key = "home-recent-title") {
                HomeSectionHeader(
                    title = "最近账目",
                    actionLabel = "查看全部",
                    onAction = actions.onOpenLedger
                )
            }
            if (state.recentEntries.isEmpty()) {
                item(key = "home-recent-empty") {
                    HomeRecentEmptyState()
                }
            } else {
                items(
                    items = state.recentEntries,
                    key = { "recent-${it.id}" }
                ) { entry ->
                    HomeRecentEntryRow(
                        entry = entry,
                        onClick = { actions.onOpenLedgerEntry(entry.id) }
                    )
                }
            }
        }
    }
}

internal object HomeTestTags {
    const val SCREEN = "home-screen"
    const val LEDGER_SELECTOR = "home-ledger-selector"
    const val OVERVIEW = "home-overview"
    const val RECENT_EMPTY = "home-recent-empty"
    const val VIEW_ALL = "home-view-all"

    fun ledgerOption(id: String) = "home-ledger-$id"
    fun attention(type: HomeAttentionType) = "home-attention-${type.name}"
    fun recentEntry(id: String) = "home-recent-$id"
}

private val HomeContentMaxWidth = 880.dp
