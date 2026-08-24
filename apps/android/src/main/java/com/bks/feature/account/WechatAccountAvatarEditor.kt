package com.bks.feature.account

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.bks.ui.components.Button
import com.bks.ui.components.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class WechatAvatarEditorState(
    val showSourceDialog: Boolean = false,
    val pendingCameraUri: String? = null
)

internal data class WechatAvatarEditorActions(
    val onStateChange: (WechatAvatarEditorState) -> Unit,
    val onAvatarReadStarted: () -> Unit,
    val onAvatarReadCancelled: () -> Unit,
    val onAvatarDataReady: (String) -> Unit,
    val onAvatarReadFailure: (String) -> Unit
)

private data class WechatAvatarUpload(
    val context: Context,
    val avatarDataReader: suspend (Context, Uri) -> String,
    val actions: WechatAvatarEditorActions
)

@Composable
internal fun WechatAccountAvatarEditor(
    avatarDataReader: suspend (Context, Uri) -> String,
    state: WechatAvatarEditorState,
    actions: WechatAvatarEditorActions
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val upload = WechatAvatarUpload(
        context = context,
        avatarDataReader = avatarDataReader,
        actions = actions
    )
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.submitAvatar(upload, uri)
    }
    val avatarCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = state.pendingCameraUri?.let(Uri::parse)
        actions.onStateChange(state.copy(pendingCameraUri = null))
        if (uri == null) return@rememberLauncherForActivityResult
        if (captured) {
            coroutineScope.submitAvatar(upload, uri, deleteAfterRead = true)
        } else {
            context.deleteAvatarCapture(uri)
        }
    }

    if (state.showSourceDialog) {
        AlertDialog(
            onDismissRequest = {
                actions.onStateChange(state.copy(showSourceDialog = false))
            },
            title = { Text("修改头像") },
            text = { Text("请选择头像来源") },
            confirmButton = {
                Button(
                    onClick = {
                        actions.onStateChange(state.copy(showSourceDialog = false))
                        val uri = runCatching { context.createAvatarCaptureUri() }
                            .getOrElse {
                                actions.onAvatarReadFailure("无法启动相机，请稍后重试")
                                return@Button
                            }
                        actions.onStateChange(
                            state.copy(
                                showSourceDialog = false,
                                pendingCameraUri = uri.toString()
                            )
                        )
                        avatarCamera.launch(uri)
                    },
                    modifier = Modifier.testTag("take-avatar-photo")
                ) {
                    Text("拍照")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        actions.onStateChange(state.copy(showSourceDialog = false))
                        avatarPicker.launch("image/*")
                    },
                    modifier = Modifier.testTag("pick-avatar-gallery")
                ) {
                    Text("从相册选择")
                }
            }
        )
    }
}

private fun CoroutineScope.submitAvatar(
    upload: WechatAvatarUpload,
    uri: Uri,
    deleteAfterRead: Boolean = false
) {
    upload.actions.onAvatarReadStarted()
    launch {
        val avatarDataUrl = try {
            upload.avatarDataReader(upload.context, uri)
        } catch (error: CancellationException) {
            upload.actions.onAvatarReadCancelled()
            throw error
        } catch (_: Exception) {
            val message = "无法读取图片，请换一张图片重试"
            upload.actions.onAvatarReadFailure(message)
            return@launch
        } finally {
            if (deleteAfterRead) upload.context.deleteAvatarCapture(uri)
        }
        upload.actions.onAvatarDataReady(avatarDataUrl)
    }
}
