package com.bks.feature.ledger

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
import com.bks.data.local.FlowDirection
import com.bks.ui.entry.FundingAccountUiModel
import com.bks.ui.entry.LedgerCategoryUiModel
import com.bks.ui.entry.LedgerEntryFormConfig
import com.bks.ui.entry.LedgerEntryFormState
import com.bks.ui.entry.LedgerEntryFormSubmission
import com.bks.ui.entry.SharedLedgerEntryForm

@Composable
internal fun ManualLedgerEntryScreen(
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    onExit: () -> Unit,
    onCreateEntry: suspend (LedgerEntryFormSubmission) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = rememberSaveable(saver = LedgerEntryFormState.Saver) {
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
