package com.bks.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.feature.sync.LedgerSyncConflictUiModel
import com.bks.feature.sync.LedgerSyncInitialMode
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncPreview
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun formatSyncTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

@Composable
internal fun LedgerSyncSettingsSection(
    ledgerSyncState: LedgerSyncUiState,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState
) {
    val coroutineScope = rememberCoroutineScope()

    CardSection(title = "账户同步") {
        when {
            !ledgerSyncState.signedIn -> Text("登录账户后可在多台设备间同步正式账本数据。")
            !ledgerSyncState.enabled -> EnableSyncContent(
                coroutineScope = coroutineScope,
                actions = actions,
                snackbarHostState = snackbarHostState,
                previewBusy = ledgerSyncState.previewBusy
            )
            else -> EnabledSyncContent(
                ledgerSyncState = ledgerSyncState,
                coroutineScope = coroutineScope,
                actions = actions,
                snackbarHostState = snackbarHostState
            )
        }
    }

    if (ledgerSyncState.preview != null) {
        FirstSyncConfirmationDialog(
            ledgerSyncState = ledgerSyncState,
            coroutineScope = coroutineScope,
            actions = actions,
            snackbarHostState = snackbarHostState,
            onDismiss = actions.onDismissPreview
        )
    }
}

@Composable
private fun EnableSyncContent(
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState,
    previewBusy: Boolean
) {
    Text("同步账本、正式及最近删除账目、分类、资金账户和分类规则。默认资金账户仅按账号同步，不进入账本同步。")
    Button(
        onClick = {
            coroutineScope.launch {
                when (val result = actions.onPreview()) {
                    is LedgerSyncOperationResult.Success -> Unit
                    is LedgerSyncOperationResult.Failure -> snackbarHostState.showSnackbar(result.message)
                }
            }
        },
        enabled = !previewBusy,
        modifier = Modifier.testTag("ledger-sync-enable")
    ) { Text(if (previewBusy) "正在检查" else "启用账户同步") }
}

@Composable
private fun EnabledSyncContent(
    ledgerSyncState: LedgerSyncUiState,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState
) {
    Text(
        ledgerSyncState.lastSuccessAtMillis?.let {
            "最近同步：${formatSyncTime(it)}"
        } ?: "尚未完成首次同步"
    )
    Text("待上传 ${ledgerSyncState.pendingCount} 项 · 冲突 ${ledgerSyncState.conflicts.size} 项")
    ledgerSyncState.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (ledgerSyncState.insecureHttpTestMode) {
        Text(
            "当前为局域网 HTTP 测试同步，账本内容未经过传输加密。",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                coroutineScope.launch {
                    val result = actions.onSyncNow()
                    snackbarHostState.showSnackbar(
                        when (result) {
                            is LedgerSyncOperationResult.Success -> "同步完成"
                            is LedgerSyncOperationResult.Failure -> result.message
                        }
                    )
                }
            },
            enabled = !ledgerSyncState.syncBusy,
            modifier = Modifier.testTag("ledger-sync-now")
        ) { Text(if (ledgerSyncState.syncBusy) "同步中" else "立即同步") }
        OutlinedButton(
            onClick = { coroutineScope.launch { actions.onDisable() } },
            enabled = !ledgerSyncState.syncBusy
        ) { Text("关闭同步") }
    }
    ledgerSyncState.conflicts.forEach { conflict ->
        SyncConflictCard(
            conflict = conflict,
            coroutineScope = coroutineScope,
            actions = actions
        )
    }
}

@Composable
private fun SyncConflictCard(
    conflict: LedgerSyncConflictUiModel,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ledger-sync-conflict-${conflict.conflictId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(conflict.title, fontWeight = FontWeight.SemiBold)
            Text("云端：${conflict.cloudSummary}")
            Text("本机：${conflict.localSummary}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    coroutineScope.launch {
                        actions.onResolveConflict(
                            conflict.conflictId,
                            conflict.canonicalVersion,
                            LedgerSyncConflictChoiceContract.CANONICAL
                        )
                    }
                }, enabled = !conflict.resolving) { Text("保留云端") }
                Button(onClick = {
                    coroutineScope.launch {
                        actions.onResolveConflict(
                            conflict.conflictId,
                            conflict.canonicalVersion,
                            LedgerSyncConflictChoiceContract.CANDIDATE
                        )
                    }
                }, enabled = !conflict.resolving) { Text("保留本机") }
            }
        }
    }
}

@Composable
private fun FirstSyncConfirmationDialog(
    ledgerSyncState: LedgerSyncUiState,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    val preview = requireNotNull(ledgerSyncState.preview)
    val syncBusy = ledgerSyncState.syncBusy
    AlertDialog(
        onDismissRequest = { if (!syncBusy) onDismiss() },
        title = { Text("启用账户同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机 ${preview.localRecordCount} 项，云端 ${preview.cloudRecordCount} 项。")
                Text("正式账本数据将上传并以服务端可读取的形式保存；待确认记录和诊断日志不会上传。默认资金账户单独按账号保存。")
                Text("生产环境仅通过 HTTPS 传输；账号最终注销将删除云端同步数据，本机账本仍会保留。")
                if (preview.insecureHttpTestMode) {
                    Text("当前使用局域网 HTTP，仅适用于受控测试环境。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        val mode = if (preview.localRecordCount == 0 && preview.cloudRecordCount > 0) {
                            LedgerSyncInitialMode.REPLACE_LOCAL
                        } else {
                            LedgerSyncInitialMode.MERGE
                        }
                        val result = actions.onEnable(mode)
                        if (result is LedgerSyncOperationResult.Success) onDismiss()
                        snackbarHostState.showSnackbar(
                            when (result) {
                                is LedgerSyncOperationResult.Success -> "账户同步已启用"
                                is LedgerSyncOperationResult.Failure -> result.message
                            }
                        )
                    }
                },
                enabled = !syncBusy
            ) { Text(if (preview.localRecordCount > 0 && preview.cloudRecordCount > 0) "确认并合并" else "确认启用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

class LedgerSyncSectionActions(
    val onPreview: suspend () -> LedgerSyncOperationResult<LedgerSyncPreview> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    val onDismissPreview: () -> Unit = {},
    val onEnable: suspend (LedgerSyncInitialMode) -> LedgerSyncOperationResult<Unit> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    val onSyncNow: suspend () -> LedgerSyncOperationResult<Unit> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    val onDisable: suspend () -> Unit = {},
    val onResolveConflict: suspend (String, Long, LedgerSyncConflictChoiceContract) ->
        LedgerSyncOperationResult<Unit> = { _, _, _ ->
            LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
        }
)
