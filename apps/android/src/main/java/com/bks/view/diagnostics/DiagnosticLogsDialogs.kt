package com.bks.view.diagnostics

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bks.view.common.components.Button
import com.bks.view.common.components.TextButton
@Composable
internal fun EnableDiagnosticsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启敏感诊断日志？") },
        text = {
            Text(
                "将以明文记录应用故障、自动记账触发事件、节点数和支付宝页面文字。" +
                    "日志仅保存在本机应用目录，长期保留且最多占用 10 MB，超过上限才删除最旧分段，不会上传。" +
                    "认证秘密会在写入前清除，诊断页面禁止系统截图。导出的 UTF-8 .log 也是明文，需自行保管或删除。"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("理解并开启") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ClearDiagnosticsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认清空诊断日志？") },
        text = { Text("将删除全部新旧诊断日志分段，无法恢复；记录开关状态保持不变。") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认清空") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ExportResultDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.testTag("diagnostic-export-result-message")) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("确定") }
        }
    )
}
