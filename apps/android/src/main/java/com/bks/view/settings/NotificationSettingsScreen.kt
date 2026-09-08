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
        Text("自动记账新增记录后提醒，包含金额、商户、建议分类和识别时间。短时间多笔记录合并提醒。")
        Text(if (systemAllowed) "系统通知已允许" else "系统通知未允许，仍会正常识别并保存到待确认")
        if (!systemAllowed) {
            TextButton(onClick = onRequestPermission) { Text("开启系统通知") }
        }
        TextButton(onClick = onOpenSystemSettings) { Text("系统通知设置") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("锁屏隐藏交易详情", Modifier.weight(1f))
            Switch(
                state.hideDetailsOnLockScreen, onCheckedChange = onHideDetailsChange,
                modifier = Modifier.testTag("notifications-hide-details")
            )
        }
        Text("默认开启。关闭后允许在锁屏展示交易详情，实际显示仍受系统锁屏设置控制。")
    }
}
