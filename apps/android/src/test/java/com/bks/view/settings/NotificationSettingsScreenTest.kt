package com.bks.view.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.bks.model.domain.notifications.NotificationPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun deniedPermissionKeepsSettingsAvailableAndRequestsOnlyOnClick() {
        var permissionRequests = 0
        var enabled: Boolean? = null
        var hide: Boolean? = null
        compose.setContent {
            NotificationSettingsScreen(
                state = NotificationPreferences(), systemAllowed = false,
                onEnabledChange = { enabled = it }, onHideDetailsChange = { hide = it },
                onRequestPermission = { permissionRequests++ }, onOpenSystemSettings = {}, onBack = {}
            )
        }
        compose.waitForIdle()
        assertEquals(0, permissionRequests)
        compose.onNodeWithText("系统通知未允许，仍会正常识别并保存到待确认").assertIsDisplayed()
        compose.onNodeWithText("开启系统通知").performScrollTo().performClick()
        assertEquals(1, permissionRequests)
        compose.onNodeWithTag("notifications-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("notifications-hide-details").performScrollTo().performClick()
        assertEquals(false, enabled)
        assertEquals(false, hide)
    }
}
