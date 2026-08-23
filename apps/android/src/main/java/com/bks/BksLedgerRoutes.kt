package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.bks.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.bks.feature.account.AccountCloudSettingsOperationResult
import com.bks.feature.ledger.FundingAccountDeleteResult as UiFundingAccountDeleteResult
import com.bks.feature.ledger.LedgerBookDeleteResult as UiLedgerBookDeleteResult
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
    ReviewQueueScreen(
        state = runtime.reviewState,
        targetLedgerName = presentation.activeLedgerName,
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = runtime.ledgerState.defaultFundingAccountSyncId,
        onStateChange = context.actions::persistReviewState,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        onCategorizationRuleRequested = { rule ->
            context.actions.persistCategorizationRules(runtime.categorizationRules.upsert(rule))
        },
        accountSession = runtime.accountSession,
        aiSettings = presentation.effectiveAiSettings,
        aiCategorizationGateway = context.dependencies.aiCategorizationGateway,
        onNavigateHome = onNavigateHome
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun BksLedgerRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val runtime = context.runtime
    val presentation = context.presentation
    val dependencies = context.dependencies
    val appState = context.appState
    LedgerScreen(
        entries = presentation.ledgerEntries,
        entryListState = appState.ledgerEntryListState,
        deletedEntries = presentation.deletedLedgerEntries,
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = runtime.ledgerState.defaultFundingAccountSyncId,
        ledgerBooks = presentation.ledgerBookUiModels,
        activeLedgerName = presentation.activeLedgerName,
        onUpdateEntry = { id, input -> dependencies.local.ledgerRepository.updateLedgerEntry(id, input) },
        onDeleteEntry = { id -> dependencies.local.ledgerRepository.moveLedgerEntryToDeleted(id) },
        onRestoreEntry = { id -> dependencies.local.ledgerRepository.restoreDeletedLedgerEntry(id) },
        onPermanentlyDeleteEntry = { id -> dependencies.local.ledgerRepository.permanentlyDeleteLedgerEntry(id) },
        onPurgeExpiredEntries = { dependencies.local.ledgerRepository.purgeExpiredDeletedLedgerEntries() },
        onCreateLedger = { name -> dependencies.local.ledgerRepository.createLedgerBook(name) },
        onSelectLedger = { id -> dependencies.local.ledgerRepository.selectLedgerBook(id) },
        onDeleteLedger = { id ->
            when (val result = dependencies.local.ledgerRepository.deleteLedgerBook(id)) {
                DataLedgerBookDeleteResult.Deleted -> UiLedgerBookDeleteResult.Deleted
                DataLedgerBookDeleteResult.LastLedgerBook -> UiLedgerBookDeleteResult.LastLedger
                is DataLedgerBookDeleteResult.NotEmpty -> UiLedgerBookDeleteResult.NotEmpty(
                    activeEntryCount = result.activeEntryCount,
                    deletedEntryCount = result.deletedEntryCount
                )
                DataLedgerBookDeleteResult.NotFound -> error("账本不存在")
            }
        },
        onCreateFundingAccount = { label, paymentSource ->
            dependencies.local.ledgerRepository.createFundingAccount(label, paymentSource)
        },
        onUpdateFundingAccount = { id, label, paymentSource ->
            dependencies.local.ledgerRepository.updateFundingAccount(id, label, paymentSource)
        },
        onSetDefaultFundingAccount = { id ->
            when (dependencies.account.cloudSettingsCoordinator.setDefaultFundingAccount(id)) {
                is AccountCloudSettingsOperationResult.CloudFailure ->
                    context.appState.snackbarHostState.showSnackbar("默认账户已保存到本机，云端同步失败")
                AccountCloudSettingsOperationResult.CloudWritesBlocked,
                AccountCloudSettingsOperationResult.InFlight,
                AccountCloudSettingsOperationResult.Stale,
                AccountCloudSettingsOperationResult.Success -> Unit
            }
        },
        onDeleteFundingAccount = { id ->
            when (val result = dependencies.local.ledgerRepository.deleteFundingAccount(id)) {
                DataFundingAccountDeleteResult.Deleted -> UiFundingAccountDeleteResult.Deleted
                is DataFundingAccountDeleteResult.Referenced -> UiFundingAccountDeleteResult.Referenced(
                    activeLedgerEntryCount = result.activeLedgerEntryCount,
                    deletedLedgerEntryCount = result.deletedLedgerEntryCount,
                    pendingEntryCount = result.pendingEntryCount,
                    ignoredEntryCount = result.ignoredEntryCount
                )
                DataFundingAccountDeleteResult.NotFound -> error("资金账户不存在")
            }
        },
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
    ReportsScreen(
        entries = context.presentation.ledgerEntries,
        reportUiModel = context.presentation.reportUiModel,
        categoryRankingListState = context.appState.reportCategoryRankingListState,
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
