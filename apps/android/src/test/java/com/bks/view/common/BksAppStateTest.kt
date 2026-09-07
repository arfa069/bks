package com.bks.view.common

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.bks.view.navigation.AppTab
import com.bks.view.profile.ProfileDestination
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BksAppStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationStateSurvivesRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val state = AtomicReference<BksAppState>()
        restorationTester.setContent {
            state.set(rememberBksAppState())
        }

        composeRule.runOnIdle {
            state.get().selectedTab.value = AppTab.Reports
            state.get().manualEntryOpen.value = true
            state.get().ledgerEntryToOpenId.value = "entry-id"
            state.get().profileDestination.value = ProfileDestination.AccountManagement
            state.get().openAccountEntry(
                mode = AccountEntryMode.SignInOrRegister,
                returnTarget = AccountEntryReturnTarget.AccountManagement
            )
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(AppTab.Reports, state.get().selectedTab.value)
            assertTrue(state.get().manualEntryOpen.value)
            assertNull(state.get().ledgerEntryToOpenId.value)
            assertEquals(ProfileDestination.AccountManagement, state.get().profileDestination.value)
            assertEquals(AccountEntryMode.SignInOrRegister, state.get().accountEntryMode.value)
            assertEquals(
                AccountEntryReturnTarget.AccountManagement,
                state.get().accountEntryReturnTarget.value
            )
        }
    }
}
