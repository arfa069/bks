package com.bks.model.data.local.ledger

import com.bks.model.domain.review.ReviewEntryEdit
import com.bks.model.domain.review.ReviewConfirmationReceipt as DomainReviewConfirmationReceipt
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewPersistenceUndo
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.review.ReviewQueueSnapshot
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.data.local.ledger.LocalLedgerRepository
import com.bks.model.data.local.ledger.PendingEntryEdit
import com.bks.model.data.local.ledger.ReviewMutationResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ReviewQueuePersistence(
    private val repository: LocalLedgerRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ReviewQueueRepository {
    override fun observeSnapshot(): Flow<ReviewQueueSnapshot> {
        val nowEpochMillis = nowProvider()
        return combine(
            repository.pendingEntries,
            repository.recoverableIgnoredEntries(nowEpochMillis)
        ) { pendingEntries, ignoredEntries ->
            ReviewQueueSnapshot(
                pendingEntries = pendingEntries.map { it.toDomain() },
                ignoredEntries = ignoredEntries.map { it.toDomain() },
                nowEpochMillis = nowEpochMillis,
                todayStartEpochMillis = todayStartEpochMillis(
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId
                )
            )
        }
    }

    override suspend fun loadSnapshot(): ReviewQueueSnapshot = observeSnapshot().first()

    override suspend fun ensureSystemCategories() {
        repository.seedSystemCategories()
    }

    override suspend fun insertCapturedEntries(entries: List<PendingEntry>): Int =
        entries.count { entry ->
            repository.insertPendingIfAbsent(entry.toEntity())
        }

    override suspend fun execute(command: ReviewPersistenceCommand): ReviewPersistenceResult =
        when (command) {
            is ReviewPersistenceCommand.Edit -> repository.editPendingForReview(
                pendingEntryId = command.stableId,
                edit = command.edit.toPendingEntryEdit()
            ).toPersistenceResult()

            is ReviewPersistenceCommand.Confirm -> command.targetLedgerBookId?.let { ledgerBookId ->
                repository.confirmPendingForReview(
                    pendingEntryId = command.stableId,
                    ledgerBookId = ledgerBookId,
                    edit = command.edit?.toPendingEntryEdit()
                ).toPersistenceResult { receipt ->
                    ReviewPersistenceResult.Applied(
                        ReviewPersistenceUndo.Confirmed(
                            receipt = receipt.toDomain(),
                            entry = receipt.pendingSnapshot.toDomain()
                        )
                    )
                }
            } ?: ReviewPersistenceResult.NotFound

            is ReviewPersistenceCommand.Ignore -> repository.ignorePendingForReview(
                pendingEntryId = command.stableId,
                ignoredAtEpochMillis = nowProvider()
            ).toPersistenceResult { ignored ->
                ReviewPersistenceResult.Applied(
                    ReviewPersistenceUndo.Ignored(
                        ignoredEntryId = ignored.id,
                        entry = ignored.toDomain().toPendingEntry()
                    )
                )
            }

            is ReviewPersistenceCommand.Recover -> repository.recoverIgnoredForReview(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            ).toPersistenceResult()

            is ReviewPersistenceCommand.UndoConfirm -> repository.undoConfirmedReview(
                receipt = command.receipt.toLocal(),
                deletedAtEpochMillis = nowProvider()
            ).toPersistenceResult()

            is ReviewPersistenceCommand.UndoIgnore -> repository.recoverIgnoredForReview(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            ).toPersistenceResult()
        }
}

private fun ReviewConfirmationReceipt.toDomain() = DomainReviewConfirmationReceipt(
    pendingSnapshot = pendingSnapshot.toDomain(),
    ledgerEntry = ledgerEntry.toDomain()
)

private fun DomainReviewConfirmationReceipt.toLocal() = ReviewConfirmationReceipt(
    pendingSnapshot = pendingSnapshot.toEntity(),
    ledgerEntry = ledgerEntry.toEntity()
)

private fun com.bks.model.domain.ledger.IgnoredEntry.toPendingEntry() = PendingEntry(
    id = originalPendingEntryId,
    source = source,
    captureReason = captureReason,
    confidence = confidence,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
    suggestedCategoryId = suggestedCategoryId,
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText,
    suggestedCategoryLabel = suggestedCategoryLabel
)

private fun PendingEntry.toEntity() = PendingEntryEntity(
    id = id,
    source = source,
    captureReason = captureReason,
    confidence = confidence,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
    suggestedCategoryId = suggestedCategoryId,
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText,
    suggestedCategoryLabel = suggestedCategoryLabel
)

private fun com.bks.model.domain.ledger.LedgerEntry.toEntity() = LedgerEntryEntity(
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

private fun ReviewEntryEdit.toPendingEntryEdit(): PendingEntryEdit = PendingEntryEdit(
    merchantTitle = title,
    amountMinor = amountMinor,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    transactionKind = transactionKind,
    suggestedCategoryId = categoryId,
    suggestedCategoryLabel = category,
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel,
    note = note
)

private inline fun <T> ReviewMutationResult<T>.toPersistenceResult(
    applied: (T) -> ReviewPersistenceResult.Applied = { ReviewPersistenceResult.Applied() }
): ReviewPersistenceResult = when (this) {
    is ReviewMutationResult.Applied -> applied(value)
    ReviewMutationResult.NotFound -> ReviewPersistenceResult.NotFound
    ReviewMutationResult.AlreadyApplied -> ReviewPersistenceResult.AlreadyApplied
    ReviewMutationResult.Conflict -> ReviewPersistenceResult.Conflict
}

private fun todayStartEpochMillis(
    nowEpochMillis: Long,
    zoneId: ZoneId
): Long = LocalDate.from(
    Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
)
    .atStartOfDay(zoneId)
    .toInstant()
    .toEpochMilli()
