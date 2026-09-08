package com.bks.model.domain.notifications

import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.ledger.PaymentSource
import org.junit.Assert.*
import org.junit.Test

class BacklogReminderPolicyTest {
    private val entry = BillSyncPipeline().sync("支付成功\n￥12.00\n商户：测试商户", 0, PaymentSource.ALIPAY).createdEntries.single()

    @Test
    fun thresholdUsesCaptureAgeAndCountNotPaymentDate() {
        assertFalse(BacklogReminderPolicy.qualifies(emptyList(), Long.MAX_VALUE))
        assertFalse(BacklogReminderPolicy.qualifies(listOf(entry), BacklogReminderPolicy.MIN_AGE_MILLIS - 1))
        assertTrue(BacklogReminderPolicy.qualifies(listOf(entry), BacklogReminderPolicy.MIN_AGE_MILLIS))
        assertTrue(BacklogReminderPolicy.qualifies(List(10) { entry.copy(id = "$it") }, 0))
        assertFalse(BacklogReminderPolicy.qualifies(listOf(entry.copy(capturedAtEpochMillis = 1_000)), 0))
    }

    @Test
    fun unchangedQueueAndClockRollbackCannotBypassCooldown() {
        val state = AttentionReminderState(lastBacklogAt = 100, lastBacklogFingerprint = "same")
        assertFalse(BacklogReminderPolicy.canAlert("same", state, Long.MAX_VALUE))
        assertFalse(BacklogReminderPolicy.canAlert("other", state, 0))
        assertFalse(BacklogReminderPolicy.canAlert("other", state, BacklogReminderPolicy.COOLDOWN_MILLIS))
        assertTrue(BacklogReminderPolicy.canAlert("other", state, 100 + BacklogReminderPolicy.COOLDOWN_MILLIS))
    }

    @Test
    fun reorderingAndCategoryBackfillDoNotCreateAnotherBacklog() {
        val entries = listOf(entry, entry.copy(id = "two"))
        assertEquals(BacklogReminderPolicy.fingerprint(entries), BacklogReminderPolicy.fingerprint(entries.reversed()))
        assertEquals(BacklogReminderPolicy.fingerprint(entries), BacklogReminderPolicy.fingerprint(entries.map {
            it.copy(suggestedCategoryLabel = "日用", merchantTitle = "新商户")
        }))
        assertNotEquals(BacklogReminderPolicy.fingerprint(entries), BacklogReminderPolicy.fingerprint(entries.drop(1)))
    }

    @Test
    fun disabledUserIntentIsNotAnOutageAndPermissionHasPriority() {
        assertNull(AutomaticBookkeepingHealth(false, false, false).reason)
        assertNull(AutomaticBookkeepingHealth(true, true, true).reason)
        assertEquals(BookkeepingInterruption.PermissionMissing, AutomaticBookkeepingHealth(true, false, false).reason)
        assertEquals(BookkeepingInterruption.ServiceDisconnected, AutomaticBookkeepingHealth(true, true, false).reason)
    }
}
