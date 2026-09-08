package com.bks.model.platform.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object AttentionNotificationIntents {
    private const val ACTION_SUFFIX = ".action.OPEN_AUTOMATIC_BOOKKEEPING"

    fun contentIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.action = context.packageName + ACTION_SUFFIX
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun consume(context: Context, intent: Intent?): Boolean {
        if (intent?.action != context.packageName + ACTION_SUFFIX || intent.data != null) return false
        intent.action = null
        return true
    }
}
