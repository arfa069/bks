package com.bks.view.app

internal data class DiagnosticLogsRoutePlatform(
    val exportWriterOverride: (suspend (String) -> String)? = null,
    val applySecureWindowFlag: Boolean = true
)
