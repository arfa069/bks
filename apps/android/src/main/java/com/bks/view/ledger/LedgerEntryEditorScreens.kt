package com.bks.view.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bks.model.domain.ledger.FlowDirection
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.view.common.entry.LedgerEntryFormConfig
import com.bks.viewmodel.ledger.LedgerEntryFormState
import com.bks.view.common.entry.LedgerEntryFormStateSaver
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import com.bks.view.common.entry.SharedLedgerEntryForm
@Composable
internal fun ManualLedgerEntryScreen(
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    onExit: () -> Unit,
    onCreateEntry: suspend (LedgerEntryFormSubmission) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = rememberSaveable(saver = LedgerEntryFormStateSaver) {
        LedgerEntryFormState.newEntry()
    }
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "新增一笔",
            initial = initial,
            categories = categories,
            fundingAccounts = fundingAccounts,
            config = LedgerEntryFormConfig(
                flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
                allowCreateFundingAccount = false,
                saveLabel = "保存账目",
                onExit = onExit,
                onSave = onCreateEntry,
                onDelete = null,
                snackbarHostState = snackbarHostState,
                showDefaultFundingAccountHint = true
            )
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}

@Composable
internal fun LedgerEntryForm(
    title: String,
    initial: LedgerEntryFormState,
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    config: LedgerEntryFormConfig
) {
    SharedLedgerEntryForm(
        title = title,
        initial = initial,
        categories = categories,
        fundingAccounts = fundingAccounts,
        config = config.copy(
            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
            allowCreateFundingAccount = false,
            saveLabel = "保存修改",
            leadingContent = { _, _ -> }
        )
    )
}
