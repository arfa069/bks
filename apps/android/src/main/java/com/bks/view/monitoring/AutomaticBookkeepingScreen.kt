package com.bks.view.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.view.common.components.TextButton
import com.bks.view.common.visual.CachedResourceImage
import com.bks.viewmodel.monitoring.AutomaticBookkeepingUiState

private val AutomaticContentMaxWidth = 880.dp

@Composable
@Suppress("LongParameterList")
fun AutomaticBookkeepingScreen(
    state: AutomaticBookkeepingUiState = AutomaticBookkeepingUiState(),
    onEnabledChange: (Boolean) -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    onOpenPowerPolicySettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = AutomaticContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    top = 20.dp,
                    end = horizontalPadding,
                    bottom = 32.dp
                )
                .testTag("automatic-bookkeeping-page"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AutomaticBookkeepingHeader(onBack = onBack)
            AutomaticBookkeepingStatusCard(
                state = state,
                onEnabledChange = onEnabledChange
            )
            BackgroundRuntimeSettingsSection(
                state = state,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAutoStartSettings = onOpenAutoStartSettings,
                onOpenPowerPolicySettings = onOpenPowerPolicySettings,
                onOpenNotificationSettings = onOpenNotificationSettings
            )
        }
    }
}

@Composable
private fun AutomaticBookkeepingHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "自动记账",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "管理识别、授权与后台运行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag("automatic-bookkeeping-back")
        ) {
            Text("返回")
        }
    }
}

@Composable
private fun AutomaticBookkeepingStatusCard(
    state: AutomaticBookkeepingUiState,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) {
                CachedResourceImage(
                    imageRes = R.drawable.aa_profile_automatic,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = "自动识别",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                AutomaticBookkeepingStatusBadge(state)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !state.writeInProgress,
                modifier = Modifier.testTag("automatic-bookkeeping-toggle")
            )
        }
    }
}

@Composable
private fun AutomaticBookkeepingStatusBadge(state: AutomaticBookkeepingUiState) {
    val label: String
    val indicatorColor: Color
    val containerColor: Color
    val contentColor: Color
    when {
        !state.enabled -> {
            label = "已关闭"
            indicatorColor = MaterialTheme.colorScheme.outline
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        !state.accessibilityAccessGranted -> {
            label = "等待无障碍授权"
            indicatorColor = MaterialTheme.colorScheme.tertiary
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        !state.accessibilityServiceConnected -> {
            label = "监听服务未运行"
            indicatorColor = MaterialTheme.colorScheme.tertiary
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            label = "正在监听"
            indicatorColor = MaterialTheme.colorScheme.secondary
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        }
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(indicatorColor, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("automatic-bookkeeping-status")
            )
        }
    }
}

@Composable
private fun BackgroundRuntimeSettingsSection(
    state: AutomaticBookkeepingUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onOpenPowerPolicySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    AutomaticBookkeepingSection(
        title = "后台运行保障"
    ) {
        Column {
            BackgroundSettingItem(
                iconLabel = "权",
                title = "无障碍授权",
                status = if (state.accessibilityAccessGranted) "已授权" else "未授权",
                actionLabel = if (state.accessibilityAccessGranted) "管理" else "开启",
                rowTag = "automatic-bookkeeping-accessibility-row",
                statusTag = "automatic-bookkeeping-access-status",
                actionTag = "automatic-bookkeeping-accessibility-settings",
                onClick = onOpenAccessibilitySettings
            )
            BackgroundSettingDivider()
            BackgroundSettingItem(
                iconLabel = "启",
                title = "自启动",
                status = "请确认",
                actionLabel = "设置",
                rowTag = "automatic-bookkeeping-autostart-row",
                statusTag = "automatic-bookkeeping-autostart-status",
                actionTag = "automatic-bookkeeping-autostart-settings",
                onClick = onOpenAutoStartSettings
            )
            BackgroundSettingDivider()
            BackgroundSettingItem(
                iconLabel = "电",
                title = "电源策略",
                status = when (state.batteryOptimizationIgnored) {
                    true -> "已豁免"
                    false -> "未豁免"
                    null -> "不可用"
                },
                actionLabel = "设置",
                rowTag = "automatic-bookkeeping-power-row",
                statusTag = "automatic-bookkeeping-power-status",
                actionTag = "automatic-bookkeeping-power-settings",
                onClick = onOpenPowerPolicySettings
            )
            BackgroundSettingDivider()
            BackgroundSettingItem(
                iconLabel = "通",
                title = "通知设置",
                status = if (state.systemNotificationsAllowed) "已允许" else "未允许",
                actionLabel = "设置",
                rowTag = "automatic-bookkeeping-notifications-row",
                statusTag = "automatic-bookkeeping-notifications-status",
                actionTag = "automatic-bookkeeping-notifications-settings",
                onClick = onOpenNotificationSettings
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun BackgroundSettingItem(
    iconLabel: String,
    title: String,
    status: String,
    actionLabel: String,
    rowTag: String,
    statusTag: String,
    actionTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .testTag(rowTag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 9.dp, vertical = 5.dp)
                    .testTag(statusTag)
            )
        }
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(actionTag)
        ) {
            Text(
                text = actionLabel,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun BackgroundSettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp, end = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    )
}

@Composable
private fun AutomaticBookkeepingSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            content()
        }
    }
}
