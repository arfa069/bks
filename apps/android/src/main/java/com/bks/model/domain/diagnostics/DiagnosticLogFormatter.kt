package com.bks.model.domain.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatDiagnosticTimestamp(timestampEpochMillis: Long): String =
    SimpleDateFormat(DIAGNOSTIC_TIMESTAMP_PATTERN, Locale.CHINA).format(Date(timestampEpochMillis))

@Suppress("CyclomaticComplexMethod")
internal fun formatDiagnosticLogLine(event: DiagnosticEvent): String {
    val metadata = event.metadata
    val attributes = metadata.attributes
    val stage = attributes[ATTRIBUTE_STAGE]
        ?: STAGE_CAPTURE.takeIf { metadata.event == BILL_SYNC_CAPTURE_BATCH_EVENT }
        ?: metadata.component.name.lowercase(Locale.ROOT)
    val displayedEvent = attributes[ATTRIBUTE_EVENT_NAME]
        ?: metadata.triggeredEvents
        ?: metadata.event
    val text = event.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
        ?: event.sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails]
        ?: ""
    return buildString {
        append(formatDiagnosticTimestamp(metadata.timestampEpochMillis))
        appendField(ATTRIBUTE_STAGE, stage)
        metadata.sessionId?.let { appendField("session", it) }
        appendField("event", displayedEvent)
        LOG_ATTRIBUTE_ORDER.forEach { name ->
            attributes[name]?.let { appendField(name, formatAttributeValue(name, it)) }
        }
        attributes.forEach { (name, value) ->
            if (name !in RESERVED_ATTRIBUTES && name !in LOG_ATTRIBUTE_ORDER) {
                appendField(name, formatAttributeValue(name, value))
            }
        }
        metadata.count?.let { appendField("count", it.toString()) }
        metadata.durationMillis
            ?.takeIf { "captureMs" !in attributes }
            ?.let { appendField("durationMs", it.toString()) }
        if (stage == STAGE_CAPTURE) {
            appendField("nodes", formatNodeCount(metadata))
            append(" | text=\"")
            append(escapeDiagnosticLogValue(text))
            append('"')
        } else if (text.isNotEmpty()) {
            append(" | text=\"")
            append(escapeDiagnosticLogValue(text))
            append('"')
        }
        append(" | result=")
        append(formatDiagnosticResult(metadata))
    }
}

private fun formatAttributeValue(name: String, value: String): String =
    if (name in TIMESTAMP_ATTRIBUTES) {
        value.toLongOrNull()?.let(::formatDiagnosticTimestamp) ?: value
    } else {
        value
    }

private fun StringBuilder.appendField(name: String, value: String) {
    append(" | ")
    append(name)
    append('=')
    append(escapeDiagnosticLogValue(value))
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
private const val ATTRIBUTE_STAGE = "stage"
private const val ATTRIBUTE_EVENT_NAME = "eventName"
private const val STAGE_CAPTURE = "capture"

private val RESERVED_ATTRIBUTES = setOf(ATTRIBUTE_STAGE, ATTRIBUTE_EVENT_NAME)
private val TIMESTAMP_ATTRIBUTES = setOf(
    "eventAtFirst",
    "eventAtLast",
    "callbackEnterAtFirst",
    "callbackEnterAtLast",
    "receivedAtFirst",
    "receivedAtLast",
    "captureAt",
    "captureEndAt",
    "lastEventAt",
    "expectedAt",
    "actualAt"
)
private val LOG_ATTRIBUTE_ORDER = listOf(
    "trigger",
    "eventAtFirst",
    "eventAtLast",
    "callbackEnterAtFirst",
    "callbackEnterAtLast",
    "receivedAtFirst",
    "receivedAtLast",
    "deliveryDelayMs",
    "frameworkDeliveryMs",
    "superDurationMs",
    "coordinatorDelayMs",
    "queueDelayMs",
    "triggerPkg",
    "triggerWin",
    "rootPkg",
    "rootWin",
    "rootBy",
    "allRoots",
    "activeRoots",
    "lookupMs",
    "collectMs",
    "recognizeMs",
    "persistMs",
    "captureAt",
    "captureEndAt",
    "captureMs"
)
