package com.bks.view.common.platform

import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.platform.billsync.ALIPAY_PACKAGE_NAME
import com.bks.view.common.platform.BillSyncBackgroundSettings
import com.bks.model.platform.billsync.BillSyncRuntimeHealthSnapshot
import com.bks.model.platform.billsync.BillSyncServiceHealth
import com.bks.view.common.platform.BillSyncStateCoordinator
import androidx.activity.ComponentActivity
import com.bks.model.domain.diagnostics.BILL_SYNC_APP_EVENT
import com.bks.model.domain.diagnostics.InMemoryDiagnosticRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncStateCoordinatorTest {
    @Test
    fun backgroundSettingActionsLaunchTheirFirstSupportedTargets() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = activityController.get()
        val coordinator = BillSyncStateCoordinator(activity)

        try {
            coordinator.openBillSyncAutoStartSettings()
            val autoStartIntent = shadowOf(activity).nextStartedActivity
            val expectedAutoStart = BillSyncBackgroundSettings
                .autoStartSettingsIntents(activity.packageName)
                .first()
            assertEquals(expectedAutoStart.action, autoStartIntent.action)
            assertEquals(expectedAutoStart.`package`, autoStartIntent.`package`)
            assertEquals(expectedAutoStart.component, autoStartIntent.component)

            coordinator.openBillSyncPowerPolicySettings()
            val powerPolicyIntent = shadowOf(activity).nextStartedActivity
            val expectedPowerPolicy = BillSyncBackgroundSettings
                .powerPolicySettingsIntents(activity.packageName)
                .first()
            assertEquals(expectedPowerPolicy.action, powerPolicyIntent.action)
            assertEquals(expectedPowerPolicy.data, powerPolicyIntent.data)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun batteryOptimizationStatusRefreshesOnCreateAndResume() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = activityController.get()
        var ignored: Boolean? = false
        val coordinator = BillSyncStateCoordinator(
            activity = activity,
            batteryOptimizationIgnored = { ignored }
        )

        try {
            coordinator.onCreate()
            assertEquals(false, coordinator.billSyncBatteryOptimizationIgnored.value)

            ignored = true
            coordinator.onResume()
            assertEquals(true, coordinator.billSyncBatteryOptimizationIgnored.value)

            ignored = null
            coordinator.onResume()
            assertEquals(null, coordinator.billSyncBatteryOptimizationIgnored.value)
        } finally {
            coordinator.onDestroy()
            activityController.destroy()
        }
    }

    @Test
    fun foregroundAndBackgroundRecordCurrentServiceAndEventHealth() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = activityController.get()
        val recorder = InMemoryDiagnosticRecorder()
        BillSyncServiceHealth.markServiceConnected(true)
        val coordinator = BillSyncStateCoordinator(
            activity = activity,
            diagnosticRecorder = recorder,
            healthSnapshot = {
                BillSyncRuntimeHealthSnapshot(
                    sessionId = 4,
                    serviceConnected = true,
                    automaticBookkeepingEnabled = true,
                    lastEventAtEpochMillis = 1_787_704_342_000L,
                    lastEventPackageName = ALIPAY_PACKAGE_NAME,
                    eventSilenceMillis = 1_000L
                )
            },
            clock = { 1_787_704_343_000L }
        )

        try {
            coordinator.onCreate()
            coordinator.onResume()
            coordinator.onStop()

            val appEvents = recorder.events.filter { it.metadata.event == BILL_SYNC_APP_EVENT }
            assertEquals(listOf("FOREGROUND", "BACKGROUND"), appEvents.map {
                it.metadata.attributes["eventName"]
            })
            assertTrue(appEvents.all { it.metadata.sessionId == "4" })
            assertTrue(appEvents.all { it.metadata.attributes["service"] == "connected" })
            assertTrue(appEvents.all { it.metadata.attributes["enabled"] == "true" })
            assertTrue(appEvents.all {
                it.metadata.attributes["lastEventPkg"] == ALIPAY_PACKAGE_NAME
            })
            assertTrue(appEvents.all { it.metadata.attributes["eventSilenceMs"] == "1000" })
        } finally {
            coordinator.onDestroy()
            BillSyncServiceHealth.markServiceConnected(false)
            activityController.destroy()
        }
    }

    @Test
    fun serviceRunningStateRequiresCurrentAccessibilityGrant() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = activityController.get()
        var accessGranted = false
        BillSyncServiceHealth.markServiceConnected(true)
        val coordinator = BillSyncStateCoordinator(
            activity = activity,
            accessibilityAccessGranted = { accessGranted }
        )

        try {
            coordinator.onCreate()
            assertFalse(coordinator.billSyncAccessibilityAccessGranted.value)
            assertFalse(coordinator.billSyncAccessibilityServiceConnected.value)

            accessGranted = true
            coordinator.onResume()
            assertTrue(coordinator.billSyncAccessibilityAccessGranted.value)
            assertTrue(coordinator.billSyncAccessibilityServiceConnected.value)

            BillSyncServiceHealth.markServiceConnected(false)
            assertFalse(coordinator.billSyncAccessibilityServiceConnected.value)
            BillSyncServiceHealth.markServiceConnected(true)
            assertTrue(coordinator.billSyncAccessibilityServiceConnected.value)

            accessGranted = false
            coordinator.onResume()
            assertFalse(coordinator.billSyncAccessibilityAccessGranted.value)
            assertFalse(coordinator.billSyncAccessibilityServiceConnected.value)
        } finally {
            coordinator.onDestroy()
            BillSyncServiceHealth.markServiceConnected(false)
            activityController.destroy()
        }
    }
}
