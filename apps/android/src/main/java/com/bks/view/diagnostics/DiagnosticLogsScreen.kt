package com.bks.view.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bks.viewmodel.diagnostics.DiagnosticLogsUiState

@Composable
internal fun DiagnosticLogsScreen(
    state: DiagnosticLogsUiState,
    actions: DiagnosticLogsScreenActions,
    isDebugBuild: Boolean,
    modifier: Modifier = Modifier
) {
    var showEnableConfirmation by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    DiagnosticLogsContent(
        state = DiagnosticLogsContentState(
            state.enabled,
            state.stats,
            state.exporting,
            state.statusMessage
        ),
        events = state.events,
        actions = DiagnosticLogsContentActions(
            onBack = actions.onBack,
            onEnabledChange = { checked ->
                if (checked && !isDebugBuild) {
                    showEnableConfirmation = true
                } else {
                    actions.onSetEnabled(checked, isDebugBuild)
                }
            },
            onRefresh = actions.onRefresh,
            onExportClick = actions.onExport,
            onClearClick = { showClearConfirmation = true }
        ),
        modifier = modifier
    )

    if (showEnableConfirmation) {
        EnableDiagnosticsConfirmationDialog(
            onConfirm = {
                actions.onSetEnabled(true, true)
                showEnableConfirmation = false
            },
            onDismiss = { showEnableConfirmation = false }
        )
    }
    if (showClearConfirmation) {
        ClearDiagnosticsConfirmationDialog(
            onConfirm = {
                showClearConfirmation = false
                actions.onClear()
            },
            onDismiss = { showClearConfirmation = false }
        )
    }
    state.exportResult?.let { result ->
        ExportResultDialog(result.title, result.message) {
            actions.onDismissExportResult(result.id)
        }
    }
}
