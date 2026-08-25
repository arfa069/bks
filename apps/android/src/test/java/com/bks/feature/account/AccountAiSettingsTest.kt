package com.bks.feature.account

import com.bks.feature.categorization.AiCategorizationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAiSettingsTest {
    @Test
    fun usesSettingsLoadedForTheVerifiedAccount() {
        val settings = AiCategorizationSettings(
            aiConsentGranted = true,
            enhancedContextGranted = true
        )

        val effectiveSettings = effectiveAiCategorizationSettings(
            accountState = verifiedAccountState(token = "token-a"),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = settings,
                loadedToken = "token-a"
            )
        )

        assertEquals(settings, effectiveSettings)
        assertFalse(cloudWritesPaused(verifiedAccountState(token = "token-a")))
        assertTrue(
            settingsSyncInFlight(
                accountState = verifiedAccountState(token = "token-a"),
                cloudSettingsState = AccountCloudSettingsState(settingsSyncInFlight = true)
            )
        )
    }

    @Test
    fun rejectsSettingsLoadedForAnotherAccount() {
        val effectiveSettings = effectiveAiCategorizationSettings(
            accountState = verifiedAccountState(token = "token-new"),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-old"
            )
        )

        assertEquals(AiCategorizationSettings(), effectiveSettings)
    }

    @Test
    fun rejectsSettingsWhileAccountDeletionPausesCloudWrites() {
        val effectiveSettings = effectiveAiCategorizationSettings(
            accountState = verifiedAccountState(
                token = "token-a",
                deletionState = AccountDeletionUiState(
                    requestedAtEpochMillis = 1L,
                    finalDeletionAtEpochMillis = 2L
                )
            ),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-a"
            )
        )

        assertEquals(AiCategorizationSettings(), effectiveSettings)
        val deletingAccountState = verifiedAccountState(
            token = "token-a",
            deletionState = AccountDeletionUiState(
                requestedAtEpochMillis = 1L,
                finalDeletionAtEpochMillis = 2L
            )
        )
        assertTrue(cloudWritesPaused(deletingAccountState))
        assertFalse(
            settingsSyncInFlight(
                accountState = deletingAccountState,
                cloudSettingsState = AccountCloudSettingsState(settingsSyncInFlight = true)
            )
        )
    }

    @Test
    fun usesEmptySettingsWhenSignedOut() {
        val effectiveSettings = effectiveAiCategorizationSettings(
            accountState = AccountSessionCoordinatorState(isRestoring = false),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-a"
            )
        )

        assertEquals(AiCategorizationSettings(), effectiveSettings)
        val signedOutState = AccountSessionCoordinatorState(isRestoring = false)
        assertTrue(cloudWritesPaused(signedOutState))
        assertFalse(
            settingsSyncInFlight(
                accountState = signedOutState,
                cloudSettingsState = AccountCloudSettingsState(settingsSyncInFlight = true)
            )
        )
    }

    private fun verifiedAccountState(
        token: String,
        deletionState: AccountDeletionUiState = AccountDeletionUiState()
    ) = AccountSessionCoordinatorState(
        session = AccountSession.SignedIn(token = token),
        isRestoring = false,
        runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
        deletionState = deletionState
    )
}
