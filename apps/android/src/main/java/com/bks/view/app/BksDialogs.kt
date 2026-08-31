package com.bks.view.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.viewmodel.sync.LedgerSyncUiState
import com.bks.view.common.components.Button
import com.bks.view.common.components.TextButton
@Composable
internal fun BksLedgerSyncAccountSwitchDialog(
    signedIn: Boolean,
    syncState: LedgerSyncUiState,
    onSwitchAccount: () -> Unit,
    onCancelAndSignOut: () -> Unit
) {
    if (!syncState.showAccountSwitch) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("切换账户同步数据") },
        text = {
            Text(
                if (syncState.pendingCount > 0) {
                    "原账户仍有 ${syncState.pendingCount} 项待上传。为避免丢失，请先恢复原账户完成同步或导出加密备份。"
                } else {
                    "确认后，本机正式账本将切换为当前账户的云端数据。待确认记录和设备设置会保留，原账户数据仍保存在其云端。"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onSwitchAccount,
                enabled = signedIn &&
                    syncState.pendingCount == 0 &&
                    !syncState.accountSwitchBusy
            ) {
                Text(if (syncState.accountSwitchBusy) "切换中" else "确认切换")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelAndSignOut,
                enabled = !syncState.accountSwitchBusy
            ) {
                Text("取消并退出当前账户")
            }
        }
    )
}

internal suspend fun runLedgerSyncAccountSwitchAction(
    switchAccount: suspend () -> LedgerSyncOperationResult<Unit>,
    showMessage: suspend (String) -> Unit
) {
    when (val result = switchAccount()) {
        is LedgerSyncOperationResult.Success -> showMessage("账户同步数据已切换")
        is LedgerSyncOperationResult.Failure -> showMessage(result.message)
    }
}

internal fun cancelLedgerSyncAccountSwitch(
    signedInToken: String?,
    launchSignOut: (String) -> Unit,
    moveToLocalMode: () -> Unit
) {
    signedInToken?.let(launchSignOut)
    moveToLocalMode()
}
