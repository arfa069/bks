package com.bks.view.settings

import com.bks.model.domain.settings.isEncryptedLocalDataBackup
import com.bks.model.domain.settings.MIN_BACKUP_PASSPHRASE_LENGTH
import com.bks.viewmodel.ledger.label
import com.bks.viewmodel.settings.LedgerCsvEntryUiModel
import com.bks.viewmodel.settings.DataAndBackupOperation
import com.bks.viewmodel.settings.DataAndBackupUiState
import com.bks.viewmodel.settings.exportLedgerCsv
import com.bks.viewmodel.settings.ledgerCsvFilename
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bks.view.common.components.Button
import com.bks.view.common.components.OutlinedButton
import com.bks.view.common.components.OutlinedTextField
import com.bks.view.common.components.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingRestore(
    val backup: String,
    val passphrase: String
)

@Suppress("LongParameterList")
internal class LocalBackupSectionActions(
    val onExport: (
        passphrase: String,
        writer: suspend (String) -> String
    ) -> Unit,
    val onValidate: (Long, String, String) -> Long?,
    val onImport: (String, String) -> Unit,
    val onAttachUi: () -> Long,
    val onDetachUi: (Long) -> Unit,
    val onAcknowledgeValidation: (Long, Long) -> Unit,
    val onEndValidatedContinuation: (Long) -> Unit,
    val onClearValidation: () -> Unit
)

private class ImportDialogCallbacks(
    val onDismiss: () -> Unit,
    val onValidated: (PendingRestore) -> Unit
)

private class BackupDialogEnvironment(
    val context: Context,
    val actions: LocalBackupSectionActions,
    val operationState: DataAndBackupUiState
)

@Composable
internal fun LocalBackupSection(
    ledgerEntries: List<LedgerCsvEntryUiModel>,
    currentLedgerName: String,
    actions: LocalBackupSectionActions,
    operationState: DataAndBackupUiState,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val uiSessionId = rememberDataAndBackupUiSession(actions)
    var selectedBackup by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var isReadingBackup by remember { mutableStateOf(false) }
    val dialogEnvironment = BackupDialogEnvironment(
        context = applicationContext,
        actions = actions,
        operationState = operationState
    )

    LaunchedEffect(operationState.notice?.id) {
        when (operationState.notice?.operation) {
            DataAndBackupOperation.Exporting -> showExportPasswordDialog = false
            DataAndBackupOperation.Importing -> pendingRestore = null
            else -> Unit
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReadingBackup = true
        coroutineScope.launch {
            val result = runCatching { readBackupFile(context, uri) }
            isReadingBackup = false
            result.onSuccess { content ->
                if (isEncryptedLocalDataBackup(content)) {
                    actions.onClearValidation()
                    selectedBackup = content
                } else {
                    snackbarHostState.showSnackbar("所选文件不是受支持的加密备份")
                }
            }.onFailure {
                snackbarHostState.showSnackbar("备份文件读取失败")
            }
        }
    }

    CardSection(title = "导出与恢复") {
        Text(
            "CSV 仅导出当前账本「$currentLedgerName」，且是明文表格；" +
                "加密备份包含全部账本及本机设置。"
        )
        Row(
            modifier = Modifier.testTag("backup-actions").horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CsvExportButton(
                ledgerEntries = ledgerEntries,
                currentLedgerName = currentLedgerName,
                context = context,
                coroutineScope = coroutineScope,
                snackbarHostState = snackbarHostState
            )
            OutlinedButton(
                onClick = { showExportPasswordDialog = true },
                enabled = !operationState.operationInProgress
            ) { Text("导出备份", maxLines = 1) }
            OutlinedButton(
                onClick = { openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !isReadingBackup && !operationState.operationInProgress
            ) { Text("导入备份", maxLines = 1) }
        }
    }

    if (showExportPasswordDialog) {
        ExportBackupDialog(
            environment = dialogEnvironment,
            onDismissed = { showExportPasswordDialog = false }
        )
    }

    selectedBackup?.let { backup ->
        val activeUiSessionId = uiSessionId ?: return@let
        ImportBackupDialog(
            backup = backup,
            uiSessionId = activeUiSessionId,
            actions = actions,
            operationState = operationState,
            callbacks = ImportDialogCallbacks(
                onDismiss = { selectedBackup = null },
                onValidated = {
                    pendingRestore = it
                    selectedBackup = null
                }
            )
        )
    }

    pendingRestore?.let { restore ->
        val activeUiSessionId = uiSessionId ?: return@let
        RestoreConfirmationDialog(
            restore = restore,
            uiSessionId = activeUiSessionId,
            environment = dialogEnvironment,
            onDismissed = { pendingRestore = null }
        )
    }
}

@Composable
private fun rememberDataAndBackupUiSession(actions: LocalBackupSectionActions): Long? {
    var uiSessionId by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        val attachedSessionId = actions.onAttachUi()
        uiSessionId = attachedSessionId
        onDispose { actions.onDetachUi(attachedSessionId) }
    }
    return uiSessionId
}

@Composable
private fun CsvExportButton(
    ledgerEntries: List<LedgerCsvEntryUiModel>,
    currentLedgerName: String,
    context: Context,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    var isCsvExporting by remember { mutableStateOf(false) }
    Button(
        onClick = {
            isCsvExporting = true
            coroutineScope.launch {
                runCatching {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                    val filename = ledgerCsvFilename(currentLedgerName, timestamp)
                    val content = withContext(Dispatchers.Default) {
                        exportLedgerCsv(ledgerEntries)
                    }
                    writeDownloadFile(
                        context = context,
                        filename = filename,
                        mimeType = "text/csv",
                        content = content
                    )
                    filename
                }.onSuccess {
                    snackbarHostState.showSnackbar("CSV 已保存到 Download/$it")
                }.onFailure {
                    snackbarHostState.showSnackbar("CSV 导出失败")
                }
                isCsvExporting = false
            }
        },
        enabled = !isCsvExporting
    ) { Text("导出 CSV") }
}

@Composable
private fun ExportBackupDialog(
    environment: BackupDialogEnvironment,
    onDismissed: () -> Unit
) {
    val actions = environment.actions
    var passphrase by remember { mutableStateOf("") }
    val isBusy = environment.operationState.operation == DataAndBackupOperation.Exporting
    BackupPasswordDialog(
        title = "导出加密备份",
        description = "请输入大于 8 位的备份密码。密码不会保存，丢失后无法恢复备份。",
        passphrase = passphrase,
        onPassphraseChange = { passphrase = it },
        confirmLabel = "确认导出",
        isBusy = isBusy,
        minimumLength = MIN_BACKUP_PASSPHRASE_LENGTH,
        onDismiss = {
            if (!isBusy) {
                passphrase = ""
                onDismissed()
            }
        },
        onConfirm = {
            actions.onExport(
                passphrase,
                { content ->
                    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                    val filename = "$timestamp-ac-backup.bak"
                    writeDownloadFile(
                        context = environment.context,
                        filename = filename,
                        mimeType = "application/octet-stream",
                        content = content
                    )
                    filename
                }
            )
            passphrase = ""
            onDismissed()
        }
    )
}

@Composable
private fun ImportBackupDialog(
    backup: String,
    uiSessionId: Long,
    actions: LocalBackupSectionActions,
    operationState: DataAndBackupUiState,
    callbacks: ImportDialogCallbacks
) {
    var passphrase by remember { mutableStateOf("") }
    var validationRequestId by remember { mutableStateOf<Long?>(null) }
    var passwordError by remember { mutableStateOf(false) }
    val validationResult = operationState.validationResult
    LaunchedEffect(validationResult) {
        val requestId = validationRequestId
        if (
            requestId != null &&
            validationResult?.requestId == requestId &&
            validationResult.uiSessionId == uiSessionId
        ) {
            actions.onAcknowledgeValidation(requestId, uiSessionId)
            validationRequestId = null
            if (validationResult.valid) {
                callbacks.onValidated(PendingRestore(backup, passphrase))
                passphrase = ""
            } else {
                passwordError = true
            }
        }
    }
    val isBusy = operationState.operation == DataAndBackupOperation.Validating
    BackupPasswordDialog(
        title = "输入备份密码",
        description = "该文件是加密备份。请输入密码，验证通过后才能恢复。",
        passphrase = passphrase,
        onPassphraseChange = {
            passphrase = it
            passwordError = false
            actions.onEndValidatedContinuation(uiSessionId)
            actions.onClearValidation()
        },
        confirmLabel = "确认",
        isBusy = isBusy,
        errorMessage = if (passwordError) {
            "密码错误，或备份文件已损坏，请重试"
        } else {
            null
        },
        onDismiss = {
            if (!isBusy) {
                passphrase = ""
                actions.onEndValidatedContinuation(uiSessionId)
                actions.onClearValidation()
                callbacks.onDismiss()
            }
        },
        onConfirm = {
            validationRequestId = actions.onValidate(uiSessionId, backup, passphrase)
        }
    )
}

@Composable
private fun RestoreConfirmationDialog(
    restore: PendingRestore,
    uiSessionId: Long,
    environment: BackupDialogEnvironment,
    onDismissed: () -> Unit
) {
    val isBusy = environment.operationState.operation == DataAndBackupOperation.Importing
    AlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                environment.actions.onEndValidatedContinuation(uiSessionId)
                onDismissed()
            }
        },
        title = { Text("确认恢复备份") },
        text = { Text("备份已通过校验。继续将替换本机现有数据，此操作无法撤销。") },
        confirmButton = {
            Button(onClick = {
                environment.actions.onEndValidatedContinuation(uiSessionId)
                environment.actions.onImport(restore.backup, restore.passphrase)
                onDismissed()
            }, enabled = !isBusy) {
                Text(if (isBusy) "正在恢复" else "确认替换并恢复")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    environment.actions.onEndValidatedContinuation(uiSessionId)
                    onDismissed()
                },
                enabled = !isBusy
            ) { Text("取消") }
        }
    )
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    description: String,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirmLabel: String,
    isBusy: Boolean,
    minimumLength: Int? = null,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val meetsMinimumLength = minimumLength == null || passphrase.length >= minimumLength
    val canConfirm = passphrase.isNotBlank() && meetsMinimumLength && !isBusy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("备份密码") },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = errorMessage != null,
                    supportingText = when {
                        errorMessage != null -> ({ Text(errorMessage) })
                        minimumLength != null -> ({ Text("密码需大于 8 位") })
                        else -> null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("backup-password-dialog-input")
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = canConfirm) {
                Text(if (isBusy) "请稍候" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("取消") }
        }
    )
}

private suspend fun readBackupFile(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Failed to read backup file")
    }

private suspend fun writeDownloadFile(
    context: Context,
    filename: String,
    mimeType: String,
    content: String
) = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        values
    ) ?: error("Failed to create Downloads entry")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("Failed to write export file")
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}
