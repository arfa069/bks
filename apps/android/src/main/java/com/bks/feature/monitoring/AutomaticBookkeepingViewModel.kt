package com.bks.feature.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class AutomaticBookkeepingFeatureState(
    val enabled: Boolean = false,
    val writeInProgress: Boolean = false
)

internal class AutomaticBookkeepingViewModel(
    enabled: Flow<Boolean>,
    private val persistEnabled: suspend (Boolean) -> Unit
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutomaticBookkeepingFeatureState())
    val uiState: StateFlow<AutomaticBookkeepingFeatureState> = mutableState.asStateFlow()

    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    private var persistedEnabled = false
    private var pendingTarget: Boolean? = null

    init {
        viewModelScope.launch {
            enabled.collect { persisted ->
                persistedEnabled = persisted
                if (pendingTarget == persisted) pendingTarget = null
                mutableState.value = AutomaticBookkeepingFeatureState(
                    enabled = pendingTarget ?: persisted,
                    writeInProgress = pendingTarget != null
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (pendingTarget != null || enabled == mutableState.value.enabled) return
        pendingTarget = enabled
        mutableState.value = AutomaticBookkeepingFeatureState(
            enabled = enabled,
            writeInProgress = true
        )
        viewModelScope.launch {
            try {
                persistEnabled(enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (pendingTarget == enabled) {
                    pendingTarget = null
                    mutableState.value = AutomaticBookkeepingFeatureState(
                        enabled = persistedEnabled,
                        writeInProgress = false
                    )
                    noticeChannel.send("保存自动记账设置失败，请重试")
                }
            }
        }
    }
}
