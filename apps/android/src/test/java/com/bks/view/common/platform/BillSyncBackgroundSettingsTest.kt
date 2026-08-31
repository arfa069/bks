package com.bks.view.common.platform

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncBackgroundSettingsTest {
    @Test
    fun xiaomiAutoStartSettingsPreferRestrictedSystemTargetsAndKeepFallbacks() {
        val intents = BillSyncBackgroundSettings.autoStartSettingsIntents(
            packageName = PACKAGE_NAME,
            manufacturer = "Xiaomi"
        )

        assertEquals("miui.intent.action.OP_AUTO_START", intents[0].action)
        assertEquals("com.miui.securitycenter", intents[0].`package`)
        assertEquals(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            intents[1].component?.flattenToString()
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[2].action)
        assertEquals(PACKAGE_NAME, intents[2].data?.schemeSpecificPart)
        assertEquals(Settings.ACTION_SETTINGS, intents[3].action)
    }

    @Test
    fun nonXiaomiAutoStartSettingsUseOnlyPortableFallbacks() {
        val intents = BillSyncBackgroundSettings.autoStartSettingsIntents(
            packageName = PACKAGE_NAME,
            manufacturer = "Google"
        )

        assertEquals(2, intents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[0].action)
        assertEquals(PACKAGE_NAME, intents[0].data?.schemeSpecificPart)
        assertEquals(Settings.ACTION_SETTINGS, intents[1].action)
        assertNull(intents[0].component)
    }

    @Test
    fun powerPolicySettingsPreferAppBatteryDetailAndKeepPortableFallbacks() {
        val intents = BillSyncBackgroundSettings.powerPolicySettingsIntents(PACKAGE_NAME)

        assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", intents[0].action)
        assertEquals(PACKAGE_NAME, intents[0].data?.schemeSpecificPart)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents[1].action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[2].action)
        assertEquals(PACKAGE_NAME, intents[2].data?.schemeSpecificPart)
        assertEquals(Settings.ACTION_SETTINGS, intents[3].action)
    }

    private companion object {
        const val PACKAGE_NAME = "com.bks.debug"
    }
}
