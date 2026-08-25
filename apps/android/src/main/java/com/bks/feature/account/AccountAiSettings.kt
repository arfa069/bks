package com.bks.feature.account

import com.bks.feature.categorization.AiCategorizationSettings

internal fun effectiveAiCategorizationSettings(
    accountState: AccountSessionCoordinatorState,
    cloudSettingsState: AccountCloudSettingsState
): AiCategorizationSettings {
    val signedIn = accountState.session as? AccountSession.SignedIn
    val cloudWritesAllowed = accountState.runtimeState.cloudWritesAllowed &&
        accountState.deletionState.cloudWritesAllowed
    return signedIn
        ?.takeIf { session ->
            session.token == cloudSettingsState.loadedToken && cloudWritesAllowed
        }
        ?.let { cloudSettingsState.aiSettings }
        ?: AiCategorizationSettings()
}
