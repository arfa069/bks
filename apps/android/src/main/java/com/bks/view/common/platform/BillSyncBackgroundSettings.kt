package com.bks.view.common.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal object BillSyncBackgroundSettings {
    fun isBatteryOptimizationIgnored(context: Context): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    fun autoStartSettingsIntents(
        packageName: String,
        manufacturer: String = Build.MANUFACTURER
    ): List<Intent> = buildList {
        if (manufacturer.equals(XIAOMI_MANUFACTURER, ignoreCase = true)) {
            add(
                Intent(MIUI_AUTO_START_ACTION)
                    .setPackage(MIUI_SECURITY_CENTER_PACKAGE)
            )
            add(
                Intent().setComponent(
                    ComponentName(
                        MIUI_SECURITY_CENTER_PACKAGE,
                        MIUI_AUTO_START_ACTIVITY
                    )
                )
            )
        }
        add(applicationDetailsIntent(packageName))
        add(Intent(Settings.ACTION_SETTINGS))
    }

    fun powerPolicySettingsIntents(packageName: String): List<Intent> = listOf(
        Intent(
            ADVANCED_POWER_USAGE_DETAIL_ACTION,
            packageUri(packageName)
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        applicationDetailsIntent(packageName),
        Intent(Settings.ACTION_SETTINGS)
    )

    private fun applicationDetailsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        packageUri(packageName)
    )

    private fun packageUri(packageName: String): Uri = Uri.fromParts("package", packageName, null)

    private const val XIAOMI_MANUFACTURER = "Xiaomi"
    private const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
    private const val MIUI_AUTO_START_ACTION = "miui.intent.action.OP_AUTO_START"
    private const val ADVANCED_POWER_USAGE_DETAIL_ACTION =
        "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"
    private const val MIUI_AUTO_START_ACTIVITY =
        "com.miui.permcenter.autostart.AutoStartManagementActivity"
}
