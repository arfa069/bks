package com.bks.viewmodel.categorization

import com.bks.model.domain.categorization.AiCategorizationSettings

data class CategorizationAiUiState(
    val settings: AiCategorizationSettings = AiCategorizationSettings(),
    val signedIn: Boolean = false,
    val cloudWritesPaused: Boolean = false,
    val settingsSyncInFlight: Boolean = false
)

data class CategorizationUiState(
    val rules: List<com.bks.model.domain.categorization.CategorizationRule> = emptyList(),
    val ai: CategorizationAiUiState = CategorizationAiUiState()
)
