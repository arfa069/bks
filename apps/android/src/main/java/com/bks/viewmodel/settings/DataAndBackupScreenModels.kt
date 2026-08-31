package com.bks.viewmodel.settings

data class BackupLedgerEntryUiModel(
    val id: String,
    val transactionTimeText: String,
    val title: String,
    val amountMinor: Long,
    val flowType: String,
    val category: String,
    val sourceLabel: String,
    val kindLabel: String,
    val note: String?
)

data class LedgerSyncSettingsConflictUiModel(
    val conflictId: String,
    val canonicalVersion: Long,
    val title: String,
    val cloudSummary: String,
    val localSummary: String,
    val resolving: Boolean = false
)

data class LedgerSyncSettingsPreviewUiModel(
    val localRecordCount: Int,
    val cloudRecordCount: Int,
    val insecureHttpTestMode: Boolean
)

data class LedgerSyncSettingsUiState(
    val signedIn: Boolean = false,
    val enabled: Boolean = false,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val conflicts: List<LedgerSyncSettingsConflictUiModel> = emptyList(),
    val insecureHttpTestMode: Boolean = false,
    val preview: LedgerSyncSettingsPreviewUiModel? = null,
    val previewBusy: Boolean = false,
    val syncBusy: Boolean = false
)

enum class LedgerSyncSettingsInitialMode {
    MERGE,
    REPLACE_LOCAL
}

enum class LedgerSyncSettingsConflictChoice {
    CANONICAL,
    CANDIDATE
}

sealed interface LedgerSyncSettingsActionResult<out T> {
    data class Success<T>(val value: T) : LedgerSyncSettingsActionResult<T>
    data class Failure(val message: String) : LedgerSyncSettingsActionResult<Nothing>
}

class LedgerSyncSectionActions(
    val onPreview: suspend () -> LedgerSyncSettingsActionResult<LedgerSyncSettingsPreviewUiModel> = {
        LedgerSyncSettingsActionResult.Failure("请先登录账户")
    },
    val onDismissPreview: () -> Unit = {},
    val onEnable: suspend (LedgerSyncSettingsInitialMode) -> LedgerSyncSettingsActionResult<Unit> = {
        LedgerSyncSettingsActionResult.Failure("请先登录账户")
    },
    val onSyncNow: suspend () -> LedgerSyncSettingsActionResult<Unit> = {
        LedgerSyncSettingsActionResult.Failure("请先登录账户")
    },
    val onDisable: suspend () -> Unit = {},
    val onResolveConflict: suspend (
        String,
        Long,
        LedgerSyncSettingsConflictChoice
    ) -> LedgerSyncSettingsActionResult<Unit> = { _, _, _ ->
        LedgerSyncSettingsActionResult.Failure("请先登录账户")
    }
)
