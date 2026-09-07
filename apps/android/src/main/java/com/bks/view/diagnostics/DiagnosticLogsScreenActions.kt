package com.bks.view.diagnostics

internal data class DiagnosticLogsScreenActions(
    val onBack: () -> Unit,
    val onSetEnabled: (Boolean, Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onExport: () -> Unit,
    val onClear: () -> Unit,
    val onDismissExportResult: (Long) -> Unit
)
