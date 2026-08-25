package com.bks.feature.account

import com.bks.feature.categorization.AiCategorizationSettings

internal fun effectiveAiCategorizationSettings(
    accountState: AccountSessionCoordinatorState,
    cloudSettingsState: AccountCloudSettingsState
): AiCategorizationSettings {
    val signedIn = accountState.session as? AccountSession.SignedIn
    return signedIn
        ?.takeIf { session ->
            session.token == cloudSettingsState.loadedToken && !cloudWritesPaused(accountState)
        }
        ?.let { cloudSettingsState.aiSettings }
        ?: AiCategorizationSettings()
}

internal fun cloudWritesPaused(accountState: AccountSessionCoordinatorState): Boolean =
    !(accountState.runtimeState.cloudWritesAllowed && accountState.deletionState.cloudWritesAllowed)

internal fun settingsSyncInFlight(
    accountState: AccountSessionCoordinatorState,
    cloudSettingsState: AccountCloudSettingsState
): Boolean = accountState.session is AccountSession.SignedIn &&
    !cloudWritesPaused(accountState) &&
    cloudSettingsState.settingsSyncInFlight
