package com.bks.model.domain.diagnostics

import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

enum class DiagnosticLevel { Debug, Info, Warning, Error }

enum class DiagnosticComponent {
    Persistence,
    Application,
    AutomaticBookkeeping
}

enum class DiagnosticSource { System, Accessibility, Unknown }

enum class DiagnosticSensitiveField {
    ExceptionDetails,
    PageText
}

data class DiagnosticEventMetadata(
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val level: DiagnosticLevel,
    val component: DiagnosticComponent,
    val event: String,
    val traceId: String = newDiagnosticTraceId(),
    val sessionId: String? = null,
    val source: DiagnosticSource = DiagnosticSource.Unknown,
    val outcome: String? = null,
    val reason: String? = null,
    val suppressedCount: Int = 0,
    val count: Int? = null,
    val durationMillis: Long? = null,
    val triggeredEvents: String? = null,
    val nodeCount: Int? = null,
    val nodeLimitReached: Boolean = false,
    val insertedCount: Int? = null,
    val attributes: Map<String, String> = emptyMap()
)

data class DiagnosticSensitivePayload(
    val fields: Map<DiagnosticSensitiveField, String> = emptyMap()
)

data class DiagnosticEvent(
    val metadata: DiagnosticEventMetadata,
    val sensitivePayload: DiagnosticSensitivePayload = DiagnosticSensitivePayload(),
    val truncatedFields: Set<DiagnosticSensitiveField> = emptySet()
)

data class DiagnosticLogStats(
    val eventCount: Int = 0,
    val storedBytes: Long = 0,
    val segmentCount: Int = 0
)

interface DiagnosticRecorder {
    fun record(event: DiagnosticEvent)
}

interface DiagnosticLogRepository : DiagnosticRecorder {
    val enabled: StateFlow<Boolean>
    val events: StateFlow<List<DiagnosticEvent>>
    val stats: StateFlow<DiagnosticLogStats>

    fun setEnabled(enabled: Boolean, userConfirmed: Boolean = false): Boolean
    suspend fun refresh(limit: Int = 1_000)
    suspend fun clear(keepEnabledPreference: Boolean = true)
    suspend fun exportPlainText(): String
}

object NoOpDiagnosticRecorder : DiagnosticRecorder {
    override fun record(event: DiagnosticEvent) = Unit
}

class InMemoryDiagnosticRecorder(
    var isEnabled: Boolean = true
) : DiagnosticRecorder {
    private val mutableEvents = mutableListOf<DiagnosticEvent>()
    val events: List<DiagnosticEvent> get() = synchronized(mutableEvents) { mutableEvents.toList() }

    override fun record(event: DiagnosticEvent) {
        if (isEnabled) synchronized(mutableEvents) { mutableEvents += event }
    }
}

fun newDiagnosticTraceId(): String = UUID.randomUUID().toString()

fun Throwable.toDiagnosticExceptionDetails(): String = stackTraceToString()

internal const val BILL_SYNC_CAPTURE_BATCH_EVENT = "bill_sync_capture_batch"
internal const val BILL_SYNC_COORDINATOR_EVENT = "bill_sync_coordinator"
internal const val BILL_SYNC_SERVICE_EVENT = "bill_sync_service"
internal const val BILL_SYNC_SETTING_EVENT = "bill_sync_setting"
internal const val BILL_SYNC_APP_EVENT = "bill_sync_app"
internal const val BILL_SYNC_WAVE_EVENT = "bill_sync_wave"
internal const val BILL_SYNC_RUNTIME_EVENT = "bill_sync_runtime"

internal val UNCOALESCED_DIAGNOSTIC_EVENTS = setOf(
    BILL_SYNC_CAPTURE_BATCH_EVENT,
    BILL_SYNC_COORDINATOR_EVENT,
    BILL_SYNC_SERVICE_EVENT,
    BILL_SYNC_SETTING_EVENT,
    BILL_SYNC_APP_EVENT,
    BILL_SYNC_WAVE_EVENT,
    BILL_SYNC_RUNTIME_EVENT
)

const val DIAGNOSTICS_EXPORT_EXTENSION = "log"
