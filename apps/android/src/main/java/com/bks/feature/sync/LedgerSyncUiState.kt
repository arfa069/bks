package com.bks.feature.sync

data class LedgerSyncConflictUiModel(
    val conflictId: String,
    val canonicalVersion: Long,
    val title: String,
    val cloudSummary: String,
    val localSummary: String,
    val resolving: Boolean = false
)

data class LedgerSyncUiState(
    val signedIn: Boolean = false,
    val enabled: Boolean = false,
    val profileKey: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val conflicts: List<LedgerSyncConflictUiModel> = emptyList(),
    val insecureHttpTestMode: Boolean = false,
    val preview: LedgerSyncPreview? = null,
    val previewBusy: Boolean = false,
    val syncBusy: Boolean = false,
    val showAccountSwitch: Boolean = false,
    val accountSwitchBusy: Boolean = false
)
