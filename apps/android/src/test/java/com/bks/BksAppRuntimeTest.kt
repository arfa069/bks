package com.bks

import com.bks.feature.account.AccountCloudSettingsState
import com.bks.feature.account.AccountDeletionUiState
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionCoordinatorState
import com.bks.feature.categorization.AiCategorizationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BksAppRuntimeTest {
    @Test
    fun presentationUsesMatchingVerifiedAccountAndCloudSettingsSnapshot() {
        val settings = AiCategorizationSettings(
            aiConsentGranted = true,
            enhancedContextGranted = true
        )

        val presentation = bksAppPresentation(
            accountState = verifiedAccountState(token = "token-a"),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = settings,
                loadedToken = "token-a",
                settingsSyncInFlight = true
            )
        )

        assertEquals("token-a", presentation.accountToken)
        assertTrue(presentation.signedIn)
        assertEquals(settings, presentation.effectiveAiSettings)
        assertFalse(presentation.cloudWritesPaused)
        assertTrue(presentation.settingsSyncInFlight)
    }

    @Test
    fun presentationRejectsCloudSettingsLoadedForAnotherAccount() {
        val presentation = bksAppPresentation(
            accountState = verifiedAccountState(token = "token-new"),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-old"
            )
        )

        assertEquals("token-new", presentation.accountToken)
        assertTrue(presentation.signedIn)
        assertEquals(AiCategorizationSettings(), presentation.effectiveAiSettings)
        assertFalse(presentation.cloudWritesPaused)
    }

    @Test
    fun presentationPausesCloudWritesDuringAccountDeletion() {
        val presentation = bksAppPresentation(
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

        assertTrue(presentation.signedIn)
        assertEquals(AiCategorizationSettings(), presentation.effectiveAiSettings)
        assertTrue(presentation.cloudWritesPaused)
    }

    @Test
    fun presentationUsesLocalSnapshotWhenSignedOut() {
        val presentation = bksAppPresentation(
            accountState = AccountSessionCoordinatorState(isRestoring = false),
            cloudSettingsState = AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-a",
                settingsSyncInFlight = true
            )
        )

        assertNull(presentation.accountToken)
        assertFalse(presentation.signedIn)
        assertEquals(AiCategorizationSettings(), presentation.effectiveAiSettings)
        assertTrue(presentation.cloudWritesPaused)
        assertFalse(presentation.settingsSyncInFlight)
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
