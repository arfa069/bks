package com.bks.view.monitoring

import com.bks.viewmodel.monitoring.AutomaticBookkeepingUiState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.view.common.components.Button
import com.bks.view.common.components.TextButton
@Composable
@Suppress("LongParameterList")
fun AutomaticBookkeepingScreen(
    state: AutomaticBookkeepingUiState = AutomaticBookkeepingUiState(),
    onEnabledChange: (Boolean) -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    onOpenPowerPolicySettings: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val status = when {
        !state.enabled -> "已关闭"
        !state.accessibilityAccessGranted -> "等待无障碍授权"
        !state.accessibilityServiceConnected -> "等待服务连接"
        else -> "正在监听"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("automatic-bookkeeping-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(
            text = "自动记账",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("自动识别", style = MaterialTheme.typography.titleMedium)
                        Text(status, modifier = Modifier.testTag("automatic-bookkeeping-status"))
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = !state.writeInProgress,
                        modifier = Modifier.testTag("automatic-bookkeeping-toggle")
                    )
                }
                Button(onClick = onOpenAccessibilitySettings) {
                    Text(if (state.accessibilityAccessGranted) "管理无障碍授权" else "打开无障碍设置")
                }
            }
        }
        BackgroundRuntimeSettingsCard(
            batteryOptimizationIgnored = state.batteryOptimizationIgnored,
            onOpenAutoStartSettings = onOpenAutoStartSettings,
            onOpenPowerPolicySettings = onOpenPowerPolicySettings
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("隐私说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("开启后仅在设备本地读取当前活动窗口中可见、非输入框的文字。")
                Text("不会截图、不会操作其他应用、不会保存或上传原始页面文字。")
                Text("识别结果只进入待确认，必须由你确认后才会入账。")
            }
        }
    }
}

@Composable
private fun BackgroundRuntimeSettingsCard(
    batteryOptimizationIgnored: Boolean?,
    onOpenAutoStartSettings: () -> Unit,
    onOpenPowerPolicySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "后台运行保障",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            BackgroundSettingItem(
                title = "自启动",
                status = "状态：请在系统设置中确认",
                description = "Android 不提供统一、可靠的自启动状态查询接口。",
                actionLabel = "查看自启动设置",
                statusTag = "automatic-bookkeeping-autostart-status",
                actionTag = "automatic-bookkeeping-autostart-settings",
                onClick = onOpenAutoStartSettings
            )
            HorizontalDivider()
            BackgroundSettingItem(
                title = "电源策略",
                status = when (batteryOptimizationIgnored) {
                    true -> "系统电池优化：已豁免"
                    false -> "系统电池优化：未豁免"
                    null -> "系统电池优化：状态不可用"
                },
                description = "厂商的“无限制”等附加策略以系统设置页为准。",
                actionLabel = "查看电源策略",
                statusTag = "automatic-bookkeeping-power-status",
                actionTag = "automatic-bookkeeping-power-settings",
                onClick = onOpenPowerPolicySettings
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun BackgroundSettingItem(
    title: String,
    status: String,
    description: String,
    actionLabel: String,
    statusTag: String,
    actionTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(status, modifier = Modifier.testTag(statusTag))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(actionTag)
        ) {
            Text(actionLabel)
        }
    }
}
