package com.bks.model.platform.sync

import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.application.sync.LedgerSyncSchedulingGateway
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.data.local.account.SecureAccountSessionStore
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
import com.bks.model.platform.app.bksAppContainer
import com.bks.model.domain.account.AccountSessionRestoreResult
import java.util.concurrent.TimeUnit

class LedgerSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.bksAppContainer
        return when (runLedgerSyncWorkerInvocation(
            restoreSession = container.secureAccountSessionStore::restore,
            syncEnabled = { container.ledgerSyncLocalStore.currentState().enabled },
            synchronize = container.ledgerSyncCoordinator::synchronize
        )) {
            LedgerSyncWorkerOutcome.Success -> Result.success()
            LedgerSyncWorkerOutcome.Retry -> Result.retry()
            LedgerSyncWorkerOutcome.Failure -> Result.failure()
        }
    }
}

internal enum class LedgerSyncWorkerOutcome {
    Success,
    Retry,
    Failure
}

internal suspend fun runLedgerSyncWorkerInvocation(
    restoreSession: () -> AccountSessionRestoreResult,
    syncEnabled: suspend () -> Boolean,
    synchronize: suspend (String) -> LedgerSyncOperationResult<Unit>
): LedgerSyncWorkerOutcome {
    val restored = restoreSession() as? AccountSessionRestoreResult.Restored
        ?: return LedgerSyncWorkerOutcome.Success
    if (!syncEnabled()) return LedgerSyncWorkerOutcome.Success
    return when (val result = synchronize(restored.credentials.token)) {
        is LedgerSyncOperationResult.Success -> LedgerSyncWorkerOutcome.Success
        is LedgerSyncOperationResult.Failure -> {
            if (result.retryable) LedgerSyncWorkerOutcome.Retry else LedgerSyncWorkerOutcome.Failure
        }
    }
}

internal class AndroidLedgerSyncSchedulingGateway(context: Context) : LedgerSyncSchedulingGateway {
    private val applicationContext = context.applicationContext

    override fun enqueueNow() = LedgerSyncScheduler.enqueueNow(applicationContext)

    override fun ensurePeriodic() = LedgerSyncScheduler.ensurePeriodic(applicationContext)

    override fun cancel() = LedgerSyncScheduler.cancel(applicationContext)

    override fun cancelLegacy() {
        try {
            LedgerSyncScheduler.cancelLegacy(applicationContext)
        } catch (_: IllegalStateException) {
            // WorkManager is not available during early Robolectric application startup.
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

    fun cancelLegacy(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(LEGACY_LEDGER_SYNC_NOW)
        manager.cancelUniqueWork(LEGACY_LEDGER_SYNC_PERIODIC)
    }

    internal const val LEDGER_SYNC_NOW = "account-ledger-sync-now-v2"
    internal const val LEDGER_SYNC_PERIODIC = "account-ledger-sync-periodic-v2"
    internal const val LEGACY_LEDGER_SYNC_NOW = "account-ledger-sync-now"
    internal const val LEGACY_LEDGER_SYNC_PERIODIC = "account-ledger-sync-periodic"
}
