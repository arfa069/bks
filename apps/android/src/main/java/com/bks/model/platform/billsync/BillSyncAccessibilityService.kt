package com.bks.model.platform.billsync

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.bks.model.platform.app.bksAppContainer
import com.ven.assists.service.AssistsService

class BillSyncAccessibilityService : AssistsService() {
    private val captureCoordinator by lazy { bksAppContainer.billSyncCaptureCoordinator }

    override fun onServiceConnected() {
        super.onServiceConnected()
        captureCoordinator.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val callbackEnterAtEpochMillis = System.currentTimeMillis()
        val callbackEnterUptimeMillis = SystemClock.uptimeMillis()
        super.onAccessibilityEvent(event)
        val superReturnUptimeMillis = SystemClock.uptimeMillis()
        captureCoordinator.onAccessibilityEvent(
            event = event,
            callbackTiming = BillSyncAccessibilityCallbackTiming(
                callbackEnterAtEpochMillis = callbackEnterAtEpochMillis,
                callbackEnterUptimeMillis = callbackEnterUptimeMillis,
                superReturnUptimeMillis = superReturnUptimeMillis
            )
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        captureCoordinator.onUnbind()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        captureCoordinator.onServiceDestroyed()
        super.onDestroy()
    }
}
