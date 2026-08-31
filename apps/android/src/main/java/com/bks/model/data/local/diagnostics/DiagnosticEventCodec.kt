package com.bks.model.data.local.diagnostics

import com.bks.model.domain.diagnostics.formatDiagnosticTimestamp

import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object DiagnosticEventCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(event: DiagnosticEvent): String = buildJsonObject {
        put("schemaVersion", 2)
        put("time", formatDiagnosticTimestamp(event.metadata.timestampEpochMillis))
        put("timestampEpochMillis", event.metadata.timestampEpochMillis)
        put("level", event.metadata.level.name)
        put("component", event.metadata.component.name)
        put("event", event.metadata.event)
        put("traceId", event.metadata.traceId)
        event.metadata.sessionId?.let { put("sessionId", it) }
        put("source", event.metadata.source.name)
        event.metadata.outcome?.let { put("outcome", it) }
        event.metadata.reason?.let { put("reason", it) }
        put("suppressedCount", event.metadata.suppressedCount)
        event.metadata.count?.let { put("count", it) }
        event.metadata.durationMillis?.let { put("durationMillis", it) }
        event.metadata.triggeredEvents?.let { put("triggeredEvents", it) }
        event.metadata.nodeCount?.let { put("nodeCount", it) }
        put("nodeLimitReached", event.metadata.nodeLimitReached)
        event.metadata.insertedCount?.let { put("insertedCount", it) }
        put("attributes", buildJsonObject {
            event.metadata.attributes.forEach { (name, value) -> put(name, value) }
        })
        put("sensitivePayload", buildJsonObject {
            event.sensitivePayload.fields.forEach { (field, value) -> put(field.name, value) }
        })
        put("truncatedFields", buildJsonArray {
            event.truncatedFields.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
        })
    }.toString()

    fun decode(line: String): DiagnosticEvent {
        val root = json.parseToJsonElement(line).jsonObject
        fun optionalString(name: String) = root[name]?.jsonPrimitive?.contentOrNull
        val payload = root["sensitivePayload"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
            runCatching { DiagnosticSensitiveField.valueOf(key) to value.jsonPrimitive.content }.getOrNull()
        }.toMap()
        val truncated = root["truncatedFields"]?.jsonArray.orEmpty().mapNotNull {
            runCatching { DiagnosticSensitiveField.valueOf(it.jsonPrimitive.content) }.getOrNull()
        }.toSet()
        return DiagnosticEvent(
            metadata = DiagnosticEventMetadata(
                timestampEpochMillis = root.getValue("timestampEpochMillis").jsonPrimitive.longOrNull
                    ?: error("Missing timestamp"),
                level = DiagnosticLevel.valueOf(root.getValue("level").jsonPrimitive.content),
                component = DiagnosticComponent.valueOf(root.getValue("component").jsonPrimitive.content),
                event = root.getValue("event").jsonPrimitive.content,
                traceId = root.getValue("traceId").jsonPrimitive.content,
                sessionId = optionalString("sessionId"),
                source = optionalString("source")?.let(DiagnosticSource::valueOf)
                    ?: DiagnosticSource.Unknown,
                outcome = optionalString("outcome"),
                reason = optionalString("reason"),
                suppressedCount = root["suppressedCount"]?.jsonPrimitive?.intOrNull ?: 0,
                count = root["count"]?.jsonPrimitive?.intOrNull,
                durationMillis = root["durationMillis"]?.jsonPrimitive?.longOrNull,
                triggeredEvents = optionalString("triggeredEvents"),
                nodeCount = root["nodeCount"]?.jsonPrimitive?.intOrNull,
                nodeLimitReached = root["nodeLimitReached"]?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: false,
                insertedCount = root["insertedCount"]?.jsonPrimitive?.intOrNull,
                attributes = root["attributes"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { name to it }
                }.toMap()
            ),
            sensitivePayload = DiagnosticSensitivePayload(payload),
            truncatedFields = truncated
        )
    }
}
