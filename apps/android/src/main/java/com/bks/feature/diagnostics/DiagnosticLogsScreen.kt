package com.bks.feature.diagnostics

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.bksAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class DiagnosticLogsLocalState {
    var showEnableConfirmation by mutableStateOf(false)
    var showClearConfirmation by mutableStateOf(false)
    var exportWriterJob by mutableStateOf<Job?>(null)
}

@Composable
internal fun DiagnosticLogsScreen(
    isDebugBuild: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overrides: DiagnosticLogsScreenOverrides = DiagnosticLogsScreenOverrides()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val defaultFactory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                context.bksAppContainer.createDiagnosticLogsViewModel() as T
        }
    }
    val logsViewModel: DiagnosticLogsViewModel = viewModel(
        factory = overrides.viewModelFactory ?: defaultFactory
    )
    val state by logsViewModel.uiState.collectAsStateWithLifecycle()
    val localState = remember { DiagnosticLogsLocalState() }
    val exportWriter = remember(context, overrides.exportWriter) {
        overrides.exportWriter ?: { plainText: String -> writeDiagnosticExport(context, plainText) }
    }

    LaunchedEffect(logsViewModel, exportWriter) {
        logsViewModel.writeRequests.collect { request ->
            if (logsViewModel.beginWrite(request.requestId)) {
                lateinit var writerJob: Job
                writerJob = launch(start = CoroutineStart.LAZY) {
                    try {
                        val displayName = withContext(Dispatchers.IO) {
                            exportWriter(request.plainText)
                        }
                        logsViewModel.finishWrite(request.requestId, displayName)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        logsViewModel.finishWrite(request.requestId, displayName = null)
                    } finally {
                        if (localState.exportWriterJob === writerJob) {
                            localState.exportWriterJob = null
                        }
                    }
                }
                localState.exportWriterJob = writerJob
                writerJob.start()
            }
        }
    }
    DiagnosticLogsEffects(
        lifecycleOwner = lifecycleOwner,
        applySecureWindowFlag = overrides.applySecureWindowFlag,
        onBackground = {
            localState.exportWriterJob?.cancel()
            logsViewModel.cancelExport(showResult = false)
        },
        onDisposeScreen = {
            localState.exportWriterJob?.cancel()
            logsViewModel.cancelExport(showResult = false)
        }
    )

    DiagnosticLogsContent(
        state = DiagnosticLogsContentState(
            state.enabled,
            state.stats,
            state.exporting,
            state.statusMessage
        ),
        events = state.events,
        actions = DiagnosticLogsContentActions(
            onBack = onBack,
            onEnabledChange = { checked ->
                if (checked && !isDebugBuild) {
                    localState.showEnableConfirmation = true
                } else {
                    logsViewModel.setEnabled(checked, userConfirmed = isDebugBuild)
                }
            },
            onRefresh = logsViewModel::refresh,
            onExportClick = logsViewModel::export,
            onClearClick = { localState.showClearConfirmation = true }
        ),
        modifier = modifier
    )

    DiagnosticLogsDialogs(localState, state, logsViewModel)
}

@Composable
private fun DiagnosticLogsDialogs(
    localState: DiagnosticLogsLocalState,
    state: DiagnosticLogsUiState,
    viewModel: DiagnosticLogsViewModel
) {
    if (localState.showEnableConfirmation) {
        EnableDiagnosticsConfirmationDialog(
            onConfirm = {
                viewModel.setEnabled(true, userConfirmed = true)
                localState.showEnableConfirmation = false
            },
            onDismiss = { localState.showEnableConfirmation = false }
        )
    }
    if (localState.showClearConfirmation) {
        ClearDiagnosticsConfirmationDialog(
            onConfirm = {
                localState.showClearConfirmation = false
                viewModel.clear()
            },
            onDismiss = { localState.showClearConfirmation = false }
        )
    }
    state.exportResult?.let { result ->
        ExportResultDialog(result.title, result.message) {
            viewModel.dismissExportResult(result.id)
        }
    }
}

@Composable
private fun DiagnosticLogsEffects(
    lifecycleOwner: LifecycleOwner,
    applySecureWindowFlag: Boolean,
    onBackground: () -> Unit,
    onDisposeScreen: () -> Unit
) {
    val currentOnBackground by rememberUpdatedState(onBackground)
    val currentOnDisposeScreen by rememberUpdatedState(onDisposeScreen)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentOnBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnDisposeScreen()
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
