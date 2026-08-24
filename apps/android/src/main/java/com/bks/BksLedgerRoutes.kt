package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationRule
import com.bks.feature.ledger.LedgerScreen
import com.bks.feature.ledger.LedgerUiState
import com.bks.feature.ledger.LedgerViewModel
import com.bks.feature.ledger.ReportsScreen
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.review.ReviewQueueScreen
import com.bks.feature.review.ReviewQueueViewModel

@Composable
@Suppress("LongParameterList")
internal fun BksReviewRoute(
    reviewState: ReviewQueueState,
    reviewViewModel: ReviewQueueViewModel,
    ledgerState: LedgerUiState,
    categorizationRules: List<CategorizationRule>,
    onCategorizationRulesChange: (List<CategorizationRule>) -> Unit,
    accountToken: String?,
    aiSettings: AiCategorizationSettings,
    aiCategorizationGateway: AiCategorizationGateway,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ReviewQueueScreen(
        state = reviewState,
        targetLedgerBookId = ledgerState.activeLedgerBookId,
        targetLedgerName = ledgerState.activeLedgerName,
        categories = ledgerState.categories,
        fundingAccounts = ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = ledgerState.defaultFundingAccountSyncId,
        onCommand = reviewViewModel::execute,
        onUndo = reviewViewModel::undoLastAction,
        onDismissUndo = reviewViewModel::dismissUndo,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        onCategorizationRuleRequested = { rule ->
            onCategorizationRulesChange(categorizationRules.upsert(rule))
        },
        accountToken = accountToken,
        aiSettings = aiSettings,
        aiCategorizationGateway = aiCategorizationGateway,
        onNavigateHome = onNavigateHome
    )
}

@Composable
@Suppress("LongParameterList")
internal fun BksLedgerRoute(
    state: LedgerUiState,
    viewModel: LedgerViewModel,
    entryListState: LazyListState,
    snackbarHostState: SnackbarHostState,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    LedgerScreen(
        entries = state.entries,
        entryListState = entryListState,
        deletedEntries = state.deletedEntries,
        categories = state.categories,
        fundingAccounts = state.fundingAccounts,
        defaultFundingAccountSyncId = state.defaultFundingAccountSyncId,
        ledgerBooks = state.ledgerBooks,
        activeLedgerName = state.activeLedgerName,
        onUpdateEntry = viewModel::updateEntry,
        onDeleteEntry = viewModel::deleteEntry,
        onRestoreEntry = viewModel::restoreEntry,
        onPermanentlyDeleteEntry = viewModel::permanentlyDeleteEntry,
        onPurgeExpiredEntries = viewModel::purgeExpiredEntries,
        onCreateLedger = viewModel::createLedger,
        onSelectLedger = viewModel::selectLedger,
        onDeleteLedger = viewModel::deleteLedger,
        onCreateFundingAccount = viewModel::createFundingAccount,
        onUpdateFundingAccount = viewModel::updateFundingAccount,
        onSetDefaultFundingAccount = { id ->
            viewModel.setDefaultFundingAccount(id)?.let { message ->
                snackbarHostState.showSnackbar(message)
            }
        },
        onDeleteFundingAccount = viewModel::deleteFundingAccount,
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
internal fun BksReportsRoute(
    state: LedgerUiState,
    categoryRankingListState: LazyListState,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ReportsScreen(
        entries = state.entries,
        reportUiModel = state.report,
        categoryRankingListState = categoryRankingListState,
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
