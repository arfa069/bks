package com.bks.view.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.model.domain.billsync.BillBackfillPhase
import com.bks.model.domain.billsync.BillBackfillState
import com.bks.model.domain.ledger.PaymentSource
import com.bks.view.common.components.Button
import com.bks.view.common.components.TextButton

@Composable
internal fun BillBackfillCard(
    state: BillBackfillState,
    onStart: (PaymentSource) -> Unit,
    onFinish: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSetup by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().testTag("review-backfill-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("补录历史账单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.isBusy) "${state.source.backfillLabel()} · 补录中" else "漏记的账单，补回待确认",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { if (state.isBusy) onFinish() else showSetup = true },
                    enabled = state.phase != BillBackfillPhase.Finishing,
                    modifier = Modifier.testTag("review-backfill-action")
                ) { Text(if (state.isBusy) "结束" else "补录") }
            }
            if (state.sessionId > 0) {
                Text(
                    "新增 ${state.insertedCount} 笔 · 跳过重复 ${state.duplicateCount} 笔",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("review-backfill-counts")
                )
            }
            if (state.message.isNotBlank()) {
                Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showSetup) {
        BillBackfillSetupDialog(
            serviceConnected = state.serviceConnected,
            onStart = { source -> onStart(source); showSetup = false },
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onDismiss = { showSetup = false }
        )
    }
}

@Composable
private fun BillBackfillSetupDialog(
    serviceConnected: Boolean,
    onStart: (PaymentSource) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var source by rememberSaveable { mutableStateOf(PaymentSource.ALIPAY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补录历史账单") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PaymentSource.ALIPAY, PaymentSource.WECHAT).forEach { item ->
                        FilterChip(
                            selected = source == item,
                            onClick = { source = item },
                            label = { Text(item.backfillLabel()) },
                            modifier = Modifier.testTag("review-backfill-source-${item.name}")
                        )
                    }
                }
                Text("开始后切换到${source.backfillLabel()}，逐笔打开账单详情并停留片刻。完成后回到这里结束补录。")
                Text("优先读取页面文字，读不全时仅识别当前账单画面。图片只在本机内存中处理，不保存、不上传。")
                Text("每次最长 10 分钟，结果需在待确认中审核。无需开启自动记账。")
                Text("画面识别需 Android 11 及以上，受保护的画面无法识别。", style = MaterialTheme.typography.bodySmall)
                if (!serviceConnected) {
                    Text("无障碍服务未运行，请先在系统设置中开启。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenAccessibilitySettings) { Text("无障碍设置") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(source) }, enabled = serviceConnected,
                modifier = Modifier.testTag("review-backfill-start")) { Text("开始补录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun PaymentSource.backfillLabel(): String = if (this == PaymentSource.WECHAT) "微信" else "支付宝"
