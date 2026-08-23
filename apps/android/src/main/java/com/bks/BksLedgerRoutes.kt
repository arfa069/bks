package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.feature.account.AccountSession
import com.bks.feature.ledger.LedgerScreen
import com.bks.feature.ledger.ReportsScreen
import com.bks.feature.review.ReviewQueueScreen

@Composable
internal fun BksReviewRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val runtime = context.runtime
    val presentation = context.presentation
    val ledgerState = context.ledgerUiState
    ReviewQueueScreen(
        state = runtime.reviewState,
        targetLedgerName = ledgerState.activeLedgerName,
        categories = ledgerState.categories,
        fundingAccounts = ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = ledgerState.defaultFundingAccountSyncId,
        onStateChange = context.actions::persistReviewState,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        onCategorizationRuleRequested = { rule ->
            context.actions.persistCategorizationRules(runtime.categorizationRules.upsert(rule))
        },
        accountToken = (runtime.accountSession as? AccountSession.SignedIn)?.token,
        aiSettings = presentation.effectiveAiSettings,
        aiCategorizationGateway = context.dependencies.aiCategorizationGateway,
        onNavigateHome = onNavigateHome
    )
}

@Composable
internal fun BksLedgerRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val state = context.ledgerUiState
    val viewModel = context.ledgerViewModel
    val appState = context.appState
    LedgerScreen(
        entries = state.entries,
        entryListState = appState.ledgerEntryListState,
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
                context.appState.snackbarHostState.showSnackbar(message)
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
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val state = context.ledgerUiState
    ReportsScreen(
        entries = state.entries,
        reportUiModel = state.report,
        categoryRankingListState = context.appState.reportCategoryRankingListState,
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
