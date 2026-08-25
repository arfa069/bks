package com.bks.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.bks.ui.components.HomeReturnButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.ui.visual.CachedResourceImage

enum class ProfileDestination(
    val title: String,
    val iconRes: Int
) {
    AccountManagement("账户管理", R.drawable.aa_profile_account),
    AutomaticBookkeeping("自动记账", R.drawable.aa_profile_automatic),
    CategorizationRules("分类规则", R.drawable.aa_profile_rules),
    DataAndBackup("数据与备份", R.drawable.aa_profile_backup),
    ComplianceAndPrivacy("合规与隐私", R.drawable.aa_profile_privacy)
}

@Composable
fun ProfileOverviewScreen(
    accountSummary: ProfileAccountSummaryUiModel,
    onDestinationSelected: (ProfileDestination) -> Unit,
    onNavigateHome: () -> Unit = {},
    ledgerSyncEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            HomeReturnButton(onClick = onNavigateHome)
        }
        AccountStatusCard(
            accountSummary = accountSummary,
            onClick = { onDestinationSelected(ProfileDestination.AccountManagement) }
        )
        ProfileDestination.entries
            .filterNot { it == ProfileDestination.AccountManagement }
            .forEach { destination ->
                ProfileEntry(
                    destination = destination,
                    summary = destination.summary(ledgerSyncEnabled),
                    onClick = { onDestinationSelected(destination) }
                )
            }
    }
}

@Composable
private fun AccountStatusCard(
    accountSummary: ProfileAccountSummaryUiModel,
    onClick: () -> Unit
) {
    ProfileEntry(
        destination = ProfileDestination.AccountManagement,
        summary = accountSummary.text,
        testTag = "profile-account-status-card",
        onClick = onClick
    )
}

@Composable
private fun ProfileEntry(
    destination: ProfileDestination,
    summary: String,
    testTag: String = "profile-entry-${destination.name}",
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedResourceImage(
                imageRes = destination.iconRes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(destination.title, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

data class ProfileAccountSummaryUiModel(val text: String)

private fun ProfileDestination.summary(ledgerSyncEnabled: Boolean): String = when (this) {
    ProfileDestination.AccountManagement -> error("Account summary is rendered by AccountStatusCard")
    ProfileDestination.AutomaticBookkeeping -> "查看功能说明"
    ProfileDestination.CategorizationRules -> "管理本地分类规则"
    ProfileDestination.DataAndBackup -> if (ledgerSyncEnabled) "账户同步已启用 · 导入、导出与备份" else "导入、导出与备份"
    ProfileDestination.ComplianceAndPrivacy -> "查看隐私与权限说明"
}
