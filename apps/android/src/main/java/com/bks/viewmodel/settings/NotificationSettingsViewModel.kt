package com.bks.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class NotificationSettingsViewModel(
    private val repository: NotificationPreferencesRepository
) : ViewModel() {
    val state = repository.state
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()

    fun setEnabled(enabled: Boolean) = update { repository.setEnabled(enabled) }
    fun setHideDetails(hide: Boolean) = update { repository.setHideDetailsOnLockScreen(hide) }
    fun setInterruptionEnabled(enabled: Boolean) = update { repository.setInterruptionEnabled(enabled) }
    fun setBacklogEnabled(enabled: Boolean) = update { repository.setBacklogEnabled(enabled) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                noticeChannel.send("通知设置保存失败，请重试")
            }
        }
    }
}
