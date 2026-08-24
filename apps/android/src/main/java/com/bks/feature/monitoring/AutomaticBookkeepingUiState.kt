package com.bks.feature.monitoring

data class AutomaticBookkeepingUiState(
    val enabled: Boolean = false,
    val writeInProgress: Boolean = false,
    val accessibilityAccessGranted: Boolean = false,
    val accessibilityServiceConnected: Boolean = false
)
