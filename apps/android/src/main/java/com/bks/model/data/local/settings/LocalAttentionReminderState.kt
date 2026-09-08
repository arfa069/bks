package com.bks.model.data.local.settings

import android.content.Context
import com.bks.model.domain.notifications.AttentionReminderState
import com.bks.model.domain.notifications.AttentionReminderStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalAttentionReminderState(context: Context) : AttentionReminderStateRepository {
    private val preferences = context.getSharedPreferences("attention_reminder_state", Context.MODE_PRIVATE)

    override suspend fun load(): AttentionReminderState = withContext(Dispatchers.IO) {
        AttentionReminderState(
            interruptionNotified = preferences.getBoolean("interruption_notified", false),
            lastBacklogAt = if (preferences.contains("backlog_at")) preferences.getLong("backlog_at", 0) else null,
            lastBacklogFingerprint = preferences.getString("backlog_fingerprint", null)
        )
    }

    override suspend fun save(state: AttentionReminderState) = withContext(Dispatchers.IO) {
        val editor = preferences.edit().putBoolean("interruption_notified", state.interruptionNotified)
            .putString("backlog_fingerprint", state.lastBacklogFingerprint)
        state.lastBacklogAt?.let { editor.putLong("backlog_at", it) } ?: editor.remove("backlog_at")
        check(editor.commit())
    }
}
