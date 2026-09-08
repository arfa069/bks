package com.bks.model.platform.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bks.model.platform.app.bksAppContainer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class BacklogReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        applicationContext.bksAppContainer.attentionReminderCoordinator.refreshBacklog(allowAlert = true)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

internal object BacklogReminderScheduler {
    const val WORK_NAME = "pending-backlog-reminder-v1"

    fun setEnabled(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (enabled) {
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BacklogReminderWorker>(1, TimeUnit.HOURS)
                    .setInitialDelay(1, TimeUnit.HOURS).build())
        } else {
            manager.cancelUniqueWork(WORK_NAME)
        }
    }
}
