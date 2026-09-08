package com.bks.model.platform.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import com.bks.R
import com.bks.model.domain.billsync.FALLBACK_MERCHANT_TITLE
import com.bks.model.domain.billsync.amountMinorToText
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.categorizationLabel
import com.bks.model.domain.notifications.NotificationPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ReviewNotificationPublisher(
    private val context: Context,
    private val uptime: () -> Long = SystemClock::elapsedRealtime
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastAlertMillis: Long? = null

    fun refresh(pending: List<PendingEntry>, newIds: Set<String>, preferences: NotificationPreferences) {
        val active = manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
        if (!preferences.enabled || !manager.areNotificationsEnabled()) {
            active.forEach { manager.cancel(it.tag, it.id) }
            return
        }
        ensureChannel()
        val pendingById = pending.associateBy { it.id }
        val activeIds = active.filter { it.id == ENTRY_NOTIFICATION_ID }
            .map { requireNotNull(it.tag).removePrefix(TAG_PREFIX) }.toSet()
        val visibleIds = (activeIds + newIds).filter { it in pendingById }.toSet()
        activeIds.filter { it !in visibleIds }.forEach { manager.cancel(TAG_PREFIX + it, ENTRY_NOTIFICATION_ID) }
        if (visibleIds.isEmpty()) {
            manager.cancel(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID)
            return
        }
        val entries = visibleIds.mapNotNull(pendingById::get).sortedByDescending { it.capturedAtEpochMillis }
        entries.forEach { entry ->
            manager.notify(TAG_PREFIX + entry.id, ENTRY_NOTIFICATION_ID, entryNotification(entry, preferences))
        }
        val now = uptime()
        val hasNew = newIds.any { it in visibleIds && it !in activeIds }
        val alert = hasNew && (lastAlertMillis == null || now - requireNotNull(lastAlertMillis) >= ALERT_INTERVAL_MILLIS)
        manager.notify(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID, summaryNotification(entries, preferences, alert))
        if (alert) lastAlertMillis = now
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "新增待确认", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "自动记账实际新增待确认记录后的提醒"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun base(preferences: NotificationPreferences, entryId: String?): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_review)
            .setContentIntent(ReviewNotificationIntents.contentIntent(context, entryId))
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(if (preferences.hideDetailsOnLockScreen) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(
                Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_review)
                    .setContentTitle("有新增待确认记录").setContentText("解锁后查看交易详情").build()
            )

    private fun entryNotification(entry: PendingEntry, preferences: NotificationPreferences): Notification {
        val text = reviewNotificationText(entry)
        return base(preferences, entry.id)
            .setContentTitle("新增待确认 · ¥${amountMinorToText(entry.amountMinor)}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(entry.capturedAtEpochMillis)
            .setOnlyAlertOnce(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .build()
    }

    private fun summaryNotification(
        entries: List<PendingEntry>, preferences: NotificationPreferences, alert: Boolean
    ): Notification {
        val newest = entries.first()
        val style = Notification.InboxStyle()
        entries.take(MAX_SUMMARY_LINES).forEach { entry ->
            style.addLine("¥${amountMinorToText(entry.amountMinor)} · ${reviewNotificationText(entry).replace('\n', ' ')}")
        }
        return base(preferences, entries.singleOrNull()?.id)
            .setContentTitle("${entries.size} 笔待确认记录")
            .setContentText("点击查看并确认")
            .setStyle(style)
            .setWhen(newest.capturedAtEpochMillis)
            .setGroupSummary(true)
            .setOnlyAlertOnce(!alert)
            .setGroupAlertBehavior(if (alert) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_CHILDREN)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pending_review_v1"
        private const val TAG_PREFIX = "bks.review."
        private const val SUMMARY_TAG = "bks.review.summary"
        private const val GROUP_KEY = "bks.pending.review"
        private const val ENTRY_NOTIFICATION_ID = 1
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val ALERT_INTERVAL_MILLIS = 5_000L
        private const val MAX_SUMMARY_LINES = 5
    }
}

internal fun reviewNotificationText(entry: PendingEntry, zone: ZoneId = ZoneId.systemDefault()): String {
    val merchant = entry.merchantTitle.takeUnless { it.isBlank() || it == FALLBACK_MERCHANT_TITLE } ?: "商户待确认"
    val category = entry.suggestedCategoryLabel?.takeIf(String::isNotBlank) ?: "未分类"
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        .format(Instant.ofEpochMilli(entry.capturedAtEpochMillis))
    return "商户：$merchant｜建议分类：$category\n识别时间：$time｜来源：${entry.source.categorizationLabel()}"
}
