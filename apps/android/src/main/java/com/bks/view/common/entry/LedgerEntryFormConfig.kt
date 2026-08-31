package com.bks.view.common.entry

import com.bks.viewmodel.ledger.LedgerEntryFormState
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.model.domain.ledger.FlowDirection
internal data class LedgerEntryFormConfig(
    val flowDirections: List<FlowDirection>,
    val allowCreateFundingAccount: Boolean,
    val saveLabel: String,
    val onExit: () -> Unit,
    val onSave: suspend (LedgerEntryFormSubmission) -> Unit,
    val onDelete: (() -> Unit)?,
    val snackbarHostState: SnackbarHostState,
    val showDefaultFundingAccountHint: Boolean = false,
    val leadingContent: @Composable (
        LedgerEntryFormState,
        (LedgerEntryFormState) -> Unit
    ) -> Unit = { _, _ -> }
)
