package com.bks.viewmodel.ledger

import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind

enum class LedgerFlowType {
    EXPENSE,
    INCOME,
    NEUTRAL
}

data class LedgerEntryUiModel(
    val id: String,
    val title: String,
    val amountMinor: Long,
    val monthKey: String,
    val transactionTimeText: String,
    val category: String,
    val sourceLabel: String,
    val kindLabel: String,
    val flowType: LedgerFlowType,
    val note: String? = null,
    val paymentSource: PaymentSource? = null,
    val originalCaptureSource: PaymentSource? = null,
    val entryOrigin: EntryOrigin = EntryOrigin.LEGACY_CAPTURE,
    val originPendingEntryId: String? = null,
    val flowDirection: FlowDirection = FlowDirection.OUTFLOW,
    val transactionKind: TransactionKind = TransactionKind.OTHER,
    val transactionTimeEpochMillis: Long = 0,
    val categoryId: String? = null,
    val fundingAccountId: Long? = null,
    val evidenceSummary: String? = null,
    val parsedFieldsText: String? = null,
    val confirmedAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val deletedAtEpochMillis: Long? = null
)
