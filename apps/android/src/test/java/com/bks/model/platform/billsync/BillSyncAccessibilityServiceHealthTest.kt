package com.bks.model.platform.billsync

import com.bks.model.platform.billsync.BillSyncAccessibilityService
import com.bks.model.platform.billsync.BillSyncServiceHealth
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
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
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceConnectionHealthTracksLifecycle() {
        BillSyncServiceHealth.markServiceConnected(context, false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()
        val service = controller.get()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(service)
        service.onInterrupt()

        assertTrue(BillSyncServiceHealth.isServiceConnected(context))
        assertEquals(
            BillSyncAccessibilityService::class.java,
            BillSyncAccessibilityService::class.java
                .getMethod("onAccessibilityEvent", AccessibilityEvent::class.java)
                .declaringClass
        )

        service.onUnbind(Intent())
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))

        controller.destroy()
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))
    }

    @Test
    fun serviceDestroyEndsConnectionWithoutExplicitUnbind() {
        BillSyncServiceHealth.markServiceConnected(context, false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(controller.get())
        assertTrue(BillSyncServiceHealth.isServiceConnected(context))

        controller.destroy()

        assertFalse(BillSyncServiceHealth.isServiceConnected(context))
    }

    @Test
    fun directCoordinatorCallbackSurvivesThrowingAssistsListener() {
        BillSyncServiceHealth.markServiceConnected(context, false)
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

            assertTrue(BillSyncServiceHealth.isServiceConnected(context))
        } finally {
            AssistsService.listeners.remove(throwingListener)
            controller.destroy()
        }
    }
}
