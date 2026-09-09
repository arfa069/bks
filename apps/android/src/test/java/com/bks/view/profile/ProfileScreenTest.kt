package com.bks.view.profile

import com.bks.model.data.local.diagnostics.DiagnosticLogs
import com.bks.model.domain.account.AccountRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import com.bks.viewmodel.account.ProfileAccountSummaryUiModel
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bks.model.domain.account.AccountDeletionState
import com.bks.view.account.TestAccountManagementScreen
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.FakeAccountRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overviewShowsAccountCardAndFeatureEntries() {
        composeRule.setContent {
            ProfileOverviewScreen(
                accountSummary = ProfileAccountSummaryUiModel("本地模式 · 账本仅保存在本机"),
                onDestinationSelected = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("profile-account-status-card").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-AccountManagement").assertDoesNotExist()
        composeRule.onNodeWithText("记账设置").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-Notifications").assertDoesNotExist()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("数据与安全").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-DiagnosticLogs").assertDoesNotExist()
        composeRule.onAllNodesWithText("进入").assertCountEquals(0)
    }

    @Test
    fun debugOverviewShowsDiagnosticLogsImmediatelyAfterComplianceAndOpensIt() {
        var destination: ProfileDestination? = null
        composeRule.setContent {
            ProfileOverviewScreen(
                accountSummary = ProfileAccountSummaryUiModel("本地模式 · 账本仅保存在本机"),
                onDestinationSelected = { destination = it },
                isDebugBuild = true
            )
        }

        assertEquals(
            ProfileDestination.ComplianceAndPrivacy.ordinal + 1,
            ProfileDestination.DiagnosticLogs.ordinal
        )
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-DiagnosticLogs")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(ProfileDestination.DiagnosticLogs, destination)
    }

    @Test
    fun accountStatusCardOpensAccountManagement() {
        var destination: ProfileDestination? = null
        composeRule.setContent {
            ProfileOverviewScreen(
                accountSummary = ProfileAccountSummaryUiModel("已登录 · 138****8000"),
                onDestinationSelected = { destination = it }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("profile-account-status-card").performClick()
        composeRule.waitForIdle()

        assertEquals(ProfileDestination.AccountManagement, destination)
    }

    @Test
    fun enabledLedgerSyncIsSummarizedOnlyOnDataAndBackupEntry() {
        composeRule.setContent {
            ProfileOverviewScreen(
                accountSummary = ProfileAccountSummaryUiModel("已登录 · 138****8000"),
                ledgerSyncEnabled = true,
                onDestinationSelected = {}
            )
        }

        composeRule.onNodeWithText("账户同步已启用", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("账户同步已启用", substring = true).assertCountEquals(1)
    }

    @Test
    fun overviewRemainsReachableAcrossWindowWidthsAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                ProfileOverviewScreen(
                    accountSummary = ProfileAccountSummaryUiModel("本地模式 · 账本仅保存在本机"),
                    onDestinationSelected = {}
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
            composeRule.onNodeWithTag("profile-account-status-card")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun signedInAccountManagementKeepsSignOutSeparateFromAccountDeletion() {
        var signedOut = false
        composeRule.setContent {
            TestAccountManagementScreen(
                session = AccountSession.SignedIn(phone = "13800138000"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionState(),
                accountRepository = FakeAccountRepository(),
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = { signedOut = true },
                onDeletionStateChange = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil { signedOut }
        assertTrue(signedOut)
        composeRule.onNodeWithText("申请注销账号").performScrollTo().assertIsDisplayed()
    }

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )
}
