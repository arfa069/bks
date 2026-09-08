package com.bks.model.platform.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object ReviewNotificationIntents {
    private const val REVIEW_ACTION_SUFFIX = ".action.OPEN_PENDING_REVIEW"

    fun contentIntent(context: Context, entryId: String?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.action = context.packageName + REVIEW_ACTION_SUFFIX
        intent.data = Uri.Builder().scheme("bks-notification").authority("review")
            .apply { entryId?.let(::appendPath) }.build()
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Empty ID opens the list; null is not a notification navigation request. */
    fun consume(context: Context, intent: Intent?): String? {
        if (intent?.action != context.packageName + REVIEW_ACTION_SUFFIX) return null
        val data = intent.data ?: return null
        if (data.scheme != "bks-notification" || data.authority != "review" || data.pathSegments.size > 1) return null
        val id = data.pathSegments.singleOrNull().orEmpty()
        if (id.length > 256) return null
        intent.action = null
        intent.data = null
        return id
    }
}
