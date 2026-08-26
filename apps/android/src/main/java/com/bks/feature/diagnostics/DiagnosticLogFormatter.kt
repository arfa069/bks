package com.bks.feature.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatDiagnosticTimestamp(timestampEpochMillis: Long): String =
    SimpleDateFormat(DIAGNOSTIC_TIMESTAMP_PATTERN, Locale.CHINA).format(Date(timestampEpochMillis))

internal fun formatDiagnosticLogLine(event: DiagnosticEvent): String {
    val metadata = event.metadata
    val triggeredEvent = metadata.triggeredEvents ?: metadata.event
    val text = event.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
        ?: event.sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails]
        ?: ""
    return buildString {
        append(formatDiagnosticTimestamp(metadata.timestampEpochMillis))
        append(" | event=")
        append(escapeDiagnosticLogValue(triggeredEvent))
        append(" | nodes=")
        append(formatNodeCount(metadata))
        append(" | text=\"")
        append(escapeDiagnosticLogValue(text))
        append("\" | result=")
        append(formatDiagnosticResult(metadata))
    }
}

private fun formatNodeCount(metadata: DiagnosticEventMetadata): String = when {
    metadata.nodeCount == null -> "-"
    metadata.nodeLimitReached -> "${metadata.nodeCount}+"
    else -> metadata.nodeCount.toString()
}

private fun formatDiagnosticResult(metadata: DiagnosticEventMetadata): String = when (metadata.outcome) {
    "inserted" -> "成功:inserted=${metadata.insertedCount ?: 0}"
    "not_inserted" -> "未新增:${escapeDiagnosticLogValue(metadata.reason ?: "duplicate_not_inserted")}"
    "failed" -> "失败:${escapeDiagnosticLogValue(metadata.reason ?: "unknown")}"
    else -> when {
        metadata.reason != null -> "失败:${escapeDiagnosticLogValue(metadata.reason)}"
        metadata.outcome != null -> escapeDiagnosticLogValue(metadata.outcome)
        else -> "-"
    }
}

private fun escapeDiagnosticLogValue(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\"", "\\\"")
    .replace("|", "\\|")

private const val DIAGNOSTIC_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
