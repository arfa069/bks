package com.bks.model.data.local.settings

import android.content.Context
import com.bks.model.domain.notifications.NotificationPreferences
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalNotificationPreferences(context: Context) : NotificationPreferencesRepository {
    private val preferences = context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        NotificationPreferences(
            enabled = preferences.getBoolean("enabled", true),
            hideDetailsOnLockScreen = preferences.getBoolean("hide_lock_screen_details", true),
            interruptionEnabled = preferences.getBoolean("interruption_enabled", true),
            backlogEnabled = preferences.getBoolean("backlog_enabled", false)
        )
    )
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    override suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    override suspend fun setHideDetailsOnLockScreen(hide: Boolean) =
        update { it.copy(hideDetailsOnLockScreen = hide) }

    override suspend fun setInterruptionEnabled(enabled: Boolean) = update { it.copy(interruptionEnabled = enabled) }
    override suspend fun setBacklogEnabled(enabled: Boolean) = update { it.copy(backlogEnabled = enabled) }

    private suspend fun update(transform: (NotificationPreferences) -> NotificationPreferences) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(state.value)
                check(preferences.edit().putBoolean("enabled", next.enabled)
                    .putBoolean("hide_lock_screen_details", next.hideDetailsOnLockScreen)
                    .putBoolean("interruption_enabled", next.interruptionEnabled)
                    .putBoolean("backlog_enabled", next.backlogEnabled).commit())
                mutableState.value = next
            }
        }
    }
}
