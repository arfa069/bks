package com.bks.viewmodel.sync

import com.bks.model.domain.sync.LedgerSyncConflictChoice

data class LedgerSyncConflictUiModel(
    val conflictId: String,
    val canonicalVersion: Long,
    val title: String,
    val cloudSummary: String,
    val localSummary: String,
    val resolving: Boolean = false
)

data class LedgerSyncPreviewUiModel(
    val localRecordCount: Int,
    val cloudRecordCount: Int,
    val insecureHttpTestMode: Boolean
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
    val preview: LedgerSyncPreviewUiModel? = null,
    val previewBusy: Boolean = false,
    val syncBusy: Boolean = false,
    val showAccountSwitch: Boolean = false,
    val accountSwitchBusy: Boolean = false
)

enum class LedgerSyncEnableMode {
    MERGE,
    REPLACE_LOCAL
}

sealed interface LedgerSyncActionResult<out T> {
    data class Success<T>(val value: T) : LedgerSyncActionResult<T>
    data class Failure(val message: String) : LedgerSyncActionResult<Nothing>
}

class LedgerSyncSectionActions(
    val onPreview: suspend () -> LedgerSyncActionResult<LedgerSyncPreviewUiModel> = {
        LedgerSyncActionResult.Failure("请先登录账户")
    },
    val onDismissPreview: () -> Unit = {},
    val onEnable: suspend (LedgerSyncEnableMode) -> LedgerSyncActionResult<Unit> = {
        LedgerSyncActionResult.Failure("请先登录账户")
    },
    val onSyncNow: suspend () -> LedgerSyncActionResult<Unit> = {
        LedgerSyncActionResult.Failure("请先登录账户")
    },
    val onDisable: suspend () -> Unit = {},
    val onResolveConflict: suspend (
        String,
        Long,
        LedgerSyncConflictChoice
    ) -> LedgerSyncActionResult<Unit> = { _, _, _ ->
        LedgerSyncActionResult.Failure("请先登录账户")
    }
)
