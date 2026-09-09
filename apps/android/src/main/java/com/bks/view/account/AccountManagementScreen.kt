package com.bks.view.account

import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountDeletionState
import com.bks.viewmodel.account.AccountManagementOperationState
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bks.R
import com.bks.view.common.components.ProfileDetailPage
import com.bks.view.common.components.ProfileDetailSummary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.view.common.components.Button
import com.bks.view.common.components.OutlinedButton
import com.bks.view.common.components.TextButton
import java.text.DateFormat
import java.util.Date

internal data class AccountManagementScreenPlatform(
    val avatarCacheOverride: WechatAvatarCache? = null,
    val avatarDataReader: suspend (Context, Uri) -> String = { context, uri ->
        context.readCompressedAvatarDataUrl(uri)
    }
)

internal data class AccountManagementScreenState(
    val session: AccountSession,
    val runtimeState: AccountRuntimeState,
    val deletionState: AccountDeletionState,
    val operationState: AccountManagementOperationState,
    val wechatAvailable: Boolean
)

internal data class AccountManagementScreenActions(
    val onSignInOrRegister: () -> Unit,
    val onBack: () -> Unit,
    val onRetryVerification: () -> Unit,
    val onSignOut: () -> Unit,
    val onRequestDeletion: () -> Unit,
    val onCancelDeletion: () -> Unit,
    val wechat: WechatAccountManagementActions
)

@Composable
internal fun AccountManagementScreen(
    screenState: AccountManagementScreenState,
    platform: AccountManagementScreenPlatform,
    actions: AccountManagementScreenActions,
    modifier: Modifier = Modifier
) {
    val session = screenState.session
    val runtimeState = screenState.runtimeState
    val operationState = screenState.operationState
    var showDeletionConfirmation by remember { mutableStateOf(false) }
    val avatarCache = platform.avatarCacheOverride ?: rememberWechatAvatarCache()

    val operationInProgress = operationState.accountOperationInProgress ||
        operationState.identityOperationInProgress
    LaunchedEffect(operationInProgress) {
        if (!operationInProgress) showDeletionConfirmation = false
    }

    ProfileDetailPage(
        title = "账户管理",
        subtitle = "管理个人信息与账户安全",
        onBack = actions.onBack,
        modifier = modifier
    ) {
        when (session) {
            AccountSession.LocalMode -> {
                ProfileDetailSummary(
                    title = "本地记账，安心记录",
                    description = "当前使用本地模式，账本仅保存在本机。",
                    artwork = R.drawable.aa_profile_account
                ) {
                    Button(
                        onClick = actions.onSignInOrRegister,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("登录或注册")
                    }
                }
            }

            is AccountSession.SignedIn -> {
                ProfileDetailSummary(
                    title = "我的账户",
                    description = runtimeState.status.connectionLabel(),
                    artwork = R.drawable.aa_profile_account
                )
                if (runtimeState.status == AccountRuntimeStatus.OfflineUnverified) {
                    OfflineAccountConnectionCard(
                        onRetry = {
                            actions.onRetryVerification()
                        },
                        retryEnabled = !operationInProgress
                    )
                }
                WechatAccountManagementPanel(
                    session = session,
                    state = WechatAccountManagementState(
                        operationState = operationState,
                        wechatAuthAvailable = screenState.wechatAvailable
                    ),
                    platform = WechatAccountManagementPlatform(
                        avatarCache = avatarCache,
                        avatarDataReader = platform.avatarDataReader
                    ),
                    actions = actions.wechat
                )
                AccountDeletionCard(
                    screenState = screenState,
                    onRequest = { showDeletionConfirmation = true },
                    onCancel = actions.onCancelDeletion
                ) {
                    OutlinedButton(
                        onClick = actions.onSignOut,
                        enabled = !operationInProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account-sign-out")
                    ) {
                        Text(if (operationInProgress) "处理中…" else "退出登录")
                    }
                }
            }
        }
        operationState.accountError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-error"))
        }
    }

    if (showDeletionConfirmation && session is AccountSession.SignedIn) {
        AccountDeletionConfirmationDialog(
            operationInProgress = operationInProgress,
            onConfirm = actions.onRequestDeletion,
            onDismiss = { showDeletionConfirmation = false }
        )
    }
}

@Composable
private fun AccountDeletionConfirmationDialog(
    operationInProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text("申请注销账号？") },
        text = {
            Text("申请后进入七天冷静期。期满将删除云端账号、注册设备、云端配置和 AI 分类日志；本机账本不会被删除。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !operationInProgress,
                modifier = Modifier.testTag("confirm-account-deletion")
            ) {
                Text(if (operationInProgress) "提交中…" else "确认申请")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("取消") }
        }
    )
}

@Composable
private fun OfflineAccountConnectionCard(
    onRetry: () -> Unit,
    retryEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                AccountRuntimeStatus.OfflineUnverified.connectionLabel(),
                modifier = Modifier.testTag("account-connection-status")
            )
            OutlinedButton(onClick = onRetry, enabled = retryEnabled) {
                Text("重新验证")
            }
        }
    }
}

@Composable
private fun AccountDeletionCard(
    screenState: AccountManagementScreenState,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    signOutContent: @Composable () -> Unit
) {
    val state = screenState.deletionState
    val accountVerified = screenState.runtimeState.accountOperationsAllowed
    val operationEnabled = accountVerified && !screenState.operationState.accountOperationInProgress &&
        !screenState.operationState.identityOperationInProgress
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            signOutContent()
            Text("账户注销", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.isPending) {
                Text("注销冷静期中，云端写入已暂停。")
                state.finalDeletionAtEpochMillis?.let { deadline ->
                    Text("预计最终注销时间：${deadline.formatDateTime()}")
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = operationEnabled,
                    modifier = Modifier.testTag("cancel-account-deletion")
                ) { Text("取消注销") }
            } else {
                Text("注销会删除云端账号数据；本机账本不受影响，也不会被重新分配。")
                Button(
                    onClick = onRequest,
                    enabled = operationEnabled,
                    modifier = Modifier.testTag("request-account-deletion")
                ) { Text("申请注销账号") }
            }
            if (!accountVerified) {
                Text("账号尚未完成在线验证，暂不能执行注销操作。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun AccountRuntimeStatus.connectionLabel(): String = when (this) {
    AccountRuntimeStatus.LocalMode -> "本地模式"
    AccountRuntimeStatus.Validating -> "正在验证账号连接"
    AccountRuntimeStatus.Verified -> "账号服务已连接"
    AccountRuntimeStatus.OfflineUnverified -> "离线使用 · 账号尚未验证"
    AccountRuntimeStatus.DeletionCoolingOff -> "账号服务已连接 · 注销冷静期"
}

private fun Long.formatDateTime(): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT
).format(Date(this))
