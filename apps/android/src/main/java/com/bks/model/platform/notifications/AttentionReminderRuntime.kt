package com.bks.model.platform.notifications

import com.bks.model.domain.notifications.AutomaticBookkeepingHealth
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import com.bks.model.domain.review.ReviewQueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

internal class AttentionReminderRuntime(
    private val scope: CoroutineScope,
    private val health: Flow<AutomaticBookkeepingHealth>,
    private val preferences: NotificationPreferencesRepository,
    private val repository: ReviewQueueRepository,
    private val coordinator: AttentionReminderCoordinator,
    private val scheduleBacklog: (Boolean) -> Unit
) {
    private var started = false
    @Volatile private var latestHealth = AutomaticBookkeepingHealth(false, false, false)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(health.onEach { latestHealth = it }, preferences.state) { status, settings -> status to settings }
                .retryWhen { _, _ -> delay(RETRY_MILLIS); true }
                .collectLatest { (status, settings) ->
                    safely {
                        if (status.reason == null || !settings.interruptionEnabled) {
                            coordinator.clearInterruption(resetIncident = !status.enabled)
                            if (status.enabled && status.reason == null) {
                                delay(RECOVERY_MILLIS)
                                coordinator.clearInterruption(resetIncident = true)
                            }
                        } else {
                            coordinator.remindInterruption(allowAlert = false) { latestHealth }
                            delay(INTERRUPTION_DELAY_MILLIS)
                            coordinator.remindInterruption { latestHealth }
                        }
                    }
                }
        }
        scope.launch {
            preferences.state.map { it.backlogEnabled }.distinctUntilChanged().collect { enabled ->
                safely { scheduleBacklog(enabled) }
            }
        }
        scope.launch {
            combine(repository.observeSnapshot(), preferences.state) { _, _ -> Unit }
                .retryWhen { _, _ -> delay(RETRY_MILLIS); true }
                .collect { safely { coordinator.refreshBacklog(allowAlert = false) } }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Reminders are independent of capture and must not crash the application scope.
        }
    }

    companion object {
        const val INTERRUPTION_DELAY_MILLIS = 30_000L
        const val RECOVERY_MILLIS = 5_000L
        private const val RETRY_MILLIS = 5_000L
    }
}
