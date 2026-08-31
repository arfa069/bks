package com.bks.view.monitoring

import com.bks.viewmodel.monitoring.AutomaticBookkeepingUiState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        var autoStartSettingsOpened = false
        var powerPolicySettingsOpened = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                state = AutomaticBookkeepingUiState(
                    batteryOptimizationIgnored = batteryOptimizationIgnored.value
                ),
                onOpenAutoStartSettings = { autoStartSettingsOpened = true },
                onOpenPowerPolicySettings = { powerPolicySettingsOpened = true }
            )
        }

        composeRule.onNodeWithText("状态：请在系统设置中确认")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("系统电池优化：状态不可用")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { batteryOptimizationIgnored.value = false }
        composeRule.onNodeWithText("系统电池优化：未豁免")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { batteryOptimizationIgnored.value = true }
        composeRule.onNodeWithText("系统电池优化：已豁免")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("automatic-bookkeeping-autostart-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("automatic-bookkeeping-power-settings")
            .performScrollTo()
            .performClick()
        assertTrue(autoStartSettingsOpened)
        assertTrue(powerPolicySettingsOpened)
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
        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").assertIsOn()
        composeRule.onNodeWithText("打开无障碍设置").performClick()

        assertTrue(settingsOpened)
        assertEquals(null, enabledChangeRequested)

        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").performClick()
        assertEquals(false, enabledChangeRequested)
    }

    @Test
    fun connectedStateShowsListeningAndPrivacyBoundary() {
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
        composeRule.onNodeWithText("不会截图、不会操作其他应用、不会保存或上传原始页面文字。")
            .performScrollTo()
            .assertIsDisplayed()
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
        composeRule.onNodeWithText("管理无障碍授权").assertIsDisplayed()

        composeRule.runOnIdle { enabled.value = true }
        composeRule.onNodeWithText("等待服务连接").assertIsDisplayed()
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
}
