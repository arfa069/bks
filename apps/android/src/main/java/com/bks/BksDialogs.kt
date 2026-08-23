package com.bks

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bks.feature.account.AccountSession
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.ui.components.Button
import com.bks.ui.components.TextButton
import kotlinx.coroutines.launch

@Composable
internal fun BksLedgerSyncAccountSwitchDialog(context: BksRouteContext) {
    val runtime = context.runtime
    val syncState = context.ledgerSyncUiState
    if (!syncState.showAccountSwitch) return
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
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
                onClick = {
                    if (signedIn != null) {
                        context.coroutineScope.launch {
                            when (
                                val result = context.ledgerSyncViewModel.switchAccount()
                            ) {
                                is LedgerSyncOperationResult.Success -> {
                                    context.appState.snackbarHostState.showSnackbar("账户同步数据已切换")
                                }
                                is LedgerSyncOperationResult.Failure ->
                                    context.appState.snackbarHostState.showSnackbar(result.message)
                            }
                        }
                    }
                },
                enabled = signedIn != null &&
                    syncState.pendingCount == 0 &&
                    !syncState.accountSwitchBusy
            ) {
                Text(if (syncState.accountSwitchBusy) "切换中" else "确认切换")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (signedIn != null) {
                        context.coroutineScope.launch {
                            context.dependencies.account.accountRepository.signOut(signedIn.token)
                        }
                    }
                    context.dependencies.account.sessionCoordinator.moveToLocalMode()
                },
                enabled = !syncState.accountSwitchBusy
            ) {
                Text("取消并退出当前账户")
            }
        }
    )
}
