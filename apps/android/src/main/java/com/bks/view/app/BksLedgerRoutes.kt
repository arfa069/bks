package com.bks.view.app

import com.bks.model.domain.categorization.upsert
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.categorization.suggestCategory
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.view.ledger.LedgerScreen
import com.bks.viewmodel.ledger.LedgerUiState
import com.bks.viewmodel.ledger.LedgerViewModel
import com.bks.view.ledger.ReportsScreen
import com.bks.viewmodel.ledger.defaultLedgerCategoryUiModels
import com.bks.viewmodel.review.ReviewQueueUiState
import com.bks.view.review.ReviewQueueScreen
import com.bks.viewmodel.review.ReviewQueueViewModel
@Composable
@Suppress("LongParameterList")
internal fun BksReviewRoute(
    reviewState: ReviewQueueUiState,
    reviewViewModel: ReviewQueueViewModel,
    ledgerState: LedgerUiState,
    onCategorizationRulesChange: suspend (CategorizationRulesUpdate) -> Unit,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ReviewQueueScreen(
        state = reviewState,
        targetLedgerBookId = ledgerState.activeLedgerBookId,
        targetLedgerName = ledgerState.activeLedgerName,
        categories = ledgerState.categories,
        fallbackCategories = defaultLedgerCategoryUiModels(),
        fundingAccounts = ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = ledgerState.defaultFundingAccountSyncId,
        onCommand = reviewViewModel::execute,
        onUndo = reviewViewModel::undoLastAction,
        onDismissUndo = reviewViewModel::dismissUndo,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        onCategorizationRuleRequested = { request ->
            onCategorizationRulesChange { currentRules ->
                currentRules.upsert(
                    CategorizationRule(
                        id = request.id,
                        merchantContains = request.merchantContains,
                        category = request.category,
                        updatedAtEpochMillis = request.updatedAtEpochMillis
                    )
                )
            }
        },
        onAiSuggest = reviewViewModel::suggestCategory,
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
    requestedEntryId: String?,
    onRequestedEntryHandled: () -> Unit,
    onRequestedEntryUnavailable: () -> Unit,
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
        requestedEntryId = requestedEntryId,
        onRequestedEntryHandled = onRequestedEntryHandled,
        onRequestedEntryUnavailable = onRequestedEntryUnavailable,
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
