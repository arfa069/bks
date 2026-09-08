package com.bks.model.platform.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BacklogReminderSchedulerTest {
    @Test
    fun repeatedStartKeepsOneJobAndOptOutCancelsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val manager = WorkManager.getInstance(context)
        BacklogReminderScheduler.setEnabled(context, true)
        val first = manager.getWorkInfosForUniqueWork(BacklogReminderScheduler.WORK_NAME).get().single()
        BacklogReminderScheduler.setEnabled(context, true)
        val second = manager.getWorkInfosForUniqueWork(BacklogReminderScheduler.WORK_NAME).get().single()
        assertEquals(first.id, second.id)
        assertEquals(WorkInfo.State.ENQUEUED, second.state)
        BacklogReminderScheduler.setEnabled(context, false)
        assertEquals(WorkInfo.State.CANCELLED,
            manager.getWorkInfosForUniqueWork(BacklogReminderScheduler.WORK_NAME).get().single().state)
    }
}
