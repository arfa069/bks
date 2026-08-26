package com.bks.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DiagnosticExportResult(
    val id: Long,
    val title: String,
    val message: String
)

internal data class DiagnosticLogsUiState(
    val enabled: Boolean = false,
    val events: List<DiagnosticEvent> = emptyList(),
    val stats: DiagnosticLogStats = DiagnosticLogStats(),
    val refreshing: Boolean = false,
    val clearing: Boolean = false,
    val exporting: Boolean = false,
    val statusMessage: String? = null,
    val exportResult: DiagnosticExportResult? = null
)

internal data class DiagnosticExportWriteRequest(
    val requestId: Long,
    val plainText: String
)

internal class DiagnosticLogsViewModel(
    private val repository: DiagnosticLogRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        DiagnosticLogsUiState(
            enabled = repository.enabled.value,
            events = repository.events.value,
            stats = repository.stats.value
        )
    )
    val uiState = mutableUiState.asStateFlow()

    private val writeRequestChannel = Channel<DiagnosticExportWriteRequest>()
    val writeRequests = writeRequestChannel.receiveAsFlow()

    private var nextId = 0L
    private var exportJob: Job? = null
    private var activeExportRequestId: Long? = null

    init {
        viewModelScope.launch {
            repository.enabled.collect { enabled ->
                mutableUiState.update { it.copy(enabled = enabled) }
            }
        }
        viewModelScope.launch {
            repository.events.collect { events ->
                mutableUiState.update { it.copy(events = events) }
            }
        }
        viewModelScope.launch {
            repository.stats.collect { stats ->
                mutableUiState.update { it.copy(stats = stats) }
            }
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean, userConfirmed: Boolean = false): Boolean =
        repository.setEnabled(enabled, userConfirmed)

    fun refresh() {
        if (mutableUiState.value.refreshing) return
        mutableUiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                repository.refresh()
                mutableUiState.update { state ->
                    if (state.statusMessage == REFRESH_FAILURE_MESSAGE) {
                        state.copy(statusMessage = null)
                    } else {
                        state
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableUiState.update { it.copy(statusMessage = REFRESH_FAILURE_MESSAGE) }
            } finally {
                mutableUiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun clear() {
        if (mutableUiState.value.clearing) return
        mutableUiState.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                repository.clear()
                mutableUiState.update {
                    it.copy(statusMessage = "诊断日志已清空。")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableUiState.update { it.copy(statusMessage = "清空失败，请稍后重试。") }
            } finally {
                mutableUiState.update { it.copy(clearing = false) }
            }
        }
    }

    fun export() {
        if (activeExportRequestId != null) return
        val requestId = ++nextId
        activeExportRequestId = requestId
        mutableUiState.update { it.copy(exporting = true, exportResult = null) }
        exportJob = viewModelScope.launch {
            try {
                val plainText = repository.exportPlainText()
                if (activeExportRequestId == requestId) {
                    writeRequestChannel.send(DiagnosticExportWriteRequest(requestId, plainText))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (activeExportRequestId == requestId) {
                    finishExport(
                        title = "导出失败",
                        message = "导出失败，请确认本机存储状态后重试。"
                    )
                }
            } finally {
                if (activeExportRequestId == requestId) exportJob = null
            }
        }
    }

    fun beginWrite(requestId: Long): Boolean = activeExportRequestId == requestId

    fun finishWrite(requestId: Long, displayName: String?) {
        if (activeExportRequestId != requestId) return
        if (displayName == null) {
            finishExport(
                title = "导出失败",
                message = "导出失败，请确认本机存储状态后重试。"
            )
        } else {
            val message = "已导出 $displayName 到 Downloads；删除本机数据不会删除该文件，请自行保管或删除。"
            finishExport("导出完成", message)
        }
    }

    fun cancelExport(showResult: Boolean = true) {
        if (activeExportRequestId == null) return
        activeExportRequestId = null
        exportJob?.cancel()
        exportJob = null
        mutableUiState.update {
            it.copy(
                exporting = false,
                exportResult = if (showResult) {
                    DiagnosticExportResult(
                        id = ++nextId,
                        title = "导出已取消",
                        message = "未创建诊断日志导出文件。"
                    )
                } else {
                    null
                }
            )
        }
    }

    fun dismissExportResult(id: Long) {
        mutableUiState.update { state ->
            if (state.exportResult?.id == id) state.copy(exportResult = null) else state
        }
    }

    override fun onCleared() {
        activeExportRequestId = null
        exportJob?.cancel()
        exportJob = null
        writeRequestChannel.close()
        mutableUiState.update {
            it.copy(refreshing = false, clearing = false, exporting = false, exportResult = null)
        }
        super.onCleared()
    }

    private fun finishExport(title: String, message: String) {
        activeExportRequestId = null
        exportJob = null
        mutableUiState.update {
            it.copy(
                exporting = false,
                statusMessage = message,
                exportResult = DiagnosticExportResult(++nextId, title, message)
            )
        }
    }

    private companion object {
        const val REFRESH_FAILURE_MESSAGE = "刷新失败，请稍后重试。"
    }
}
