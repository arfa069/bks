package com.bks.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.bks.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.bks.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.feature.ledger.LedgerUiEntry
import com.bks.feature.sync.LedgerSyncUiState
import kotlinx.coroutines.launch

@Composable
@Suppress("LongParameterList")
fun DataAndBackupScreen(
    ledgerEntries: List<LedgerUiEntry>,
    currentLedgerName: String = "默认账本",
    onExportEncryptedBackup: suspend (String) -> String,
    onValidateEncryptedBackup: suspend (String, String) -> Unit,
    onImportEncryptedBackup: suspend (String, String) -> Unit,
    onDeleteLocalData: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    localDataReplacementInFlight: Boolean = false,
    ledgerSyncState: LedgerSyncUiState = LedgerSyncUiState(),
    ledgerSyncActions: LedgerSyncSectionActions = LedgerSyncSectionActions()
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteRequestInFlight by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val deletionInFlight = deleteRequestInFlight || localDataReplacementInFlight

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("数据与备份", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        LedgerSyncSettingsSection(
            ledgerSyncState = ledgerSyncState,
            actions = ledgerSyncActions,
            snackbarHostState = snackbarHostState
        )
        LocalBackupSection(
            ledgerEntries = ledgerEntries,
            currentLedgerName = currentLedgerName,
            actions = LocalBackupSectionActions(
                onExportEncryptedBackup = onExportEncryptedBackup,
                onValidateEncryptedBackup = onValidateEncryptedBackup,
                onImportEncryptedBackup = onImportEncryptedBackup
            ),
            snackbarHostState = snackbarHostState
        )
        CardSection(title = "危险区", danger = true) {
            Text("删除本机数据不会注销云端账号，且无法撤销。")
            OutlinedButton(onClick = { showDeleteDialog = true }) {
                Text("删除本机数据")
            }
        }
    }

    if (showDeleteDialog || localDataReplacementInFlight) {
        LocalDataDeletionDialog(
            deletionInFlight = deletionInFlight,
            onDismiss = {
                if (!deletionInFlight) {
                    showDeleteDialog = false
                }
            },
            onDelete = {
                if (!deletionInFlight) {
                    deleteRequestInFlight = true
                    coroutineScope.launch {
                        val failure = runCatching { onDeleteLocalData() }.exceptionOrNull()
                        deleteRequestInFlight = false
                        if (failure == null) {
                            showDeleteDialog = false
                        } else {
                            snackbarHostState.showSnackbar("删除本机数据失败，请重试")
                        }
                    }
                }
            }
        )
    }
}

@Composable
internal fun CardSection(
    title: String,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun LocalDataDeletionDialog(
    deletionInFlight: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var state by remember { mutableStateOf(LocalDataDeletionState()) }
    AlertDialog(
        onDismissRequest = { if (!deletionInFlight) onDismiss() },
        title = { Text("删除本机数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("确认删除前请先导出加密备份。")
                OutlinedButton(
                    onClick = {
                        state = reduceLocalDataDeletionState(
                            state,
                            LocalDataDeletionAction.SetBackupReminderAccepted(!state.backupReminderAccepted)
                        )
                    },
                    enabled = !deletionInFlight
                ) { Text("我已了解并完成需要的备份") }
                OutlinedTextField(
                    value = state.confirmationText,
                    onValueChange = {
                        state = reduceLocalDataDeletionState(
                            state,
                            LocalDataDeletionAction.UpdateConfirmationText(it)
                        )
                    },
                    label = { Text("输入 $DELETE_LOCAL_DATA_PHRASE") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deletionInFlight
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                enabled = state.canDelete && !deletionInFlight
            ) {
                Text(if (deletionInFlight) "正在删除…" else "确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deletionInFlight) { Text("取消") }
        }
    )
}
