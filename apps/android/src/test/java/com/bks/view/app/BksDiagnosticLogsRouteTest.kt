package com.bks.view.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BksDiagnosticLogsRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sensitiveWindowFlagCanBeAppliedAndCleared() {
        val activity = composeRule.activity

        setDiagnosticSecureFlag(activity, true)
        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )

        setDiagnosticSecureFlag(activity, false)
        assertEquals(
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    @Test
    fun secureWindowIsActiveForWholeCompositionAndRestoresPreviousState() {
        val activity = composeRule.activity
        val showDiagnostics = mutableStateOf(true)
        composeRule.setContent {
            if (showDiagnostics.value) SecureWindowWhile(enabled = true)
        }
        composeRule.waitForIdle()

        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )

        composeRule.runOnUiThread { showDiagnostics.value = false }
        composeRule.waitForIdle()

        assertEquals(
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        )

        setDiagnosticSecureFlag(activity, true)
        composeRule.runOnUiThread { showDiagnostics.value = true }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { showDiagnostics.value = false }
        composeRule.waitForIdle()

        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )
        setDiagnosticSecureFlag(activity, false)
    }
}
