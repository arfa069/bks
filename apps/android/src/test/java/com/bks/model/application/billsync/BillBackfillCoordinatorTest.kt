package com.bks.model.application.billsync

import com.bks.model.domain.billsync.BACKFILL_DETAIL
import com.bks.model.domain.billsync.BillBackfillParser
import com.bks.model.domain.billsync.BillBackfillPhase
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillBackfillCoordinatorTest {
    private val entry = requireNotNull(BillBackfillParser().parse(BACKFILL_DETAIL, PaymentSource.ALIPAY, 1))

    @Test fun requiresLiveServiceAndRejectsWrongSourceStaleAndDuplicateCaptures() = runTest {
        var writes = 0
        val coordinator = BillBackfillCoordinator(backgroundScope, { writes++; it.copy(insertedCount = 1) })
        coordinator.start(PaymentSource.ALIPAY)
        assertFalse(coordinator.state.value.isRunning)
        coordinator.serviceConnectionChanged(true)
        coordinator.start(PaymentSource.ALIPAY)
        val id = coordinator.state.value.sessionId
        coordinator.submit(id, entry.copy(source = PaymentSource.WECHAT), false)
        coordinator.submit(id, entry, false)
        coordinator.submit(id, entry, false)
        assertEquals(1, writes)
        assertEquals(1, coordinator.state.value.insertedCount)
        coordinator.finish()
        coordinator.start(PaymentSource.ALIPAY)
        coordinator.submit(id, entry, true)
        assertEquals(1, writes)
    }

    @Test fun stopAndCaptureCancellationWaitForAdmittedWriteWithoutLosingItsCount() = runTest {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val coordinator = BillBackfillCoordinator(backgroundScope, {
            started.complete(Unit); released.await(); it.copy(insertedCount = 1)
        })
        coordinator.serviceConnectionChanged(true)
        coordinator.start(PaymentSource.ALIPAY)
        val id = coordinator.state.value.sessionId
        val writing = launch { coordinator.submit(id, entry, false) }
        started.await()
        writing.cancel()
        val finishing = launch { coordinator.finish() }
        runCurrent()
        assertFalse(coordinator.accepts(id, PaymentSource.ALIPAY))
        assertFalse(finishing.isCompleted)
        assertFalse(writing.isCompleted)
        released.complete(Unit)
        writing.join(); finishing.join()
        assertEquals(BillBackfillPhase.Finished, coordinator.state.value.phase)
        assertEquals(1, coordinator.state.value.insertedCount)
    }

    @Test fun serviceLossAndTimeoutEndSessionButNeverAutoRestart() = runTest {
        val coordinator = BillBackfillCoordinator(backgroundScope, { it }, { testScheduler.currentTime }, 2_000)
        coordinator.serviceConnectionChanged(true)
        coordinator.start(PaymentSource.ALIPAY)
        coordinator.serviceConnectionChanged(false)
        runCurrent()
        assertEquals(BillBackfillPhase.Finished, coordinator.state.value.phase)
        coordinator.serviceConnectionChanged(true)
        assertFalse(coordinator.state.value.isRunning)
        coordinator.start(PaymentSource.WECHAT)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(BillBackfillPhase.Finished, coordinator.state.value.phase)
    }

    @Test fun failedWriteCanRetryAndPersistedDuplicateIsReported() = runTest {
        var fail = true
        val coordinator = BillBackfillCoordinator(backgroundScope, { if (fail) error("test") else it })
        coordinator.serviceConnectionChanged(true)
        coordinator.start(PaymentSource.ALIPAY)
        val id = coordinator.state.value.sessionId
        coordinator.submit(id, entry, false)
        assertTrue(coordinator.state.value.message.contains("保存失败"))
        fail = false
        coordinator.submit(id, entry, true)
        assertEquals(1, coordinator.state.value.duplicateCount)
        assertEquals(1, coordinator.state.value.ocrCount)
    }
}
