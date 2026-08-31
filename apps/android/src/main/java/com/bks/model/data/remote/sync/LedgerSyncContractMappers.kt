package com.bks.model.data.remote.sync

import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.model.domain.sync.LedgerSyncConflictChoice
import com.bks.model.domain.sync.LedgerSyncEntityType
import com.bks.model.domain.sync.LedgerSyncPayload

internal fun LedgerSyncEntityTypeContract.toDomain() = LedgerSyncEntityType.valueOf(name)
internal fun LedgerSyncEntityType.toContract() = LedgerSyncEntityTypeContract.valueOf(name)
internal fun LedgerSyncConflictChoice.toContract() = LedgerSyncConflictChoiceContract.valueOf(name)

internal fun LedgerSyncPayloadContract.toDomain(): LedgerSyncPayload = when (this) {
    is LedgerSyncPayloadContract.Category -> LedgerSyncPayload.Category(id, name, kind, sortOrder, isSystem, createdAtMillis)
    is LedgerSyncPayloadContract.FundingAccount ->
        LedgerSyncPayload.FundingAccount(syncId, sourceScope, paymentSource, label, createdAtMillis)
    is LedgerSyncPayloadContract.LedgerBook -> LedgerSyncPayload.LedgerBook(id, name, createdAtMillis)
    is LedgerSyncPayloadContract.LedgerEntry -> LedgerSyncPayload.LedgerEntry(
        id, ledgerBookId, paymentSource, originalCaptureSource, entryOrigin, flowDirection,
        transactionKind, amountMinor, currency, merchantTitle, transactionTimeMillis,
        categoryId, fundingAccountSyncId, note, confirmedAtMillis, updatedAtMillis, deletedAtMillis
    )
    is LedgerSyncPayloadContract.CategorizationRule -> LedgerSyncPayload.CategorizationRule(
        id, merchantContains, titleContains, sourceLabel, transactionKind, category,
        priority, enabled, updatedAtMillis
    )
}

internal fun LedgerSyncPayload.toContract(): LedgerSyncPayloadContract = when (this) {
    is LedgerSyncPayload.Category -> LedgerSyncPayloadContract.Category(id, name, kind, sortOrder, isSystem, createdAtMillis)
    is LedgerSyncPayload.FundingAccount ->
        LedgerSyncPayloadContract.FundingAccount(syncId, sourceScope, paymentSource, label, createdAtMillis)
    is LedgerSyncPayload.LedgerBook -> LedgerSyncPayloadContract.LedgerBook(id, name, createdAtMillis)
    is LedgerSyncPayload.LedgerEntry -> LedgerSyncPayloadContract.LedgerEntry(
        id, ledgerBookId, paymentSource, originalCaptureSource, entryOrigin, flowDirection,
        transactionKind, amountMinor, currency, merchantTitle, transactionTimeMillis,
        categoryId, fundingAccountSyncId, note, confirmedAtMillis, updatedAtMillis, deletedAtMillis
    )
    is LedgerSyncPayload.CategorizationRule -> LedgerSyncPayloadContract.CategorizationRule(
        id, merchantContains, titleContains, sourceLabel, transactionKind, category,
        priority, enabled, updatedAtMillis
    )
}
