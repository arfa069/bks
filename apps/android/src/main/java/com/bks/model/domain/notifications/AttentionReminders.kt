package com.bks.model.domain.notifications

import com.bks.model.domain.ledger.PendingEntry
import java.security.MessageDigest

data class AutomaticBookkeepingHealth(val enabled: Boolean, val accessGranted: Boolean, val connected: Boolean) {
    val reason: BookkeepingInterruption?
        get() = when {
            !enabled -> null
            !accessGranted -> BookkeepingInterruption.PermissionMissing
            !connected -> BookkeepingInterruption.ServiceDisconnected
            else -> null
        }
}

enum class BookkeepingInterruption { PermissionMissing, ServiceDisconnected }

data class AttentionReminderState(
    val interruptionNotified: Boolean = false,
    val lastBacklogAt: Long? = null,
    val lastBacklogFingerprint: String? = null
)

interface AttentionReminderStateRepository {
    suspend fun load(): AttentionReminderState
    suspend fun save(state: AttentionReminderState)
}

object BacklogReminderPolicy {
    const val MIN_COUNT = 10
    const val MIN_AGE_MILLIS = 48L * 60 * 60 * 1_000
    const val COOLDOWN_MILLIS = 24L * 60 * 60 * 1_000

    fun qualifies(entries: List<PendingEntry>, now: Long): Boolean = entries.isNotEmpty() &&
        (entries.size >= MIN_COUNT || entries.any { now >= it.capturedAtEpochMillis && now - it.capturedAtEpochMillis >= MIN_AGE_MILLIS })

    fun canAlert(fingerprint: String, state: AttentionReminderState, now: Long): Boolean =
        fingerprint != state.lastBacklogFingerprint &&
            (state.lastBacklogAt == null || now >= state.lastBacklogAt && now - state.lastBacklogAt >= COOLDOWN_MILLIS)

    /** Only an opaque digest is persisted, never merchant names, amounts or raw page content. */
    fun fingerprint(entries: List<PendingEntry>): String {
        val input = entries.map { it.id }.sorted().joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
