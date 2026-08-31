package com.bks.model.data.local.diagnostics

import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticLogRepository
import com.bks.model.domain.diagnostics.DiagnosticLogStats
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
import com.bks.model.domain.diagnostics.formatDiagnosticLogLine
import com.bks.model.domain.diagnostics.UNCOALESCED_DIAGNOSTIC_EVENTS
import android.content.Context
import android.util.Log
import com.bks.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LongParameterList", "TooManyFunctions")
class AndroidDiagnosticLogRepository internal constructor(
    context: Context,
    private val store: DiagnosticPlainTextStore = DiagnosticPlainTextStore(
        directory = File(context.noBackupFilesDir, DIAGNOSTICS_DIRECTORY)
    ),
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val buildDefaultEnabled: Boolean = isDebugBuild,
    private val clock: () -> Long = System::currentTimeMillis,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val exportDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DiagnosticLogRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + storageDispatcher)
    private val mutex = Mutex()
    private val clearGeneration = AtomicLong(0)
    private val coalesced = ConcurrentHashMap<CoalesceKey, CoalesceState>()
    private val writeQueue = Channel<WriteQueueItem>(WRITE_QUEUE_CAPACITY)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, buildDefaultEnabled))
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val _stats = MutableStateFlow(DiagnosticLogStats())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    override val stats: StateFlow<DiagnosticLogStats> = _stats.asStateFlow()

    init {
        scope.launch {
            runCatching { consumeWriteQueue() }
                .onFailure { logFixedFailure("diagnostic_worker_failed") }
        }
    }

    override fun setEnabled(enabled: Boolean, userConfirmed: Boolean): Boolean {
        if (enabled && !isDebugBuild && !userConfirmed) return false
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        return true
    }

    override fun record(event: DiagnosticEvent) {
        if (!_enabled.value) return
        val queued = writeQueue.trySend(
            WriteQueueItem.Event(event, clearGeneration.get())
        )
        if (queued.isFailure) logFixedFailure("diagnostic_queue_full")
    }

    internal suspend fun recordNow(event: DiagnosticEvent) {
        if (!_enabled.value) return
        recordBatchNow(
            listOf(QueuedEvent(event, clearGeneration.get()))
        )
    }

    override suspend fun refresh(limit: Int) {
        awaitQueuedWrites()
        withContext(storageDispatcher) {
            mutex.withLock {
                flushSuppressedLocked()
                refreshLocked(limit)
            }
        }
    }

    override suspend fun clear(keepEnabledPreference: Boolean) {
        clearGeneration.incrementAndGet()
        withContext(storageDispatcher) {
            mutex.withLock {
                coalesced.values.forEach { it.flushJob?.cancel() }
                coalesced.clear()
                store.clear()
                _events.value = emptyList()
                _stats.value = DiagnosticLogStats()
                if (!keepEnabledPreference) {
                    preferences.edit().remove(KEY_ENABLED).apply()
                    _enabled.value = buildDefaultEnabled
                }
            }
        }
    }

    override suspend fun exportPlainText(): String {
        awaitQueuedWrites()
        return withContext(exportDispatcher) {
            mutex.withLock {
                flushSuppressedLocked()
                val lines = store.readAll().map { event ->
                    formatDiagnosticLogLine(sanitizeDiagnosticEvent(event))
                }
                if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
            }
        }
    }

    private suspend fun consumeWriteQueue() {
        while (true) {
            val first = writeQueue.receive()
            val events = mutableListOf<QueuedEvent>()
            val barriers = mutableListOf<CompletableDeferred<Unit>>()
            collectQueueItem(first, events, barriers)
            if (first is WriteQueueItem.Event && first.event.metadata.level != DiagnosticLevel.Error) {
                delay(WRITE_BATCH_WINDOW_MILLIS)
            }
            var drainedItems = 1
            while (drainedItems < MAX_WRITE_BATCH_ITEMS) {
                val next = writeQueue.tryReceive().getOrNull() ?: break
                collectQueueItem(next, events, barriers)
                drainedItems += 1
            }
            if (events.isNotEmpty()) {
                runCatching { recordBatchNow(events) }
                    .onFailure { logFixedFailure("diagnostic_write_failed") }
            }
            barriers.forEach { it.complete(Unit) }
        }
    }

    private fun collectQueueItem(
        item: WriteQueueItem,
        events: MutableList<QueuedEvent>,
        barriers: MutableList<CompletableDeferred<Unit>>
    ) {
        when (item) {
            is WriteQueueItem.Event -> events += QueuedEvent(item.event, item.generation)
            is WriteQueueItem.Barrier -> barriers += item.completion
        }
    }

    private suspend fun awaitQueuedWrites() {
        val completion = CompletableDeferred<Unit>()
        writeQueue.send(WriteQueueItem.Barrier(completion))
        completion.await()
    }

    private suspend fun recordBatchNow(events: List<QueuedEvent>) {
        if (events.isEmpty()) return
        val sanitizedEvents = events.mapNotNull { queued ->
            runCatching { queued.copy(event = sanitizeDiagnosticEvent(queued.event)) }
                .onFailure { logFixedFailure("diagnostic_sanitize_failed") }
                .getOrNull()
        }
        mutex.withLock {
            val pendingAppends = mutableListOf<DiagnosticEvent>()
            sanitizedEvents.forEach { queued ->
                if (queued.generation == clearGeneration.get()) {
                    prepareRecordLocked(queued.event, pendingAppends)
                }
            }
            appendAllLocked(pendingAppends)
        }
    }

    private fun prepareRecordLocked(
        event: DiagnosticEvent,
        pendingAppends: MutableList<DiagnosticEvent>
    ) {
        if (event.metadata.event in UNCOALESCED_DIAGNOSTIC_EVENTS) {
            pendingAppends += event
            return
        }
        val key = CoalesceKey(
            event.metadata.component,
            event.metadata.source,
            event.metadata.reason ?: event.metadata.event
        )
        val previous = coalesced[key]
        val now = clock()
        if (previous != null) {
            if (now - previous.firstTimestamp < COALESCE_WINDOW_MILLIS) {
                previous.suppressedCount += 1
                if (previous.flushJob == null) {
                    previous.flushJob = scheduleSuppressedFlush(key, previous)
                }
                return
            }
            previous.flushJob?.cancel()
            coalesced.remove(key)
            suppressedEvent(previous)?.let(pendingAppends::add)
        }
        pendingAppends += event
        coalesced[key] = CoalesceState(
            firstTimestamp = now,
            template = event
        )
    }

    private fun scheduleSuppressedFlush(key: CoalesceKey, state: CoalesceState): Job = scope.launch {
        delay(COALESCE_WINDOW_MILLIS)
        mutex.withLock {
            if (coalesced[key] !== state) return@withLock
            coalesced.remove(key)
            suppressedEvent(state)?.let { appendAllLocked(listOf(it)) }
        }
    }

    private fun flushSuppressedLocked() {
        val states = coalesced.values.toList()
        coalesced.clear()
        val summaries = states.mapNotNull { state ->
            state.flushJob?.cancel()
            suppressedEvent(state)
        }
        appendAllLocked(summaries)
    }

    private fun suppressedEvent(state: CoalesceState): DiagnosticEvent? {
        if (state.suppressedCount <= 0) return null
        return state.template.copy(
            metadata = state.template.metadata.copy(
                timestampEpochMillis = clock(),
                suppressedCount = state.suppressedCount
            ),
            sensitivePayload = DiagnosticSensitivePayload(),
            truncatedFields = emptySet()
        )
    }

    private fun appendAllLocked(events: List<DiagnosticEvent>) {
        if (events.isEmpty()) return
        val rotated = store.appendAll(events)
        events.forEach { logMetadata(it.metadata) }
        if (rotated) {
            refreshLocked(MAX_VISIBLE_EVENTS)
            return
        }
        _events.value = (events.asReversed() + _events.value).take(MAX_VISIBLE_EVENTS)
        _stats.value = DiagnosticLogStats(
            eventCount = _stats.value.eventCount + events.size,
            storedBytes = store.storedBytes(),
            segmentCount = store.segmentCount()
        )
    }

    private fun refreshLocked(limit: Int) {
        val latest = store.readLatest(limit)
        _events.value = latest.asReversed()
        _stats.value = DiagnosticLogStats(
            eventCount = store.eventCount(),
            storedBytes = store.storedBytes(),
            segmentCount = store.segmentCount()
        )
    }

    private fun logMetadata(metadata: DiagnosticEventMetadata) {
        val safeReason = metadata.reason
            ?.replace(Regex("[\\r\\n]"), "_")
            ?.take(80)
        Log.i(
            LOG_TAG,
            "component=${metadata.component.name} event=${metadata.event.take(60)} " +
                "traceId=${metadata.traceId} sessionId=${metadata.sessionId.orEmpty()} " +
                "source=${metadata.source.name} outcome=${metadata.outcome.orEmpty()} " +
                "reason=${safeReason.orEmpty()} count=${metadata.count ?: 0} " +
                "suppressed=${metadata.suppressedCount} durationMs=${metadata.durationMillis ?: 0}"
        )
    }

    private sealed interface WriteQueueItem {
        data class Event(val event: DiagnosticEvent, val generation: Long) : WriteQueueItem
        data class Barrier(val completion: CompletableDeferred<Unit>) : WriteQueueItem
    }

    private data class QueuedEvent(
        val event: DiagnosticEvent,
        val generation: Long
    )

    private data class CoalesceKey(
        val component: DiagnosticComponent,
        val source: DiagnosticSource,
        val reason: String
    )

    private data class CoalesceState(
        val firstTimestamp: Long,
        val template: DiagnosticEvent,
        var suppressedCount: Int = 0,
        var flushJob: Job? = null
    )

    private companion object {
        const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        const val PREFERENCES_NAME = "diagnostic_log_preferences"
        const val KEY_ENABLED = "enabled"
        const val COALESCE_WINDOW_MILLIS = 5_000L
        const val WRITE_BATCH_WINDOW_MILLIS = 150L
        const val WRITE_QUEUE_CAPACITY = 2_048
        const val MAX_WRITE_BATCH_ITEMS = 128
        const val MAX_VISIBLE_EVENTS = 1_000
        const val LOG_TAG = "BksDiag"
    }
}

object DiagnosticLogs {
    @Volatile
    private var repository: AndroidDiagnosticLogRepository? = null

    fun get(context: Context): AndroidDiagnosticLogRepository = repository ?: synchronized(this) {
        repository ?: AndroidDiagnosticLogRepository(context.applicationContext).also { repository = it }
    }
}

private fun logFixedFailure(code: String) {
    Log.e("BksDiag", code)
}
