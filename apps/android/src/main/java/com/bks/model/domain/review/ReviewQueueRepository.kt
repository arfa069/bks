package com.bks.model.domain.review

import com.bks.model.domain.ledger.IgnoredEntry
import com.bks.model.domain.ledger.LedgerEntry
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import kotlinx.coroutines.flow.Flow

data class ReviewEntryEdit(
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

data class ReviewQueueSnapshot(
    val pendingEntries: List<PendingEntry> = emptyList(),
    val ignoredEntries: List<IgnoredEntry> = emptyList(),
    val nowEpochMillis: Long,
    val todayStartEpochMillis: Long
)

data class ReviewConfirmationReceipt(
    val pendingSnapshot: PendingEntry,
    val ledgerEntry: LedgerEntry
)

sealed interface ReviewPersistenceCommand {
    val stableId: String

    data class Edit(override val stableId: String, val edit: ReviewEntryEdit) : ReviewPersistenceCommand
    data class Confirm(
        override val stableId: String,
        val targetLedgerBookId: String?,
        val edit: ReviewEntryEdit? = null
    ) : ReviewPersistenceCommand
    data class Ignore(override val stableId: String) : ReviewPersistenceCommand
    data class Recover(val ignoredEntryId: String, override val stableId: String) : ReviewPersistenceCommand
    data class UndoConfirm(val receipt: ReviewConfirmationReceipt) : ReviewPersistenceCommand {
        override val stableId: String = receipt.pendingSnapshot.id
    }
    data class UndoIgnore(val ignoredEntryId: String, override val stableId: String) : ReviewPersistenceCommand
}

sealed interface ReviewPersistenceUndo {
    val entry: PendingEntry

    data class Confirmed(
        val receipt: ReviewConfirmationReceipt,
        override val entry: PendingEntry
    ) : ReviewPersistenceUndo

    data class Ignored(
        val ignoredEntryId: String,
        override val entry: PendingEntry
    ) : ReviewPersistenceUndo
}

sealed interface ReviewPersistenceResult {
    data class Applied(val undo: ReviewPersistenceUndo? = null) : ReviewPersistenceResult
    data object NotFound : ReviewPersistenceResult
    data object AlreadyApplied : ReviewPersistenceResult
    data object Conflict : ReviewPersistenceResult
}

interface ReviewQueueRepository {
    fun observeSnapshot(): Flow<ReviewQueueSnapshot>
    suspend fun loadSnapshot(): ReviewQueueSnapshot
    suspend fun ensureSystemCategories()
    suspend fun insertCapturedEntries(entries: List<PendingEntry>): Int
    suspend fun execute(command: ReviewPersistenceCommand): ReviewPersistenceResult
}

const val IGNORED_ENTRY_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
