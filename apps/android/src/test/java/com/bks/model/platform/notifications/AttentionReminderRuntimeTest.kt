package com.bks.model.platform.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.notifications.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AttentionReminderRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = RuntimePreferences()
    private val repository = ReminderTestRepository()
    private val health = MutableStateFlow(AutomaticBookkeepingHealth(true, true, true))
    private val store = RuntimeStateStore()
    private val schedules = mutableListOf<Boolean>()

    private fun TestScope.startRuntime(): AttentionReminderRuntime {
        shadowOf(manager).setNotificationsEnabled(true)
        return AttentionReminderRuntime(backgroundScope, health, preferences, repository,
            AttentionReminderCoordinator(repository, preferences, store, AttentionNotificationPublisher(context))
        ) { schedules += it }.also { it.start() }
    }

    @Test
    fun transientDisconnectDoesNotAlertAndStableRecoveryRearms() = runTest {
        val runtime = startRuntime()
        runtime.start()
        runCurrent()
        assertEquals(listOf(false), schedules)
        health.value = AutomaticBookkeepingHealth(true, true, false)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        health.value = AutomaticBookkeepingHealth(true, true, true)
        runCurrent()
        advanceTimeBy(5_001)
        health.value = AutomaticBookkeepingHealth(true, false, false)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(store.value.interruptionNotified)
        health.value = AutomaticBookkeepingHealth(true, true, true)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(store.value.interruptionNotified)
    }

    @Test
    fun lockScreenChangeReconcilesImmediatelyWithoutRealerting() = runTest {
        health.value = AutomaticBookkeepingHealth(true, true, false)
        startRuntime()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, manager.activeNotifications.size)
        preferences.setHideDetailsOnLockScreen(false)
        runCurrent()
        assertEquals(Notification.VISIBILITY_PUBLIC, manager.activeNotifications.single().notification.visibility)
        preferences.setHideDetailsOnLockScreen(true)
        runCurrent()
        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun userDisablingDuringGraceCancelsAndDoesNotModifyHealth() = runTest {
        health.value = AutomaticBookkeepingHealth(true, false, false)
        startRuntime()
        runCurrent()
        advanceTimeBy(20_000)
        preferences.setInterruptionEnabled(false)
        runCurrent()
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        assertTrue(health.value.enabled)
        preferences.setBacklogEnabled(true)
        runCurrent()
        preferences.setBacklogEnabled(false)
        runCurrent()
        assertEquals(listOf(false, true, false), schedules)
    }

    @Test
    fun shortReconnectAndRuntimeRestartDoNotRepeatSameIncident() = runTest {
        store.value = AttentionReminderState(interruptionNotified = true)
        health.value = AutomaticBookkeepingHealth(true, false, false)
        startRuntime()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        health.value = AutomaticBookkeepingHealth(true, true, true)
        runCurrent()
        advanceTimeBy(1_000)
        health.value = AutomaticBookkeepingHealth(true, true, false)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        assertTrue(store.value.interruptionNotified)
        health.value = AutomaticBookkeepingHealth(false, false, false)
        runCurrent()
        assertFalse(store.value.interruptionNotified)
    }

    private class RuntimePreferences : NotificationPreferencesRepository {
        override val state = MutableStateFlow(NotificationPreferences())
        override suspend fun setEnabled(enabled: Boolean) { state.value = state.value.copy(enabled = enabled) }
        override suspend fun setHideDetailsOnLockScreen(hide: Boolean) { state.value = state.value.copy(hideDetailsOnLockScreen = hide) }
        override suspend fun setInterruptionEnabled(enabled: Boolean) { state.value = state.value.copy(interruptionEnabled = enabled) }
        override suspend fun setBacklogEnabled(enabled: Boolean) { state.value = state.value.copy(backlogEnabled = enabled) }
    }

    private class RuntimeStateStore : AttentionReminderStateRepository {
        var value = AttentionReminderState()
        override suspend fun load() = value
        override suspend fun save(state: AttentionReminderState) { value = state }
    }
}
