package com.bks.feature.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bks.bksAppContainer
import com.bks.feature.account.AccountSessionRestoreResult
import java.util.concurrent.TimeUnit

class LedgerSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.bksAppContainer
        val restored = container.secureAccountSessionStore.restore()
            as? AccountSessionRestoreResult.Restored
            ?: return Result.success()
        val local = container.ledgerSyncLocalStore
        if (!local.currentState().enabled) return Result.success()
        return when (
            val result = container.createLedgerSyncCoordinator().synchronize(restored.credentials.token)
        ) {
            is LedgerSyncOperationResult.Success -> Result.success()
            is LedgerSyncOperationResult.Failure -> if (result.retryable) Result.retry() else Result.failure()
        }
    }
}

object LedgerSyncScheduler {
    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            LEDGER_SYNC_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LedgerSyncWorker>()
                .setConstraints(networkConstraints)
                .build()
        )
    }

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            LEDGER_SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LedgerSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(LEDGER_SYNC_NOW)
        manager.cancelUniqueWork(LEDGER_SYNC_PERIODIC)
    }

    internal const val LEDGER_SYNC_NOW = "account-ledger-sync-now"
    internal const val LEDGER_SYNC_PERIODIC = "account-ledger-sync-periodic"
}
