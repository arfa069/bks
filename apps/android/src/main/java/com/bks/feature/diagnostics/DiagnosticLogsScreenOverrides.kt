package com.bks.feature.diagnostics

import androidx.lifecycle.ViewModelProvider

internal data class DiagnosticLogsScreenOverrides(
    val viewModelFactory: ViewModelProvider.Factory? = null,
    val applySecureWindowFlag: Boolean = true,
    val exportWriter: (suspend (String) -> String)? = null
)
