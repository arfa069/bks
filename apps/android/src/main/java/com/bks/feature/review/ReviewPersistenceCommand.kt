package com.bks.feature.review

import com.bks.data.local.ReviewConfirmationReceipt
import com.bks.data.local.TransactionKind

internal data class ReviewEntryEdit(
    val title: String,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val transactionKind: TransactionKind,
    val categoryId: String?,
    val category: String,
    val fundingAccountId: Long?,
    val fundingAccountLabel: String,
    val note: String?
)

internal sealed interface ReviewPersistenceCommand {
    val stableId: String

    data class Edit(
        override val stableId: String,
        val edit: ReviewEntryEdit
    ) : ReviewPersistenceCommand

    data class Confirm(
        override val stableId: String,
        val targetLedgerBookId: String?,
        val edit: ReviewEntryEdit? = null
    ) : ReviewPersistenceCommand

    data class Ignore(
        override val stableId: String
    ) : ReviewPersistenceCommand

    data class Recover(
        val ignoredEntryId: String,
        override val stableId: String
    ) : ReviewPersistenceCommand

    data class UndoConfirm(
        val receipt: ReviewConfirmationReceipt
    ) : ReviewPersistenceCommand {
        override val stableId: String = receipt.pendingSnapshot.id
    }

    data class UndoIgnore(
        val ignoredEntryId: String,
        override val stableId: String
    ) : ReviewPersistenceCommand
}

internal sealed interface ReviewPersistenceUndo {
    val entry: ReviewQueueEntry

    data class Confirmed(
        val receipt: ReviewConfirmationReceipt,
        override val entry: ReviewQueueEntry
    ) : ReviewPersistenceUndo

    data class Ignored(
        val ignoredEntryId: String,
        override val entry: ReviewQueueEntry
    ) : ReviewPersistenceUndo
}

internal sealed interface ReviewPersistenceResult {
    data class Applied(
        val undo: ReviewPersistenceUndo? = null
    ) : ReviewPersistenceResult

    data object NotFound : ReviewPersistenceResult
    data object AlreadyApplied : ReviewPersistenceResult
    data object Conflict : ReviewPersistenceResult
}
