package com.bks.model.platform.notifications

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.bks.model.domain.notifications.AutomaticBookkeepingHealth
import com.bks.model.platform.billsync.BillSyncPermission
import com.bks.model.platform.billsync.BillSyncServiceHealth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal class BookkeepingHealthObserver(private val context: Context, private val enabled: Flow<Boolean>) {
    fun observe(): Flow<AutomaticBookkeepingHealth> = combine(
        enabled.distinctUntilChanged(),
        changes()
    ) { enabled, _ ->
        AutomaticBookkeepingHealth(enabled, BillSyncPermission.isGranted(context), BillSyncServiceHealth.isServiceConnected())
    }.distinctUntilChanged()

    private fun changes(): Flow<Unit> = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val listener = BillSyncServiceHealth.registerListener { trySend(Unit) }
        try {
            resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer)
            trySend(Unit)
            awaitClose { }
        } finally {
            BillSyncServiceHealth.unregisterListener(listener)
            resolver.unregisterContentObserver(observer)
        }
    }.buffer(Channel.CONFLATED)
}
