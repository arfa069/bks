package com.bks.ui.entry

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.data.local.FlowDirection

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
