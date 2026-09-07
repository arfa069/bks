package com.bks.view.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bks.BuildConfig
import com.bks.model.domain.diagnostics.DIAGNOSTICS_EXPORT_EXTENSION
import com.bks.view.diagnostics.DiagnosticLogsScreen
import com.bks.view.diagnostics.DiagnosticLogsScreenActions
import com.bks.viewmodel.diagnostics.DiagnosticLogsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BksDiagnosticLogsRoute(
    viewModel: DiagnosticLogsViewModel,
    onBack: () -> Unit,
    innerPadding: PaddingValues,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    platform: DiagnosticLogsRoutePlatform = DiagnosticLogsRoutePlatform()
) {
    if (!isDebugBuild) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var exportWriterJob by remember { mutableStateOf<Job?>(null) }
    val exportWriter = remember(context, platform.exportWriterOverride) {
        platform.exportWriterOverride ?: { plainText: String -> writeDiagnosticExport(context, plainText) }
    }

    LaunchedEffect(viewModel, exportWriter) {
        viewModel.writeRequests.collect { request ->
            if (viewModel.beginWrite(request.requestId)) {
                lateinit var writerJob: Job
                writerJob = launch(start = CoroutineStart.LAZY) {
                    try {
                        val displayName = withContext(Dispatchers.IO) {
                            exportWriter(request.plainText)
                        }
                        viewModel.finishWrite(request.requestId, displayName)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        viewModel.finishWrite(request.requestId, displayName = null)
                    } finally {
                        if (exportWriterJob === writerJob) exportWriterJob = null
                    }
                }
                exportWriterJob = writerJob
                writerJob.start()
            }
        }
    }
    DiagnosticLogsRouteEffects(
        lifecycleOwner = lifecycleOwner,
        applySecureWindowFlag = platform.applySecureWindowFlag,
        cancelExport = {
            exportWriterJob?.cancel()
            viewModel.cancelExport(showResult = false)
        }
    )

    DiagnosticLogsScreen(
        state = state,
        actions = DiagnosticLogsScreenActions(
            onBack = onBack,
            onSetEnabled = { enabled, confirmed -> viewModel.setEnabled(enabled, confirmed) },
            onRefresh = viewModel::refresh,
            onExport = viewModel::export,
            onClear = viewModel::clear,
            onDismissExportResult = viewModel::dismissExportResult
        ),
        isDebugBuild = isDebugBuild,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun DiagnosticLogsRouteEffects(
    lifecycleOwner: LifecycleOwner,
    applySecureWindowFlag: Boolean,
    cancelExport: () -> Unit
) {
    val currentCancelExport by rememberUpdatedState(cancelExport)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentCancelExport()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentCancelExport()
        }
    }
    SecureWindowWhile(applySecureWindowFlag)
}

@Composable
internal fun SecureWindowWhile(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val wasAlreadySecure = activity?.window?.attributes?.flags?.let { flags ->
            flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        } ?: false
        if (enabled && !wasAlreadySecure) setDiagnosticSecureFlag(activity, true)
        onDispose {
            if (enabled && !wasAlreadySecure) setDiagnosticSecureFlag(activity, false)
        }
    }
}

internal fun setDiagnosticSecureFlag(activity: Activity?, enabled: Boolean) {
    if (enabled) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun writeDiagnosticExport(context: Context, plainText: String): String {
    val displayName = "bks-diagnostics-${System.currentTimeMillis()}.$DIAGNOSTICS_EXPORT_EXTENSION"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
        "Cannot create export file"
    }
    try {
        checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
            output.write(plainText.toByteArray(Charsets.UTF_8))
        }
        val publishValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        check(context.contentResolver.update(uri, publishValues, null, null) == 1) {
            "Cannot publish export file"
        }
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
    return displayName
}
