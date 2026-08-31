package com.bks.view.common.platform

import com.bks.model.platform.billsync.BillSyncPermission
import com.bks.model.platform.billsync.BillSyncRuntimeHealthSnapshot
import com.bks.model.platform.billsync.BillSyncServiceHealth
import com.bks.model.platform.billsync.recordBillSyncAppEvent
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.domain.diagnostics.NoOpDiagnosticRecorder
class BillSyncStateCoordinator internal constructor(
    private val activity: ComponentActivity,
    private val diagnosticRecorder: DiagnosticRecorder = NoOpDiagnosticRecorder,
    private val healthSnapshot: () -> BillSyncRuntimeHealthSnapshot = {
        BillSyncRuntimeHealthSnapshot(
            sessionId = null,
            serviceConnected = BillSyncServiceHealth.isServiceConnected(activity),
            automaticBookkeepingEnabled = false,
            lastEventAtEpochMillis = null,
            lastEventPackageName = null,
            eventSilenceMillis = null
        )
    },
    private val batteryOptimizationIgnored: () -> Boolean? = {
        BillSyncBackgroundSettings.isBatteryOptimizationIgnored(activity)
    },
    private val clock: () -> Long = System::currentTimeMillis
) {
    val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    val billSyncAccessibilityServiceConnected = mutableStateOf(false)
    val billSyncBatteryOptimizationIgnored = mutableStateOf<Boolean?>(null)

    private var serviceHealthListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun onCreate() {
        billSyncAccessibilityServiceConnected.value = BillSyncServiceHealth.isServiceConnected(activity)
        refreshBackgroundSettingsState()
        serviceHealthListener = BillSyncServiceHealth.registerListener(activity) { connected ->
            billSyncAccessibilityServiceConnected.value = connected
        }
    }

    fun onResume() {
        billSyncAccessibilityAccessGranted.value = BillSyncPermission.isGranted(activity)
        billSyncAccessibilityServiceConnected.value = BillSyncServiceHealth.isServiceConnected(activity)
        refreshBackgroundSettingsState()
        recordAppState("FOREGROUND")
    }

    fun onStop() {
        recordAppState("BACKGROUND")
    }

    fun onDestroy() {
        serviceHealthListener?.let { listener ->
            BillSyncServiceHealth.unregisterListener(activity, listener)
        }
        serviceHealthListener = null
    }

    fun openBillSyncAccessibilitySettings() {
        runCatching {
            activity.startActivity(BillSyncPermission.settingsIntent())
        }.getOrElse {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openBillSyncAutoStartSettings() {
        openFirstAvailable(
            BillSyncBackgroundSettings.autoStartSettingsIntents(activity.packageName)
        )
    }

    fun openBillSyncPowerPolicySettings() {
        openFirstAvailable(
            BillSyncBackgroundSettings.powerPolicySettingsIntents(activity.packageName)
        )
    }

    private fun refreshBackgroundSettingsState() {
        billSyncBatteryOptimizationIgnored.value = batteryOptimizationIgnored()
    }

    private fun openFirstAvailable(intents: List<Intent>) {
        intents.forEach { intent ->
            if (runCatching { activity.startActivity(intent) }.isSuccess) return
        }
    }

    private fun recordAppState(eventName: String) {
        val snapshot = healthSnapshot()
        diagnosticRecorder.recordBillSyncAppEvent(
            eventName = eventName,
            timestampEpochMillis = clock(),
            sessionId = snapshot.sessionId,
            serviceConnected = snapshot.serviceConnected,
            enabled = snapshot.automaticBookkeepingEnabled,
            lastEventAtEpochMillis = snapshot.lastEventAtEpochMillis,
            lastEventPackage = snapshot.lastEventPackageName,
            eventSilenceMillis = snapshot.eventSilenceMillis
        )
    }

}
