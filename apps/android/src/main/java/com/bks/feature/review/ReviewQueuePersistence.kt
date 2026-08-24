package com.bks.feature.review

import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PendingEntryEdit
import com.bks.data.local.ReviewMutationResult
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
) {
    fun observeState(): Flow<ReviewQueueState> {
        val nowEpochMillis = nowProvider()
        return combine(
            repository.pendingEntries,
            repository.recoverableIgnoredEntries(nowEpochMillis)
        ) { pendingEntries, ignoredEntries ->
            ReviewQueueState(
                pendingEntries = pendingEntries.map { it.toReviewEntry(zoneId) },
                ignoredEntries = ignoredEntries.map { it.toReviewIgnoredEntry(zoneId) },
                nowEpochMillis = nowEpochMillis,
                todayStartEpochMillis = todayStartEpochMillis(
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId
                )
            )
        }
    }

    internal suspend fun loadState(): ReviewQueueState = observeState().first()

    suspend fun ensureSystemCategories() {
        repository.seedSystemCategories()
    }

    internal suspend fun insertCapturedEntries(entries: List<ReviewQueueEntry>): Int =
        entries.count { entry ->
            repository.insertPendingIfAbsent(entry.toEntity(zoneId))
        }

    internal suspend fun execute(command: ReviewPersistenceCommand): ReviewPersistenceResult =
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
                            receipt = receipt,
                            entry = receipt.pendingSnapshot.toReviewEntry(zoneId)
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
                        entry = ignored.toReviewIgnoredEntry(zoneId).entry
                    )
                )
            }

            is ReviewPersistenceCommand.Recover -> repository.recoverIgnoredForReview(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            ).toPersistenceResult()

            is ReviewPersistenceCommand.UndoConfirm -> repository.undoConfirmedReview(
                receipt = command.receipt,
                deletedAtEpochMillis = nowProvider()
            ).toPersistenceResult()

            is ReviewPersistenceCommand.UndoIgnore -> repository.recoverIgnoredForReview(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            ).toPersistenceResult()
        }
}

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
