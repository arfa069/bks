package com.bks.feature.billsync

import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.bks.bksAppContainer
import com.ven.assists.service.AssistsService

class BillSyncAccessibilityService : AssistsService() {
    private val captureCoordinator by lazy { bksAppContainer.billSyncCaptureCoordinator }

    override fun onServiceConnected() {
        super.onServiceConnected()
        captureCoordinator.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
        captureCoordinator.onAccessibilityEvent(event)
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
