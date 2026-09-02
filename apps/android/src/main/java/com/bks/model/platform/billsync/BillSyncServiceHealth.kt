package com.bks.model.platform.billsync

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object BillSyncServiceHealth {
    private val serviceConnected = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun isServiceConnected(): Boolean = serviceConnected.get()

    fun markServiceConnected(connected: Boolean) {
        if (serviceConnected.getAndSet(connected) == connected) return
        listeners.forEach { listener -> listener(connected) }
    }

    fun registerListener(onChanged: (Boolean) -> Unit): (Boolean) -> Unit {
        listeners += onChanged
        return onChanged
    }

    fun unregisterListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
