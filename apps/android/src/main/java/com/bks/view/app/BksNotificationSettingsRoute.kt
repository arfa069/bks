package com.bks.view.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.model.platform.app.bksAppContainer
import com.bks.view.settings.NotificationSettingsScreen
import com.bks.viewmodel.settings.NotificationSettingsViewModel

@Composable
internal fun BksNotificationSettingsRoute(
    innerPadding: PaddingValues,
    onNotice: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotificationSettingsViewModel(context.bksAppContainer.notificationPreferences) as T
        }
    }
    val viewModel: NotificationSettingsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    fun systemAllowed(): Boolean = manager.areNotificationsEnabled()
    var allowed by remember { mutableStateOf(systemAllowed()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = systemAllowed()
    }
    val openSettings = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }.onFailure { onNotice("无法打开系统通知设置，请到系统设置中手动开启") }
        Unit
    }
    DisposableEffect(lifecycleOwner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = systemAllowed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) { viewModel.notices.collect(onNotice) }
    NotificationSettingsScreen(
        state, allowed,
        onEnabledChange = viewModel::setEnabled,
        onHideDetailsChange = viewModel::setHideDetails,
        onInterruptionChange = viewModel::setInterruptionEnabled,
        onBacklogChange = viewModel::setBacklogEnabled,
        onRequestPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else openSettings()
        },
        onOpenSystemSettings = openSettings,
        onBack = onBack,
        modifier = Modifier.padding(innerPadding)
    )
}
