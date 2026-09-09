package com.bks.viewmodel.monitoring

data class AutomaticBookkeepingUiState(
    val enabled: Boolean = false,
    val writeInProgress: Boolean = false,
    val accessibilityAccessGranted: Boolean = false,
    val accessibilityServiceConnected: Boolean = false,
    val batteryOptimizationIgnored: Boolean? = null,
    val systemNotificationsAllowed: Boolean = false
)
