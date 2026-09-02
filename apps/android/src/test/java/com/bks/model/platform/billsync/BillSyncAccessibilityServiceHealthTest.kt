package com.bks.model.platform.billsync

import com.bks.model.platform.billsync.BillSyncAccessibilityService
import com.bks.model.platform.billsync.BillSyncServiceHealth
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncAccessibilityServiceHealthTest {
    @Test
    fun serviceConnectionHealthTracksLifecycle() {
        BillSyncServiceHealth.markServiceConnected(false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()
        val service = controller.get()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(service)
        service.onInterrupt()

        assertTrue(BillSyncServiceHealth.isServiceConnected())
        assertEquals(
            BillSyncAccessibilityService::class.java,
            BillSyncAccessibilityService::class.java
                .getMethod("onAccessibilityEvent", AccessibilityEvent::class.java)
                .declaringClass
        )

        service.onUnbind(Intent())
        assertFalse(BillSyncServiceHealth.isServiceConnected())

        controller.destroy()
        assertFalse(BillSyncServiceHealth.isServiceConnected())
    }

    @Test
    fun serviceDestroyEndsConnectionWithoutExplicitUnbind() {
        BillSyncServiceHealth.markServiceConnected(false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(controller.get())
        assertTrue(BillSyncServiceHealth.isServiceConnected())

        controller.destroy()

        assertFalse(BillSyncServiceHealth.isServiceConnected())
    }

    @Test
    fun directCoordinatorCallbackSurvivesThrowingAssistsListener() {
        BillSyncServiceHealth.markServiceConnected(false)
        val throwingListener = object : AssistsServiceListener {
            override fun onServiceConnected(service: AssistsService) {
                error("expected")
            }
        }
        AssistsService.listeners.add(0, throwingListener)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()

        try {
            BillSyncAccessibilityService::class.java
                .getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }
                .invoke(controller.get())

            assertTrue(BillSyncServiceHealth.isServiceConnected())
        } finally {
            AssistsService.listeners.remove(throwingListener)
            controller.destroy()
        }
    }
}
