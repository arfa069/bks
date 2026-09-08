package com.bks.model.platform.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.bks.R
import com.bks.model.domain.notifications.BookkeepingInterruption

internal class AttentionNotificationPublisher(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(channel: String): Boolean {
        val name = if (channel == INTERRUPTION_CHANNEL) "自动记账中断" else "待确认积压"
        manager.createNotificationChannel(NotificationChannel(channel, name, NotificationManager.IMPORTANCE_DEFAULT))
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun isActive(tag: String): Boolean = manager.activeNotifications.any { it.tag == tag && it.id == NOTIFICATION_ID }
    fun cancel(tag: String) = manager.cancel(tag, NOTIFICATION_ID)

    fun interruption(reason: BookkeepingInterruption, hideDetails: Boolean, alert: Boolean) {
        val text = when (reason) {
            BookkeepingInterruption.PermissionMissing -> "无障碍权限未开启，点击检查设置"
            BookkeepingInterruption.ServiceDisconnected -> "无障碍服务持续未运行，点击检查设置"
        }
        manager.notify(INTERRUPTION_TAG, NOTIFICATION_ID,
            base(INTERRUPTION_CHANNEL, hideDetails, alert)
                .setContentTitle("自动记账需要处理").setContentText(text)
                .setContentIntent(AttentionNotificationIntents.contentIntent(context)).build())
    }

    fun backlog(count: Int, hideDetails: Boolean, alert: Boolean) {
        manager.notify(BACKLOG_TAG, NOTIFICATION_ID,
            base(BACKLOG_CHANNEL, hideDetails, alert)
                .setContentTitle("$count 笔记录待确认").setContentText("抽空检查一下，确认后才会计入账本")
                .setContentIntent(ReviewNotificationIntents.contentIntent(context, null)).build())
    }

    private fun base(channel: String, hide: Boolean, alert: Boolean): Notification.Builder =
        Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_review)
            .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setOnlyAlertOnce(!alert)
            .setVisibility(if (hide) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_review)
                .setContentTitle("${context.getString(R.string.app_name)}有一条提醒").setContentText("解锁后查看详情").build())

    companion object {
        const val INTERRUPTION_CHANNEL = "bookkeeping_interruption_v1"
        const val BACKLOG_CHANNEL = "pending_backlog_v1"
        const val INTERRUPTION_TAG = "bks.attention.interruption"
        const val BACKLOG_TAG = "bks.attention.backlog"
        private const val NOTIFICATION_ID = 0
    }
}
