package com.bks.view.app

import com.bks.model.domain.account.AccountSession
import com.bks.view.profile.ProfileAccountSummaryUiModel
import com.bks.viewmodel.settings.LedgerSyncSettingsActionResult
import com.bks.viewmodel.settings.LedgerSyncSettingsConflictUiModel
import com.bks.viewmodel.settings.LedgerSyncSettingsPreviewUiModel
import com.bks.viewmodel.settings.LedgerSyncSettingsUiState
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.application.sync.LedgerSyncPreview
import com.bks.viewmodel.sync.LedgerSyncUiState
internal fun AccountSession.toProfileAccountSummaryUiModel(): ProfileAccountSummaryUiModel =
    ProfileAccountSummaryUiModel(
        text = when (this) {
            AccountSession.LocalMode -> "本地模式 · 账本仅保存在本机"
            is AccountSession.SignedIn -> phone
                ?.let { "已登录 · ${it.maskProfilePhone()}" }
                ?: "已登录 · ${nickname ?: "微信账号"}"
        }
    )

internal fun LedgerSyncUiState.toSettingsUiState(): LedgerSyncSettingsUiState =
    LedgerSyncSettingsUiState(
        signedIn = signedIn,
        enabled = enabled,
        lastSuccessAtMillis = lastSuccessAtMillis,
        lastError = lastError,
        pendingCount = pendingCount,
        conflicts = conflicts.map { conflict ->
            LedgerSyncSettingsConflictUiModel(
                conflictId = conflict.conflictId,
                canonicalVersion = conflict.canonicalVersion,
                title = conflict.title,
                cloudSummary = conflict.cloudSummary,
                localSummary = conflict.localSummary,
                resolving = conflict.resolving
            )
        },
        insecureHttpTestMode = insecureHttpTestMode,
        preview = preview?.toSettingsPreview(),
        previewBusy = previewBusy,
        syncBusy = syncBusy
    )

internal fun <T, R> LedgerSyncOperationResult<T>.toSettingsActionResult(
    transform: (T) -> R
): LedgerSyncSettingsActionResult<R> = when (this) {
    is LedgerSyncOperationResult.Success ->
        LedgerSyncSettingsActionResult.Success(transform(value))
    is LedgerSyncOperationResult.Failure ->
        LedgerSyncSettingsActionResult.Failure(message)
}

internal fun LedgerSyncPreview.toSettingsPreview(): LedgerSyncSettingsPreviewUiModel =
    LedgerSyncSettingsPreviewUiModel(
        localRecordCount = localRecordCount,
        cloudRecordCount = cloudRecordCount,
        insecureHttpTestMode = insecureHttpTestMode
    )

private fun String.maskProfilePhone(): String =
    if (length == 11) replaceRange(3, 7, "****") else "已登录"
