package com.bks.view.monitoring

import com.bks.viewmodel.monitoring.AutomaticBookkeepingUiState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBookkeepingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backgroundSettingsShowHonestStatusesRefreshAndDispatchActions() {
        val batteryOptimizationIgnored = mutableStateOf<Boolean?>(null)
        var accessibilitySettingsOpened = false
        var autoStartSettingsOpened = false
        var powerPolicySettingsOpened = false
        var notificationSettingsOpened = false
        val notificationsAllowed = mutableStateOf(false)
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    accessibilityAccessGranted = true,
                    batteryOptimizationIgnored = batteryOptimizationIgnored.value,
                    systemNotificationsAllowed = notificationsAllowed.value
                ),
                onOpenAccessibilitySettings = { accessibilitySettingsOpened = true },
                onOpenAutoStartSettings = { autoStartSettingsOpened = true },
                onOpenPowerPolicySettings = { powerPolicySettingsOpened = true },
                onOpenNotificationSettings = { notificationSettingsOpened = true }
            )
        }

        composeRule.onNodeWithTag("automatic-bookkeeping-access-status")
            .assertTextEquals("已授权")
        composeRule.onNodeWithText("请确认")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("automatic-bookkeeping-power-status")
            .assertTextEquals("不可用")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { batteryOptimizationIgnored.value = false }
        composeRule.onNodeWithTag("automatic-bookkeeping-power-status")
            .assertTextEquals("未豁免")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { batteryOptimizationIgnored.value = true }
        composeRule.onNodeWithTag("automatic-bookkeeping-power-status")
            .assertTextEquals("已豁免")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("automatic-bookkeeping-accessibility-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("automatic-bookkeeping-autostart-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("automatic-bookkeeping-power-settings")
            .performScrollTo()
            .performClick()
        assertTrue(accessibilitySettingsOpened)
        assertTrue(autoStartSettingsOpened)
        assertTrue(powerPolicySettingsOpened)
        composeRule.onNodeWithTag("automatic-bookkeeping-notifications-status").assertTextEquals("未允许")
        composeRule.runOnIdle { notificationsAllowed.value = true }
        composeRule.onNodeWithTag("automatic-bookkeeping-notifications-status").assertTextEquals("已允许")
        composeRule.onNodeWithTag("automatic-bookkeeping-notifications-settings").performScrollTo().performClick()
        assertTrue(notificationSettingsOpened)
    }

    @Test
    fun enabledWithoutPermissionKeepsIntentAndOffersSettings() {
        var enabledChangeRequested: Boolean? = null
        var settingsOpened = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    enabled = true,
                    accessibilityAccessGranted = false,
                    accessibilityServiceConnected = false
                ),
                onEnabledChange = { enabledChangeRequested = it },
                onOpenAccessibilitySettings = { settingsOpened = true }
            )
        }

        composeRule.onNodeWithText("等待无障碍授权").assertIsDisplayed()
        composeRule.onNodeWithTag("automatic-bookkeeping-access-status")
            .performScrollTo()
            .assertTextEquals("未授权")
        composeRule.onNodeWithTag("automatic-bookkeeping-service-status").assertDoesNotExist()
        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").assertIsOn()
        composeRule.onNodeWithTag("automatic-bookkeeping-accessibility-settings")
            .performScrollTo()
            .performClick()

        assertTrue(settingsOpened)
        assertEquals(null, enabledChangeRequested)

        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").performClick()
        assertEquals(false, enabledChangeRequested)
    }

    @Test
    fun connectedStateShowsListeningWithoutLegacyServiceOrPrivacySections() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    enabled = true,
                    accessibilityAccessGranted = true,
                    accessibilityServiceConnected = true
                )
            )
        }

        composeRule.onNodeWithText("正在监听").assertIsDisplayed()
        composeRule.onNodeWithTag("automatic-bookkeeping-access-status")
            .performScrollTo()
            .assertTextEquals("已授权")
        composeRule.onNodeWithTag("automatic-bookkeeping-service-status").assertDoesNotExist()
        composeRule.onNodeWithText("隐私说明").assertDoesNotExist()
    }

    @Test
    fun disabledAndDisconnectedStatesRemainDistinct() {
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    enabled = enabled.value,
                    accessibilityAccessGranted = true,
                    accessibilityServiceConnected = false
                )
            )
        }
        composeRule.onNodeWithText("已关闭").assertIsDisplayed()
        composeRule.onNodeWithTag("automatic-bookkeeping-accessibility-settings")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { enabled.value = true }
        composeRule.onNodeWithText("监听服务未运行").assertIsDisplayed()
        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").assertIsOn()
    }

    @Test
    fun writeInProgressDisablesFurtherToggleInput() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    enabled = true,
                    writeInProgress = true
                )
            )
        }

        composeRule.onNodeWithTag("automatic-bookkeeping-toggle")
            .assertIsOn()
            .assertIsNotEnabled()
    }

    @Test
    fun contentRemainsReachableAcrossWindowSizesAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                AutomaticBookkeepingScreen(
                    state = AutomaticBookkeepingUiState(
                        enabled = true,
                        accessibilityAccessGranted = true,
                        accessibilityServiceConnected = true,
                        batteryOptimizationIgnored = false
                    )
                )
            }
        }
        composeRule.waitForIdle()

        listOf(
            TestConfiguration(DpSize(400.dp, 500.dp)),
            TestConfiguration(DpSize(400.dp, 800.dp)),
            TestConfiguration(DpSize(610.dp, 500.dp)),
            TestConfiguration(DpSize(900.dp, 1_000.dp)),
            TestConfiguration(DpSize(400.dp, 500.dp), fontScale = 1.5f)
        ).forEach { configuration ->
            composeRule.runOnIdle {
                forcedSize = configuration.size
                fontScale = configuration.fontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("automatic-bookkeeping-toggle")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("automatic-bookkeeping-accessibility-settings")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("automatic-bookkeeping-autostart-settings")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("automatic-bookkeeping-power-settings")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("隐私说明").assertDoesNotExist()
            composeRule.onNodeWithTag("automatic-bookkeeping-notifications-settings")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )
}
