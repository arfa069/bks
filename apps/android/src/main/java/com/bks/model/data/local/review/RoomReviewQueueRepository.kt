package com.bks.model.data.local.review

import androidx.room.withTransaction
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.data.local.ledger.RoomFundingAccountStore
import com.bks.model.data.local.ledger.RoomLedgerBookStore
import com.bks.model.data.local.ledger.toDomain
import com.bks.model.data.local.ledger.toEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.applyCategorizationSuggestion
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.IgnoreReason
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.defaultFlowDirection
import com.bks.model.domain.ledger.toEntryOrigin
import com.bks.model.domain.review.ReviewConfirmationReceipt
import com.bks.model.domain.review.IGNORED_ENTRY_RETENTION_MILLIS
import com.bks.model.domain.review.ReviewEntryEdit
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewPersistenceUndo
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.review.ReviewQueueSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

@Suppress("TooManyFunctions")
class RoomReviewQueueRepository(
    private val database: BksDatabase,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : ReviewQueueRepository {
    private val ledgerBookStore = RoomLedgerBookStore(database, nowProvider, idGenerator)
    private val fundingAccountStore = RoomFundingAccountStore(database, nowProvider, idGenerator)

    override fun observeSnapshot(): Flow<ReviewQueueSnapshot> {
        val nowEpochMillis = nowProvider()
        return combine(
            database.pendingEntryDao().observePendingEntries(),
            database.ignoredEntryDao().observeRecoverable(nowEpochMillis)
        ) { pendingEntries, ignoredEntries ->
            ReviewQueueSnapshot(
                pendingEntries = pendingEntries.map { it.toDomain() },
                ignoredEntries = ignoredEntries.map { it.toDomain() },
                nowEpochMillis = nowEpochMillis,
                todayStartEpochMillis = todayStartEpochMillis(nowEpochMillis, zoneId)
            )
        }
    }

    override suspend fun loadSnapshot(): ReviewQueueSnapshot = observeSnapshot().first()

    override suspend fun ensureSystemCategories() {
        val defaults = DefaultCategories.systemDefaults(nowProvider()).map(Category::toEntity)
        database.withTransaction {
            val recorder = LocalSyncMutationRecorder(database, nowProvider, idGenerator)
            database.categoryDao().insertIgnore(defaults)
            defaults.forEach { category ->
                database.categoryDao().updateSystemCategory(
                    id = category.id,
                    name = category.name,
                    kind = category.kind,
                    sortOrder = category.sortOrder
                )
                database.categoryDao().getCategory(category.id)?.let { recorder.record(it) }
            }
        }
    }

    override suspend fun loadCategories(): List<Category> =
        database.categoryDao().getAllCategories().map { it.toDomain() }

    override suspend fun insertCapturedEntries(entries: List<PendingEntry>): List<PendingEntry> =
        entries.filter { entry -> insertPendingIfAbsent(entry.toEntity()) }

    override suspend fun applyCategorizationRulesToUnclassified(
        rules: List<CategorizationRule>
    ): Int = database.withTransaction {
        val categories = database.categoryDao().getAllCategories().map { it.toDomain() }
        val updates = database.pendingEntryDao().listPendingEntries().mapNotNull { entry ->
            if (entry.suggestedCategoryId != null || entry.suggestedCategoryLabel != null) {
                return@mapNotNull null
            }
            val categorized = entry.toDomain().applyCategorizationSuggestion(rules, categories)
            categorized.suggestedCategoryId?.let {
                entry.copy(
                    suggestedCategoryId = it,
                    suggestedCategoryLabel = categorized.suggestedCategoryLabel
                )
            }
        }
        if (updates.isNotEmpty()) database.pendingEntryDao().upsertAll(updates)
        updates.size
    }

    override suspend fun execute(command: ReviewPersistenceCommand): ReviewPersistenceResult =
        when (command) {
            is ReviewPersistenceCommand.Edit -> editPending(command.stableId, command.edit)
            is ReviewPersistenceCommand.Confirm -> command.targetLedgerBookId?.let { ledgerBookId ->
                confirmPending(command.stableId, ledgerBookId, command.edit)
            } ?: ReviewPersistenceResult.NotFound
            is ReviewPersistenceCommand.Ignore -> ignorePending(command.stableId)
            is ReviewPersistenceCommand.Recover -> recoverIgnored(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            )
            is ReviewPersistenceCommand.UndoConfirm -> undoConfirmed(command.receipt)
            is ReviewPersistenceCommand.UndoIgnore -> recoverIgnored(
                ignoredEntryId = command.ignoredEntryId,
                expectedPendingEntryId = command.stableId
            )
        }

    private suspend fun insertPendingIfAbsent(entry: PendingEntryEntity): Boolean =
        database.withTransaction {
            val pendingExists = database.pendingEntryDao().getById(entry.id) != null
            val ledgerExists = database.ledgerEntryDao()
                .listActiveByOriginPendingEntryId(entry.id)
                .isNotEmpty()
            val ignoredExists = database.ignoredEntryDao()
                .listByOriginalPendingEntryId(entry.id)
                .isNotEmpty()
            if (pendingExists || ledgerExists || ignoredExists) {
                false
            } else {
                database.pendingEntryDao().insertIgnore(entry) != -1L
            }
        }

    private suspend fun editPending(
        pendingEntryId: String,
        edit: ReviewEntryEdit
    ): ReviewPersistenceResult = database.withTransaction {
        val pending = database.pendingEntryDao().getById(pendingEntryId)
        val ledgerEntryCount = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(pendingEntryId)
            .size
        val ignoredEntryCount = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(pendingEntryId)
            .size
        if (pending == null) {
            return@withTransaction transitionedResult(
                expectedTransition = ReviewExpectedTransition.EDIT,
                ledgerEntryCount = ledgerEntryCount,
                ignoredEntryCount = ignoredEntryCount
            )
        }
        if (ledgerEntryCount > 0 || ignoredEntryCount > 0) {
            return@withTransaction ReviewPersistenceResult.Conflict
        }
        database.pendingEntryDao().upsert(pending.applyEdit(edit))
        ReviewPersistenceResult.Applied()
    }

    private suspend fun confirmPending(
        pendingEntryId: String,
        ledgerBookId: String,
        edit: ReviewEntryEdit?
    ): ReviewPersistenceResult = database.withTransaction {
        val pending = database.pendingEntryDao().getById(pendingEntryId)
        val ledgerEntryCount = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(pendingEntryId)
            .size
        val ignoredEntryCount = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(pendingEntryId)
            .size
        if (pending == null) {
            return@withTransaction transitionedResult(
                expectedTransition = ReviewExpectedTransition.CONFIRM,
                ledgerEntryCount = ledgerEntryCount,
                ignoredEntryCount = ignoredEntryCount
            )
        }
        if (ledgerEntryCount > 0 || ignoredEntryCount > 0) {
            return@withTransaction ReviewPersistenceResult.Conflict
        }
        val targetLedgerBook = ledgerBookStore.requireForWrite(ledgerBookId)
        val pendingToConfirm = edit?.let(pending::applyEdit) ?: pending
        val confirmedAtEpochMillis = nowProvider()
        val ledgerEntry = pendingToConfirm.toConfirmedLedgerEntry(
            id = idGenerator(),
            ledgerBookId = targetLedgerBook.id,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            fundingAccountId = fundingAccountStore.resolvePending(pendingToConfirm)
        )
        database.pendingEntryDao().upsert(pendingToConfirm)
        database.ledgerEntryDao().upsert(ledgerEntry)
        check(database.pendingEntryDao().deleteById(pendingEntryId) == 1)
        LocalSyncMutationRecorder(database, nowProvider, idGenerator).record(ledgerEntry)
        val receipt = ReviewConfirmationReceipt(
            pendingSnapshot = pendingToConfirm.toDomain(),
            ledgerEntry = ledgerEntry.toDomain()
        )
        ReviewPersistenceResult.Applied(
            ReviewPersistenceUndo.Confirmed(
                receipt = receipt,
                entry = receipt.pendingSnapshot
            )
        )
    }

    private suspend fun ignorePending(pendingEntryId: String): ReviewPersistenceResult =
        database.withTransaction {
            val pending = database.pendingEntryDao().getById(pendingEntryId)
            val ledgerEntryCount = database.ledgerEntryDao()
                .listActiveByOriginPendingEntryId(pendingEntryId)
                .size
            val ignoredEntryCount = database.ignoredEntryDao()
                .listByOriginalPendingEntryId(pendingEntryId)
                .size
            if (pending == null) {
                return@withTransaction transitionedResult(
                    expectedTransition = ReviewExpectedTransition.IGNORE,
                    ledgerEntryCount = ledgerEntryCount,
                    ignoredEntryCount = ignoredEntryCount
                )
            }
            if (ledgerEntryCount > 0 || ignoredEntryCount > 0) {
                return@withTransaction ReviewPersistenceResult.Conflict
            }
            val ignoredAtEpochMillis = nowProvider()
            val ignoredEntry = pending.toIgnoredEntry(
                id = idGenerator(),
                ignoredAtEpochMillis = ignoredAtEpochMillis
            )
            database.ignoredEntryDao().upsert(ignoredEntry)
            check(database.pendingEntryDao().deleteById(pendingEntryId) == 1)
            ReviewPersistenceResult.Applied(
                ReviewPersistenceUndo.Ignored(
                    ignoredEntryId = ignoredEntry.id,
                    entry = pending.toDomain()
                )
            )
        }

    private suspend fun recoverIgnored(
        ignoredEntryId: String,
        expectedPendingEntryId: String
    ): ReviewPersistenceResult = database.withTransaction {
        val ignored = database.ignoredEntryDao().getById(ignoredEntryId)
        val currentPending = database.pendingEntryDao().getById(expectedPendingEntryId)
        if (ignored == null) {
            return@withTransaction if (currentPending != null) {
                ReviewPersistenceResult.AlreadyApplied
            } else {
                ReviewPersistenceResult.NotFound
            }
        }
        val sameOriginIgnored = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(expectedPendingEntryId)
        val ledgerEntries = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(expectedPendingEntryId)
        val lifecycleConflict = ignored.originalPendingEntryId != expectedPendingEntryId ||
            currentPending != null ||
            sameOriginIgnored.any { it.id != ignoredEntryId } ||
            ledgerEntries.isNotEmpty()
        if (lifecycleConflict) return@withTransaction ReviewPersistenceResult.Conflict

        check(database.pendingEntryDao().insertIgnore(ignored.toPendingEntry()) != -1L)
        check(database.ignoredEntryDao().deleteById(ignoredEntryId) == 1)
        ReviewPersistenceResult.Applied()
    }

    private suspend fun undoConfirmed(
        receipt: ReviewConfirmationReceipt
    ): ReviewPersistenceResult = database.withTransaction {
        val pendingSnapshot = receipt.pendingSnapshot.toEntity()
        val ledgerSnapshot = receipt.ledgerEntry.toEntity()
        val pendingEntryId = pendingSnapshot.id
        val currentPending = database.pendingEntryDao().getById(pendingEntryId)
        val currentLedger = database.ledgerEntryDao().getById(ledgerSnapshot.id)
        val activeOriginLedgers = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(pendingEntryId)
        val originIgnoredEntries = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(pendingEntryId)
        if (currentLedger == null || currentLedger.deletedAtEpochMillis != null) {
            return@withTransaction completedUndoResult(
                expectedPending = pendingSnapshot,
                currentPending = currentPending,
                activeLedgerCount = activeOriginLedgers.size,
                ignoredEntryCount = originIgnoredEntries.size
            )
        }
        val ledgerIdentityMatches = currentLedger.originPendingEntryId == pendingEntryId &&
            activeOriginLedgers.singleOrNull()?.id == currentLedger.id
        val lifecycleConflict = currentPending != null || originIgnoredEntries.isNotEmpty()
        if (!ledgerIdentityMatches || currentLedger != ledgerSnapshot || lifecycleConflict) {
            return@withTransaction ReviewPersistenceResult.Conflict
        }
        check(database.pendingEntryDao().insertIgnore(pendingSnapshot) != -1L)
        val deletedAtEpochMillis = nowProvider()
        check(database.ledgerEntryDao().moveToDeleted(currentLedger.id, deletedAtEpochMillis) == 1)
        LocalSyncMutationRecorder(database, nowProvider, idGenerator).record(
            currentLedger.copy(deletedAtEpochMillis = deletedAtEpochMillis)
        )
        ReviewPersistenceResult.Applied()
    }
}

private fun PendingEntryEntity.applyEdit(edit: ReviewEntryEdit): PendingEntryEntity {
    require(edit.amountMinor > 0) { "Amount must be greater than zero" }
    return copy(
        merchantTitle = edit.title.trim().ifBlank { merchantTitle },
        amountMinor = edit.amountMinor,
        transactionTimeEpochMillis = edit.transactionTimeEpochMillis,
        transactionKind = edit.transactionKind,
        suggestedCategoryId = edit.categoryId,
        suggestedCategoryLabel = edit.category.trim().ifBlank { null },
        fundingAccountId = edit.fundingAccountId,
        fundingAccountLabel = edit.fundingAccountLabel.trim().ifBlank { null },
        note = edit.note?.trim()?.ifBlank { null }
    )
}

private fun PendingEntryEntity.toConfirmedLedgerEntry(
    id: String,
    ledgerBookId: String,
    confirmedAtEpochMillis: Long,
    fundingAccountId: Long?
) = LedgerEntryEntity(
    id = id,
    ledgerBookId = ledgerBookId,
    paymentSource = source,
    originalCaptureSource = source,
    entryOrigin = captureReason.toEntryOrigin(),
    originPendingEntryId = this.id,
    flowDirection = transactionKind.defaultFlowDirection(),
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    categoryId = suggestedCategoryId,
    fundingAccountId = fundingAccountId,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    updatedAtEpochMillis = confirmedAtEpochMillis,
    deletedAtEpochMillis = null
)

private fun PendingEntryEntity.toIgnoredEntry(
    id: String,
    ignoredAtEpochMillis: Long
) = IgnoredEntryEntity(
    id = id,
    originalPendingEntryId = this.id,
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
    ignoredAtEpochMillis = ignoredAtEpochMillis,
    expiresAtEpochMillis = ignoredAtEpochMillis + IGNORED_ENTRY_RETENTION_MILLIS,
    reason = IgnoreReason.USER_IGNORED,
    suggestedCategoryLabel = suggestedCategoryLabel
)

private fun IgnoredEntryEntity.toPendingEntry() = PendingEntryEntity(
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

private enum class ReviewExpectedTransition { EDIT, CONFIRM, IGNORE }

private fun transitionedResult(
    expectedTransition: ReviewExpectedTransition,
    ledgerEntryCount: Int,
    ignoredEntryCount: Int
): ReviewPersistenceResult = when {
    ledgerEntryCount == 0 && ignoredEntryCount == 0 -> ReviewPersistenceResult.NotFound
    expectedTransition == ReviewExpectedTransition.CONFIRM &&
        ledgerEntryCount == 1 && ignoredEntryCount == 0 -> ReviewPersistenceResult.AlreadyApplied
    expectedTransition == ReviewExpectedTransition.IGNORE &&
        ledgerEntryCount == 0 && ignoredEntryCount == 1 -> ReviewPersistenceResult.AlreadyApplied
    else -> ReviewPersistenceResult.Conflict
}

private fun completedUndoResult(
    expectedPending: PendingEntryEntity,
    currentPending: PendingEntryEntity?,
    activeLedgerCount: Int,
    ignoredEntryCount: Int
): ReviewPersistenceResult = when {
    currentPending == expectedPending && activeLedgerCount == 0 && ignoredEntryCount == 0 ->
        ReviewPersistenceResult.AlreadyApplied
    currentPending != null || activeLedgerCount > 0 || ignoredEntryCount > 0 ->
        ReviewPersistenceResult.Conflict
    else -> ReviewPersistenceResult.NotFound
}

private fun todayStartEpochMillis(nowEpochMillis: Long, zoneId: ZoneId): Long =
    LocalDate.from(Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId))
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
