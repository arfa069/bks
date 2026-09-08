package com.bks.model.platform.notifications

import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import com.bks.model.domain.notifications.ReviewNotificationSink
import com.bks.model.domain.review.ReviewQueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ReviewNotificationRuntime(
    private val scope: CoroutineScope,
    private val repository: ReviewQueueRepository,
    private val preferences: NotificationPreferencesRepository,
    private val publisher: ReviewNotificationPublisher
) : ReviewNotificationSink {
    private val batches = Channel<Set<String>>(Channel.BUFFERED)
    private val mutex = Mutex()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (first in batches) {
                delay(BATCH_WINDOW_MILLIS)
                val ids = first.toMutableSet()
                while (true) ids += batches.tryReceive().getOrNull() ?: break
                refresh(ids)
            }
        }
        scope.launch {
            repository.observeSnapshot().collect { refresh(emptySet()) }
        }
        scope.launch {
            preferences.state.collect { refresh(emptySet()) }
        }
    }

    override fun enqueue(entries: List<PendingEntry>) {
        if (preferences.state.value.enabled && entries.isNotEmpty()) {
            batches.trySend(entries.map { it.id }.toSet())
        }
    }

    private suspend fun refresh(newIds: Set<String>) {
        try {
            mutex.withLock {
                // Re-read after taking the lock so confirmation/clear cannot replay a stale snapshot.
                publisher.refresh(repository.loadSnapshot().pendingEntries, newIds, preferences.state.value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Notification permission, channel state and binder errors are unrelated to capture success.
        }
    }

    private companion object {
        const val BATCH_WINDOW_MILLIS = 750L
    }
}
