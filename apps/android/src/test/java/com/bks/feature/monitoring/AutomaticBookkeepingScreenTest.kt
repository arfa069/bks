package com.bks.feature.monitoring

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
