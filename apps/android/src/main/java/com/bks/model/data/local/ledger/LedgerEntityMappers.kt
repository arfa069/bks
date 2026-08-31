package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.CategoryEntity
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LedgerBookEntryCounts
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.FundingAccount
import com.bks.model.domain.ledger.IgnoredEntry
import com.bks.model.domain.ledger.LedgerBook
import com.bks.model.domain.ledger.LedgerBookSummary
import com.bks.model.domain.ledger.LedgerEntry
import com.bks.model.domain.ledger.PendingEntry

internal fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    kind = kind,
    sortOrder = sortOrder,
    isSystem = isSystem,
    createdAtEpochMillis = createdAtEpochMillis
)

internal fun Category.toEntity() = CategoryEntity(
    id = id,
    name = name,
    kind = kind,
    sortOrder = sortOrder,
    isSystem = isSystem,
    createdAtEpochMillis = createdAtEpochMillis
)

internal fun FundingAccountEntity.toDomain() = FundingAccount(
    id = id,
    syncId = syncId,
    sourceScope = sourceScope,
    paymentSource = paymentSource,
    label = label,
    createdAtEpochMillis = createdAtEpochMillis
)

internal fun LedgerBookEntity.toDomain() = LedgerBook(id, name, createdAtEpochMillis)

internal fun LedgerBookEntryCounts.toDomain() = LedgerBookSummary(
    id = id,
    name = name,
    activeEntryCount = activeEntryCount,
    deletedEntryCount = deletedEntryCount
)

internal fun LedgerEntryEntity.toDomain() = LedgerEntry(
    id = id,
    ledgerBookId = ledgerBookId,
    paymentSource = paymentSource,
    originalCaptureSource = originalCaptureSource,
    entryOrigin = entryOrigin,
    originPendingEntryId = originPendingEntryId,
    flowDirection = flowDirection,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    categoryId = categoryId,
    fundingAccountId = fundingAccountId,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    deletedAtEpochMillis = deletedAtEpochMillis
)

internal fun PendingEntryEntity.toDomain() = PendingEntry(
    id, source, captureReason, confidence, transactionKind, amountMinor, currency,
    merchantTitle, transactionTimeEpochMillis, capturedAtEpochMillis, suggestedCategoryId,
    fundingAccountId, fundingAccountLabel, note, evidenceSummary, parsedFieldsText,
    suggestedCategoryLabel
)

internal fun IgnoredEntryEntity.toDomain() = IgnoredEntry(
    id, originalPendingEntryId, source, captureReason, confidence, transactionKind,
    amountMinor, currency, merchantTitle, transactionTimeEpochMillis, capturedAtEpochMillis,
    suggestedCategoryId, fundingAccountId, fundingAccountLabel, note, evidenceSummary,
    parsedFieldsText, ignoredAtEpochMillis, expiresAtEpochMillis, reason, suggestedCategoryLabel
)
