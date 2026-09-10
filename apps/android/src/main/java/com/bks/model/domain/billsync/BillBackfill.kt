package com.bks.model.domain.billsync

import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.flow.StateFlow

enum class BillBackfillPhase { Idle, Running, Finishing, Finished }

data class BillBackfillState(
    val sessionId: Long = 0,
    val source: PaymentSource = PaymentSource.ALIPAY,
    val phase: BillBackfillPhase = BillBackfillPhase.Idle,
    val serviceConnected: Boolean = false,
    val insertedCount: Int = 0,
    val duplicateCount: Int = 0,
    val ocrCount: Int = 0,
    val message: String = "",
    val expiresAtMillis: Long = 0
) {
    val isRunning: Boolean get() = phase == BillBackfillPhase.Running
    val isBusy: Boolean get() = isRunning || phase == BillBackfillPhase.Finishing
}

interface BillBackfillGateway {
    val state: StateFlow<BillBackfillState>
    suspend fun start(source: PaymentSource)
    suspend fun finish()
}
