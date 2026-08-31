package com.bks.view.account

import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountDeletionState
import com.bks.model.platform.account.WechatAuthCallback
import com.bks.viewmodel.account.AccountManagementViewModel
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bks.view.common.components.Button
import com.bks.view.common.components.OutlinedButton
import com.bks.view.common.components.TextButton
import java.text.DateFormat
import java.util.Date

internal data class AccountManagementScreenDependencies(
    val viewModel: AccountManagementViewModel,
    val wechatAuthCallback: WechatAuthCallback? = null,
    val onWechatAuthCallbackConsumed: () -> Unit = {},
    val avatarCacheOverride: WechatAvatarCache? = null,
    val avatarDataReader: suspend (Context, Uri) -> String = { context, uri ->
        context.readCompressedAvatarDataUrl(uri)
    }
)

internal data class AccountManagementScreenState(
    val session: AccountSession,
    val runtimeState: AccountRuntimeState,
    val deletionState: AccountDeletionState
)

internal data class AccountManagementScreenActions(
    val onSignInOrRegister: () -> Unit,
    val onBack: () -> Unit
)

@Composable
internal fun AccountManagementScreen(
    screenState: AccountManagementScreenState,
    dependencies: AccountManagementScreenDependencies,
    actions: AccountManagementScreenActions,
    modifier: Modifier = Modifier
) {
    val session = screenState.session
    val runtimeState = screenState.runtimeState
    val deletionState = screenState.deletionState
    val viewModel = dependencies.viewModel
    val operationState by viewModel.state.collectAsStateWithLifecycle()
    var showDeletionConfirmation by remember { mutableStateOf(false) }
    val avatarCache = dependencies.avatarCacheOverride ?: rememberWechatAvatarCache()

    LaunchedEffect(session) {
        viewModel.activate(session)
    }
    val operationInProgress = operationState.accountOperationInProgress ||
        operationState.identityOperationInProgress
    LaunchedEffect(operationInProgress) {
        if (!operationInProgress) showDeletionConfirmation = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = actions.onBack) { Text("返回") }
        Text(
            text = "账户管理",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        when (session) {
            AccountSession.LocalMode -> {
                Text("当前使用本地模式，账本仅保存在本机。")
                Button(
                    onClick = actions.onSignInOrRegister,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("登录或注册")
                }
            }

            is AccountSession.SignedIn -> {
                if (runtimeState.status == AccountRuntimeStatus.OfflineUnverified) {
                    OfflineAccountConnectionCard(
                        onRetry = {
                            viewModel.retryVerification(session, deletionState)
                        },
                        retryEnabled = !operationInProgress
                    )
                }
                WechatAccountManagementPanel(
                    session = session,
                    deletionState = deletionState,
                    operationState = operationState,
                    dependencies = WechatAccountManagementDependencies(
                        viewModel = viewModel,
                        wechatAuthCallback = dependencies.wechatAuthCallback,
                        avatarCache = avatarCache,
                        onWechatAuthCallbackConsumed = dependencies.onWechatAuthCallbackConsumed,
                        avatarDataReader = dependencies.avatarDataReader
                    )
                )
                OutlinedButton(
                    onClick = { viewModel.signOut(session) },
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account-sign-out")
                ) {
                    Text(if (operationInProgress) "处理中…" else "退出登录")
                }
                AccountDeletionCard(
                    state = deletionState,
                    accountVerified = runtimeState.accountOperationsAllowed,
                    operationEnabled = runtimeState.accountOperationsAllowed &&
                        !operationInProgress,
                    onRequest = { showDeletionConfirmation = true },
                    onCancel = { viewModel.cancelDeletion(session) }
                )
            }
        }
        operationState.accountError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-error"))
        }
    }

    if (showDeletionConfirmation && session is AccountSession.SignedIn) {
        AccountDeletionConfirmationDialog(
            operationInProgress = operationInProgress,
            onConfirm = { viewModel.requestDeletion(session) },
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
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
    state: AccountDeletionState,
    accountVerified: Boolean,
    operationEnabled: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
