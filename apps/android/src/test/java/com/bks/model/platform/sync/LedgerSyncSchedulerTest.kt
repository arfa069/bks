package com.bks.model.platform.sync

import com.bks.model.platform.sync.LedgerSyncScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerSyncSchedulerTest {
    @Test
    fun repeatedSchedulingKeepsOneImmediateAndOnePeriodicWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniqueWork(
            LedgerSyncScheduler.LEGACY_LEDGER_SYNC_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LedgerSyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
        ).result.get()
        workManager.enqueueUniqueWork(
            LedgerSyncScheduler.LEGACY_LEDGER_SYNC_PERIODIC,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LedgerSyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
        ).result.get()

        LedgerSyncScheduler.cancelLegacy(context)
        LedgerSyncScheduler.enqueueNow(context)
        LedgerSyncScheduler.enqueueNow(context)
        LedgerSyncScheduler.ensurePeriodic(context)
        LedgerSyncScheduler.ensurePeriodic(context)

        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(LedgerSyncScheduler.LEDGER_SYNC_NOW).get().size
        )
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(LedgerSyncScheduler.LEDGER_SYNC_PERIODIC).get().size
        )
        assertEquals("account-ledger-sync-now-v2", LedgerSyncScheduler.LEDGER_SYNC_NOW)
        assertEquals("account-ledger-sync-periodic-v2", LedgerSyncScheduler.LEDGER_SYNC_PERIODIC)
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(
                LedgerSyncScheduler.LEGACY_LEDGER_SYNC_NOW
            ).get().single().state
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(
                LedgerSyncScheduler.LEGACY_LEDGER_SYNC_PERIODIC
            ).get().single().state
        )
    }
}
