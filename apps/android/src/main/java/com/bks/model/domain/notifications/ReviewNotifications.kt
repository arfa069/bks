package com.bks.model.domain.notifications

import com.bks.model.domain.ledger.PendingEntry
import kotlinx.coroutines.flow.StateFlow

/** Best-effort, non-blocking handoff of entries that have actually committed. */
fun interface ReviewNotificationSink {
    fun enqueue(entries: List<PendingEntry>)
}

data class NotificationPreferences(
    val enabled: Boolean = true,
    val hideDetailsOnLockScreen: Boolean = true
)

interface NotificationPreferencesRepository {
    val state: StateFlow<NotificationPreferences>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setHideDetailsOnLockScreen(hide: Boolean)
}
