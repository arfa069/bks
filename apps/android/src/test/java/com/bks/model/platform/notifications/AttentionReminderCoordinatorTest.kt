package com.bks.model.platform.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.settings.LocalAttentionReminderState
import com.bks.model.data.local.settings.LocalNotificationPreferences
import com.bks.model.domain.notifications.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AttentionReminderCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = LocalNotificationPreferences(context)
    private val state = LocalAttentionReminderState(context)
    private val repository = ReminderTestRepository()
    private var now = BacklogReminderPolicy.MIN_AGE_MILLIS
    private fun coordinator() = AttentionReminderCoordinator(repository, preferences, state, AttentionNotificationPublisher(context)) { now }
    private val disconnected = AutomaticBookkeepingHealth(true, true, false)

    @Before fun allowNotifications() { shadowOf(manager).setNotificationsEnabled(true) }

    @Test
    fun incidentPersistsAcrossRestartAndDismissalUntilRecovery() = runBlocking {
        coordinator().remindInterruption { disconnected }
        assertEquals(1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("服务"))
        manager.cancelAll()
        coordinator().remindInterruption { disconnected }
        assertEquals(0, manager.activeNotifications.size)
        coordinator().clearInterruption(resetIncident = true)
        coordinator().remindInterruption { disconnected }
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun denialOrSeparateSwitchDoesNotConsumeIncidentOrChangeCapturePreference() = runBlocking {
        preferences.setEnabled(false) // Only the stage-one reminder switch.
        preferences.setInterruptionEnabled(false)
        coordinator().remindInterruption { disconnected }
        assertFalse(state.load().interruptionNotified)
        preferences.setInterruptionEnabled(true)
        shadowOf(manager).setNotificationsEnabled(false)
        coordinator().remindInterruption { disconnected }
        assertFalse(state.load().interruptionNotified)
        shadowOf(manager).setNotificationsEnabled(true)
        coordinator().remindInterruption { disconnected }
        assertTrue(state.load().interruptionNotified)
        assertFalse(preferences.state.value.enabled)
    }

    @Test
    fun blockedInterruptionChannelDoesNotDisableBacklogOrReserveIncident() = runBlocking {
        manager.createNotificationChannel(NotificationChannel(AttentionNotificationPublisher.INTERRUPTION_CHANNEL,
            "中断", NotificationManager.IMPORTANCE_NONE))
        coordinator().remindInterruption { disconnected }
        assertFalse(state.load().interruptionNotified)
        preferences.setBacklogEnabled(true)
        repository.setEntries(listOf(reminderEntry("old")))
        coordinator().refreshBacklog(true)
        assertEquals(AttentionNotificationPublisher.BACKLOG_TAG, manager.activeNotifications.single().tag)
    }

    @Test
    fun startupAndRestoreOnlyReconcileWhileScheduledCheckCanAlert() = runBlocking {
        preferences.setBacklogEnabled(true)
        val first = reminderEntry("old")
        repository.setEntries(listOf(first))
        coordinator().refreshBacklog(allowAlert = false)
        assertEquals(0, manager.activeNotifications.size)
        coordinator().refreshBacklog(allowAlert = true)
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        now += BacklogReminderPolicy.COOLDOWN_MILLIS
        coordinator().refreshBacklog(allowAlert = true)
        assertEquals(0, manager.activeNotifications.size) // unchanged after process recreation
        repository.setEntries(listOf(first, reminderEntry("new")))
        coordinator().refreshBacklog(allowAlert = true)
        assertEquals(1, manager.activeNotifications.size)
        repository.setEntries(emptyList())
        coordinator().refreshBacklog(allowAlert = false)
        assertEquals(0, manager.activeNotifications.size)
        repository.setEntries(listOf(first, reminderEntry("new")))
        now += BacklogReminderPolicy.COOLDOWN_MILLIS
        coordinator().refreshBacklog(allowAlert = true)
        assertEquals(0, manager.activeNotifications.size) // restoring identical records doesn't rearm
    }

    @Test
    fun differentQueueWithinCooldownUpdatesSilentlyAndSwitchOffCancels() = runBlocking {
        preferences.setBacklogEnabled(true)
        repository.setEntries(listOf(reminderEntry("one")))
        coordinator().refreshBacklog(true)
        repository.setEntries(listOf(reminderEntry("one"), reminderEntry("two")))
        now += 1_000
        coordinator().refreshBacklog(true)
        val notification = manager.activeNotifications.single().notification
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("2 笔记录待确认", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        preferences.setBacklogEnabled(false)
        coordinator().refreshBacklog(true)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun confirmationDuringReservationPreventsStaleBacklogNotification() = runBlocking {
        preferences.setBacklogEnabled(true)
        repository.setEntries(listOf(reminderEntry("one")))
        val clearingStore = object : AttentionReminderStateRepository {
            override suspend fun load() = state.load()
            override suspend fun save(state: AttentionReminderState) {
                this@AttentionReminderCoordinatorTest.state.save(state)
                repository.setEntries(emptyList())
            }
        }
        AttentionReminderCoordinator(repository, preferences, clearingStore, AttentionNotificationPublisher(context)) { now }
            .refreshBacklog(true)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun settingsAndCooldownSurviveRecreation() = runBlocking {
        preferences.setBacklogEnabled(true)
        preferences.setInterruptionEnabled(false)
        state.save(AttentionReminderState(true, 123, "opaque"))
        assertEquals(preferences.state.value, LocalNotificationPreferences(context).state.value)
        assertEquals(AttentionReminderState(true, 123, "opaque"), LocalAttentionReminderState(context).load())
    }

    @Test
    fun explicitImmutableClickOnlyOpensBookkeepingAndConsumesOnce() {
        val pending = requireNotNull(AttentionNotificationIntents.contentIntent(context))
        assertTrue(pending.isImmutable)
        val intent = shadowOf(pending).savedIntent
        assertNotNull(intent.component)
        assertTrue(AttentionNotificationIntents.consume(context, intent))
        assertFalse(AttentionNotificationIntents.consume(context, intent))
        assertFalse(AttentionNotificationIntents.consume(context,
            Intent("${context.packageName}.action.OPEN_AUTOMATIC_BOOKKEEPING", Uri.parse("https://example.com"))))
    }
}
