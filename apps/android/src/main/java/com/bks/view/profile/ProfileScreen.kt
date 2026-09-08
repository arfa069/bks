package com.bks.view.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.view.common.components.HomeReturnButton
import com.bks.view.common.visual.CachedResourceImage
import com.bks.viewmodel.account.ProfileAccountSummaryUiModel

private val ProfileContentMaxWidth = 880.dp
private val ProfileTwoColumnBreakpoint = 840.dp
private val ProfileUtilityDestinations = listOf(
    ProfileDestination.AutomaticBookkeeping,
    ProfileDestination.CategorizationRules,
    ProfileDestination.Notifications
)
private val ProfileDataDestinations = listOf(
    ProfileDestination.DataAndBackup,
    ProfileDestination.ComplianceAndPrivacy
)

enum class ProfileDestination(
    val title: String,
    val iconRes: Int
) {
    AccountManagement("账户管理", R.drawable.aa_profile_account),
    AutomaticBookkeeping("自动记账", R.drawable.aa_profile_automatic),
    CategorizationRules("分类规则", R.drawable.aa_profile_rules),
    Notifications("通知设置", R.drawable.aa_profile_automatic),
    DataAndBackup("数据与备份", R.drawable.aa_profile_backup),
    ComplianceAndPrivacy("合规与隐私", R.drawable.aa_profile_privacy),
    DiagnosticLogs("诊断日志", R.drawable.aa_profile_privacy)
}

@Composable
@Suppress("LongParameterList")
fun ProfileOverviewScreen(
    accountSummary: ProfileAccountSummaryUiModel,
    onDestinationSelected: (ProfileDestination) -> Unit,
    onNavigateHome: () -> Unit = {},
    ledgerSyncEnabled: Boolean = false,
    isDebugBuild: Boolean = false,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp
        val topPadding = if (maxWidth < 600.dp && maxHeight >= maxWidth * 1.7f) 88.dp else 20.dp
        val showTwoColumns = maxWidth >= ProfileTwoColumnBreakpoint
        val dataDestinations = if (isDebugBuild) {
            ProfileDataDestinations + ProfileDestination.DiagnosticLogs
        } else {
            ProfileDataDestinations
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ProfileContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    top = topPadding,
                    end = horizontalPadding,
                    bottom = 32.dp
                )
                .testTag("profile-content"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ProfileHeader(onNavigateHome = onNavigateHome)
            AccountStatusCard(
                accountSummary = accountSummary,
                onClick = { onDestinationSelected(ProfileDestination.AccountManagement) }
            )
            if (showTwoColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ProfileSection(
                        title = "记账设置",
                        destinations = ProfileUtilityDestinations,
                        ledgerSyncEnabled = ledgerSyncEnabled,
                        onDestinationSelected = onDestinationSelected,
                        modifier = Modifier.weight(1f)
                    )
                    ProfileSection(
                        title = "数据与安全",
                        destinations = dataDestinations,
                        ledgerSyncEnabled = ledgerSyncEnabled,
                        onDestinationSelected = onDestinationSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ProfileSection(
                    title = "记账设置",
                    destinations = ProfileUtilityDestinations,
                    ledgerSyncEnabled = ledgerSyncEnabled,
                    onDestinationSelected = onDestinationSelected
                )
                ProfileSection(
                    title = "数据与安全",
                    destinations = dataDestinations,
                    ledgerSyncEnabled = ledgerSyncEnabled,
                    onDestinationSelected = onDestinationSelected
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(onNavigateHome: () -> Unit) {
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
                text = "我的",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "管理账户与应用设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        HomeReturnButton(onClick = onNavigateHome)
    }
}

@Composable
private fun AccountStatusCard(
    accountSummary: ProfileAccountSummaryUiModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile-account-status-card")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        ),
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
                    imageRes = ProfileDestination.AccountManagement.iconRes,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = ProfileDestination.AccountManagement.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = accountSummary.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            ProfileChevron(
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    shape = CircleShape
                )
            )
        }
    }
}

@Composable
private fun ProfileSection(
    title: String,
    destinations: List<ProfileDestination>,
    ledgerSyncEnabled: Boolean,
    onDestinationSelected: (ProfileDestination) -> Unit,
    modifier: Modifier = Modifier
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            destinations.forEachIndexed { index, destination ->
                ProfileEntry(
                    destination = destination,
                    summary = destination.summary(ledgerSyncEnabled),
                    onClick = { onDestinationSelected(destination) }
                )
                if (index < destinations.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEntry(
    destination: ProfileDestination,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile-entry-${destination.name}")
            .clickable(onClick = onClick)
            .heightIn(min = 76.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CachedResourceImage(
            imageRes = destination.iconRes,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = destination.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        ProfileChevron()
    }
}

@Composable
private fun ProfileChevron(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun ProfileDestination.summary(ledgerSyncEnabled: Boolean): String = when (this) {
    ProfileDestination.AccountManagement -> error("Account summary is rendered by AccountStatusCard")
    ProfileDestination.AutomaticBookkeeping -> "查看功能说明与运行状态"
    ProfileDestination.CategorizationRules -> "管理本地分类规则"
    ProfileDestination.Notifications -> "待确认、中断提醒与锁屏隐私"
    ProfileDestination.DataAndBackup -> if (ledgerSyncEnabled) {
        "账户同步已启用 · 导入、导出与备份"
    } else {
        "导入、导出与备份"
    }
    ProfileDestination.ComplianceAndPrivacy -> "查看隐私、权限与合规说明"
    ProfileDestination.DiagnosticLogs -> "查看、导出与清空本机诊断日志"
}
