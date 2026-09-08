package com.bks.model.platform.notifications

import com.bks.model.domain.notifications.AttentionReminderStateRepository
import com.bks.model.domain.notifications.AutomaticBookkeepingHealth
import com.bks.model.domain.notifications.BacklogReminderPolicy
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import com.bks.model.domain.review.ReviewQueueRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AttentionReminderCoordinator(
    private val repository: ReviewQueueRepository,
    private val preferences: NotificationPreferencesRepository,
    private val stateRepository: AttentionReminderStateRepository,
    private val publisher: AttentionNotificationPublisher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun clearInterruption(resetIncident: Boolean) = mutex.withLock {
        publisher.cancel(AttentionNotificationPublisher.INTERRUPTION_TAG)
        if (resetIncident) {
            val state = stateRepository.load()
            if (state.interruptionNotified) stateRepository.save(state.copy(interruptionNotified = false))
        }
    }

    suspend fun remindInterruption(allowAlert: Boolean = true, currentHealth: () -> AutomaticBookkeepingHealth) = mutex.withLock {
        val health = currentHealth()
        val reason = health.reason ?: return@withLock
        val settings = preferences.state.value
        if (!settings.interruptionEnabled || !publisher.canPost(AttentionNotificationPublisher.INTERRUPTION_CHANNEL)) {
            publisher.cancel(AttentionNotificationPublisher.INTERRUPTION_TAG)
            return@withLock
        }
        val state = stateRepository.load()
        val alert = allowAlert && !state.interruptionNotified
        if (!alert && !publisher.isActive(AttentionNotificationPublisher.INTERRUPTION_TAG)) return@withLock
        // Reserve before posting: process death may lose a best-effort reminder, but cannot replay this incident.
        if (alert) stateRepository.save(state.copy(interruptionNotified = true))
        if (preferences.state.value.interruptionEnabled && currentHealth().reason == reason) {
            publisher.interruption(reason, preferences.state.value.hideDetailsOnLockScreen, alert)
        }
    }

    suspend fun refreshBacklog(allowAlert: Boolean) = mutex.withLock {
        val settings = preferences.state.value
        if (!settings.backlogEnabled || !publisher.canPost(AttentionNotificationPublisher.BACKLOG_CHANNEL)) {
            publisher.cancel(AttentionNotificationPublisher.BACKLOG_TAG)
            return@withLock
        }
        val entries = repository.loadSnapshot().pendingEntries
        val now = clock()
        if (!BacklogReminderPolicy.qualifies(entries, now)) {
            publisher.cancel(AttentionNotificationPublisher.BACKLOG_TAG)
            return@withLock
        }
        val state = stateRepository.load()
        val fingerprint = BacklogReminderPolicy.fingerprint(entries)
        val alert = allowAlert && BacklogReminderPolicy.canAlert(fingerprint, state, now)
        if (!alert && !publisher.isActive(AttentionNotificationPublisher.BACKLOG_TAG)) return@withLock
        if (alert) stateRepository.save(state.copy(lastBacklogAt = now, lastBacklogFingerprint = fingerprint))
        // A concurrent confirmation/import can change Room while the reservation is being persisted.
        val latest = repository.loadSnapshot().pendingEntries
        if (!preferences.state.value.backlogEnabled || !BacklogReminderPolicy.qualifies(latest, clock())) {
            publisher.cancel(AttentionNotificationPublisher.BACKLOG_TAG)
            return@withLock
        }
        if (fingerprint == BacklogReminderPolicy.fingerprint(latest)) {
            publisher.backlog(latest.size, preferences.state.value.hideDetailsOnLockScreen, alert)
        }
    }
}
