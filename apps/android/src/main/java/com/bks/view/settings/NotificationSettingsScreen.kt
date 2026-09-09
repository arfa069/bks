package com.bks.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bks.model.domain.notifications.NotificationPreferences
import com.bks.view.common.components.TextButton

@Composable
@Suppress("LongParameterList")
internal fun NotificationSettingsScreen(
    state: NotificationPreferences,
    systemAllowed: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHideDetailsChange: (Boolean) -> Unit,
    onInterruptionChange: (Boolean) -> Unit,
    onBacklogChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("通知设置", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("新增待确认提醒", Modifier.weight(1f))
            Switch(state.enabled, onCheckedChange = onEnabledChange, modifier = Modifier.testTag("notifications-enabled"))
        }
        Text("新增待确认时提醒，多笔合并。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("自动记账中断提醒", Modifier.weight(1f))
            Switch(state.interruptionEnabled, onCheckedChange = onInterruptionChange,
                modifier = Modifier.testTag("notifications-interruption"))
        }
        Text("中断持续 30 秒后提醒，恢复后撤销。强制停止应用时可能无法提醒。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("待确认积压提醒", Modifier.weight(1f))
            Switch(state.backlogEnabled, onCheckedChange = onBacklogChange,
                modifier = Modifier.testTag("notifications-backlog"))
        }
        Text("满 10 笔或超过 48 小时提醒，每 24 小时最多一次，后台检查可能延迟。")
        Text(if (systemAllowed) "系统通知已允许" else "系统通知未允许，不影响识别和保存待确认。")
        if (!systemAllowed) {
            TextButton(onClick = onRequestPermission) { Text("开启系统通知") }
        }
        TextButton(onClick = onOpenSystemSettings) { Text("系统通知设置") }
        Text("可在系统设置中分别关闭各类提醒。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("锁屏隐藏交易详情", Modifier.weight(1f))
            Switch(
                state.hideDetailsOnLockScreen, onCheckedChange = onHideDetailsChange,
                modifier = Modifier.testTag("notifications-hide-details")
            )
        }
        Text("关闭后可在锁屏显示详情，以系统设置为准。")
    }
}
