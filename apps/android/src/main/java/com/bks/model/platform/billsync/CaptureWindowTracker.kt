package com.bks.model.platform.billsync

@Suppress("TooManyFunctions")
internal class CaptureWindowTracker {
    private val accepted = mutableMapOf<Pair<String, Int>, AcceptedWindow>()
    private val deferredCaptures = mutableMapOf<Pair<String, Int>, BillSyncCaptureBatch>()

    @Synchronized
    fun acceptIfNew(packageName: String, windowId: Int): Boolean {
        val key = packageName to windowId
        val existing = accepted[key]
        if (existing != null) {
            return false
        }
        accepted[key] = AcceptedWindow(persistenceInFlight = true)
        return true
    }

    @Synchronized
    fun canCompleteMerchant(packageName: String, windowId: Int): Boolean =
        accepted[packageName to windowId]?.let { it.persistenceInFlight && !it.releaseRequested } == true

    @Synchronized
    fun deferIfPersistenceInFlight(batch: BillSyncCaptureBatch): Boolean {
        val trigger = batch.trigger
        val packageName = trigger.packageName ?: return false
        if (trigger.windowId < 0) return false
        val key = packageName to trigger.windowId
        if (accepted[key]?.persistenceInFlight != true) return false
        deferredCaptures[key] = deferredCaptures[key]?.merge(batch) ?: batch
        return true
    }

    @Synchronized
    fun takeDeferredCapture(packageName: String, windowId: Int): BillSyncCaptureBatch? =
        deferredCaptures.remove(packageName to windowId)

    @Synchronized
    fun release(packageName: String, windowId: Int) {
        val key = packageName to windowId
        val existing = accepted[key] ?: return
        if (existing.persistenceInFlight) {
            existing.releaseRequested = true
        } else {
            accepted.remove(key)
        }
    }

    @Synchronized
    fun releaseForNewPayment(packageName: String) {
        accepted.keys.filter { it.first == packageName }.forEach { (_, windowId) ->
            release(packageName, windowId)
        }
    }

    @Synchronized
    fun persistenceSucceeded(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = false)
    }

    @Synchronized
    fun persistenceFailed(packageName: String, windowId: Int) {
        completePersistence(packageName, windowId, remove = true)
    }

    @Synchronized
    fun persistenceCancelled(packageName: String, windowId: Int) {
        deferredCaptures.remove(packageName to windowId)
        completePersistence(packageName, windowId, remove = false)
    }

    @Synchronized
    fun clear() {
        accepted.clear()
        deferredCaptures.clear()
    }

    private fun completePersistence(packageName: String, windowId: Int, remove: Boolean) {
        val key = packageName to windowId
        val existing = accepted[key] ?: return
        existing.persistenceInFlight = false
        if (remove || existing.releaseRequested) accepted.remove(key)
    }

    private data class AcceptedWindow(
        var persistenceInFlight: Boolean,
        var releaseRequested: Boolean = false
    )
}
