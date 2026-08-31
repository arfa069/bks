package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategoryDao
import com.bks.model.data.local.database.CategoryEntity
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.DefaultFundingAccountCacheDao
import com.bks.model.data.local.database.FundingAccountDao
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.data.local.database.IgnoredEntryDao
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerBookDao
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LedgerBookEntryCounts
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.database.LocalSettingsDao
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.data.local.database.PendingEntryDao
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.data.local.settings.DefaultCategorizationRules
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.defaultFlowDirection
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.IgnoreReason
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.toEntryOrigin
import com.bks.model.domain.ledger.TransactionKind
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

sealed interface LedgerBookDeleteResult {
    data object Deleted : LedgerBookDeleteResult
    data object NotFound : LedgerBookDeleteResult
    data object LastLedgerBook : LedgerBookDeleteResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookDeleteResult
}

sealed interface FundingAccountDeleteResult {
    data object Deleted : FundingAccountDeleteResult
    data object NotFound : FundingAccountDeleteResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountDeleteResult
}

data class PendingEntryEdit(
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val transactionKind: TransactionKind,
    val suggestedCategoryId: String?,
    val suggestedCategoryLabel: String?,
    val fundingAccountId: Long?,
    val fundingAccountLabel: String?,
    val note: String?
)

data class ReviewConfirmationReceipt(
    val pendingSnapshot: PendingEntryEntity,
    val ledgerEntry: LedgerEntryEntity
)

data class ReviewConfirmationOverrides(
    val categoryId: String? = null,
    val note: String? = null
)

sealed interface ReviewMutationResult<out T> {
    data class Applied<T>(val value: T) : ReviewMutationResult<T>
    data object NotFound : ReviewMutationResult<Nothing>
    data object AlreadyApplied : ReviewMutationResult<Nothing>
    data object Conflict : ReviewMutationResult<Nothing>
}

data class LedgerRepositoryState(
    val ledgerBooks: List<LedgerBookEntryCounts> = emptyList(),
    val activeLedgerBook: LedgerBookEntity? = null,
    val ledgerEntries: List<LedgerEntryEntity> = emptyList(),
    val deletedLedgerEntries: List<LedgerEntryEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val fundingAccounts: List<FundingAccountEntity> = emptyList(),
    val defaultFundingAccountSyncId: String? = null
)

private data class LedgerRepositoryComponents(
    val ledgerBooks: RoomLedgerBookRepository,
    val ledgerEntries: RoomLedgerEntryRepository,
    val fundingAccounts: RoomFundingAccountRepository
)

private fun createLedgerRepositoryComponents(
    database: BksDatabase,
    clock: () -> Long,
    idGenerator: () -> String
): LedgerRepositoryComponents {
    val ledgerBooks = RoomLedgerBookRepository(database, clock, idGenerator)
    val fundingAccounts = RoomFundingAccountRepository(database, clock, idGenerator)
    return LedgerRepositoryComponents(
        ledgerBooks = ledgerBooks,
        ledgerEntries = RoomLedgerEntryRepository(
            database = database,
            clock = clock,
            idGenerator = idGenerator,
            ledgerBookRepository = ledgerBooks,
            fundingAccountRepository = fundingAccounts
        ),
        fundingAccounts = fundingAccounts
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLedgerRepository private constructor(
    private val database: BksDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val components: LedgerRepositoryComponents
) : LedgerBookRepository by components.ledgerBooks,
    LedgerEntryRepository by components.ledgerEntries,
    FundingAccountRepository by components.fundingAccounts {
    constructor(
        database: BksDatabase,
        clock: () -> Long = { System.currentTimeMillis() },
        idGenerator: () -> String = { UUID.randomUUID().toString() }
    ) : this(database, clock, idGenerator, createLedgerRepositoryComponents(database, clock, idGenerator))

    val pendingEntries = database.pendingEntryDao().observePendingEntries()
    // Compatibility API for backup/tests; the main UI consumes state below instead.
    val ledgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { ledgerEntries(it.id) } ?: flowOf(emptyList())
    }
    val deletedLedgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { deletedLedgerEntries(it.id) } ?: flowOf(emptyList())
    }
    val categories = database.categoryDao().observeCategories()
    val state = activeLedgerBook.flatMapLatest { activeLedgerBook ->
        combine(
            database.ledgerBookDao().observeEntryCounts(),
            activeLedgerBook?.let { ledgerEntries(it.id) } ?: flowOf(emptyList()),
            activeLedgerBook?.let { deletedLedgerEntries(it.id) } ?: flowOf(emptyList()),
            categories,
            fundingAccounts
        ) { ledgerBooks, ledgerEntries, deletedLedgerEntries, categories, fundingAccounts ->
            LedgerRepositoryState(
                ledgerBooks = ledgerBooks,
                activeLedgerBook = activeLedgerBook,
                ledgerEntries = ledgerEntries,
                deletedLedgerEntries = deletedLedgerEntries,
                categories = categories,
                fundingAccounts = fundingAccounts
            )
        }.combine(database.localSettingsDao().observeById()) { state, settings ->
            state.copy(defaultFundingAccountSyncId = settings?.defaultFundingAccountSyncId)
        }
    }

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

    suspend fun seedSystemCategories() {
        val defaults = DefaultCategories.systemDefaults(clock()).map(Category::toEntity)
        database.withTransaction {
            val recorder = LocalSyncMutationRecorder(database, clock, idGenerator)
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

    suspend fun createManualEntry(input: LedgerEntryInput): LedgerEntryEntity {
        val targetLedgerBookId = ensureDefaultLedgerBook().id
        return createManualEntry(targetLedgerBookId, input)
    }

    suspend fun listAllLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listAllLedgerEntries()

    suspend fun listLedgerEntriesBetween(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listLedgerEntriesBetween(startEpochMillis, endEpochMillis)

    suspend fun purgeExpiredDeletedLedgerEntries(): Int =
        purgeExpiredDeletedLedgerEntries(clock())

    suspend fun upsertPending(entry: PendingEntryEntity) {
        database.pendingEntryDao().upsert(entry)
    }

    suspend fun insertPendingIfAbsent(entry: PendingEntryEntity): Boolean = database.withTransaction {
        val pendingExists = database.pendingEntryDao().getById(entry.id) != null
        val activeLedgerExists = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(entry.id)
            .isNotEmpty()
        val ignoredExists = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(entry.id)
            .isNotEmpty()
        if (pendingExists || activeLedgerExists || ignoredExists) {
            false
        } else {
            database.pendingEntryDao().insertIgnore(entry) != -1L
        }
    }

    suspend fun deletePending(pendingEntryId: String) {
        database.pendingEntryDao().deleteById(pendingEntryId)
    }

    suspend fun upsertIgnored(entry: IgnoredEntryEntity) {
        database.ignoredEntryDao().upsert(entry)
    }

    suspend fun editPendingForReview(
        pendingEntryId: String,
        edit: PendingEntryEdit
    ): ReviewMutationResult<PendingEntryEntity> = database.withTransaction {
        val pending = database.pendingEntryDao().getById(pendingEntryId)
        val ledgerEntries = database.ledgerEntryDao().listActiveByOriginPendingEntryId(pendingEntryId)
        val ignoredEntries = database.ignoredEntryDao().listByOriginalPendingEntryId(pendingEntryId)
        if (pending == null) {
            return@withTransaction transitionedReviewResult(
                expectedTransition = ReviewExpectedTransition.EDIT,
                ledgerEntryCount = ledgerEntries.size,
                ignoredEntryCount = ignoredEntries.size
            )
        }
        if (ledgerEntries.isNotEmpty() || ignoredEntries.isNotEmpty()) {
            return@withTransaction ReviewMutationResult.Conflict
        }
        val updated = pending.applyEdit(edit)
        database.pendingEntryDao().upsert(updated)
        ReviewMutationResult.Applied(updated)
    }

    suspend fun confirmPendingForReview(
        pendingEntryId: String,
        ledgerBookId: String,
        edit: PendingEntryEdit? = null,
        overrides: ReviewConfirmationOverrides = ReviewConfirmationOverrides(),
        confirmedAtEpochMillis: Long = clock()
    ): ReviewMutationResult<ReviewConfirmationReceipt> = database.withTransaction {
        val pending = database.pendingEntryDao().getById(pendingEntryId)
        val ledgerEntries = database.ledgerEntryDao().listActiveByOriginPendingEntryId(pendingEntryId)
        val ignoredEntries = database.ignoredEntryDao().listByOriginalPendingEntryId(pendingEntryId)
        if (pending == null) {
            return@withTransaction transitionedReviewResult(
                expectedTransition = ReviewExpectedTransition.CONFIRM,
                ledgerEntryCount = ledgerEntries.size,
                ignoredEntryCount = ignoredEntries.size
            )
        }
        if (ledgerEntries.isNotEmpty() || ignoredEntries.isNotEmpty()) {
            return@withTransaction ReviewMutationResult.Conflict
        }
        val targetLedgerBook = components.ledgerBooks.requireForWrite(ledgerBookId)
        val pendingToConfirm = edit?.let(pending::applyEdit) ?: pending.copy(
            suggestedCategoryId = overrides.categoryId ?: pending.suggestedCategoryId,
            note = overrides.note ?: pending.note
        )
        val ledgerEntry = pendingToConfirm.toConfirmedLedgerEntry(
            id = idGenerator(),
            ledgerBookId = targetLedgerBook.id,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            fundingAccountId = components.fundingAccounts.resolvePending(pendingToConfirm)
        )
        database.pendingEntryDao().upsert(pendingToConfirm)
        database.ledgerEntryDao().upsert(ledgerEntry)
        check(database.pendingEntryDao().deleteById(pendingEntryId) == 1)
        LocalSyncMutationRecorder(database, clock, idGenerator).record(ledgerEntry)
        ReviewMutationResult.Applied(
            ReviewConfirmationReceipt(
                pendingSnapshot = pendingToConfirm,
                ledgerEntry = ledgerEntry
            )
        )
    }

    suspend fun ignorePendingForReview(
        pendingEntryId: String,
        reason: IgnoreReason = IgnoreReason.USER_IGNORED,
        ignoredAtEpochMillis: Long = clock()
    ): ReviewMutationResult<IgnoredEntryEntity> = database.withTransaction {
        val pending = database.pendingEntryDao().getById(pendingEntryId)
        val ledgerEntries = database.ledgerEntryDao().listActiveByOriginPendingEntryId(pendingEntryId)
        val ignoredEntries = database.ignoredEntryDao().listByOriginalPendingEntryId(pendingEntryId)
        if (pending == null) {
            return@withTransaction transitionedReviewResult(
                expectedTransition = ReviewExpectedTransition.IGNORE,
                ledgerEntryCount = ledgerEntries.size,
                ignoredEntryCount = ignoredEntries.size
            )
        }
        if (ledgerEntries.isNotEmpty() || ignoredEntries.isNotEmpty()) {
            return@withTransaction ReviewMutationResult.Conflict
        }
        val ignoredEntry = pending.toIgnoredEntry(
            id = idGenerator(),
            reason = reason,
            ignoredAtEpochMillis = ignoredAtEpochMillis
        )
        database.ignoredEntryDao().upsert(ignoredEntry)
        check(database.pendingEntryDao().deleteById(pendingEntryId) == 1)
        ReviewMutationResult.Applied(ignoredEntry)
    }

    suspend fun recoverIgnoredForReview(
        ignoredEntryId: String,
        expectedPendingEntryId: String
    ): ReviewMutationResult<PendingEntryEntity> = database.withTransaction {
        val ignored = database.ignoredEntryDao().getById(ignoredEntryId)
        val currentPending = database.pendingEntryDao().getById(expectedPendingEntryId)
        if (ignored == null) {
            return@withTransaction if (currentPending != null) {
                ReviewMutationResult.AlreadyApplied
            } else {
                ReviewMutationResult.NotFound
            }
        }
        val sameOriginIgnored = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(expectedPendingEntryId)
        val ledgerEntries = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(expectedPendingEntryId)
        val ignoredTargetMismatch = ignored.originalPendingEntryId != expectedPendingEntryId
        val lifecycleConflict = currentPending != null ||
            sameOriginIgnored.any { it.id != ignoredEntryId } ||
            ledgerEntries.isNotEmpty()
        if (ignoredTargetMismatch || lifecycleConflict) {
            return@withTransaction ReviewMutationResult.Conflict
        }
        val restored = ignored.toPendingEntry()
        check(database.pendingEntryDao().insertIgnore(restored) != -1L)
        check(database.ignoredEntryDao().deleteById(ignoredEntryId) == 1)
        ReviewMutationResult.Applied(restored)
    }

    suspend fun undoConfirmedReview(
        receipt: ReviewConfirmationReceipt,
        deletedAtEpochMillis: Long = clock()
    ): ReviewMutationResult<PendingEntryEntity> = database.withTransaction {
        val pendingEntryId = receipt.pendingSnapshot.id
        val currentPending = database.pendingEntryDao().getById(pendingEntryId)
        val currentLedger = database.ledgerEntryDao().getById(receipt.ledgerEntry.id)
        val activeOriginLedgers = database.ledgerEntryDao()
            .listActiveByOriginPendingEntryId(pendingEntryId)
        val originIgnoredEntries = database.ignoredEntryDao()
            .listByOriginalPendingEntryId(pendingEntryId)
        if (currentLedger == null || currentLedger.deletedAtEpochMillis != null) {
            return@withTransaction completedUndoResult(
                expectedPending = receipt.pendingSnapshot,
                currentPending = currentPending,
                activeLedgerCount = activeOriginLedgers.size,
                ignoredEntryCount = originIgnoredEntries.size
            )
        }
        val ledgerIdentityMatches = currentLedger.originPendingEntryId == pendingEntryId &&
            activeOriginLedgers.singleOrNull()?.id == currentLedger.id
        val ledgerSnapshotMatches = currentLedger == receipt.ledgerEntry
        val lifecycleConflict = currentPending != null || originIgnoredEntries.isNotEmpty()
        if (!ledgerIdentityMatches || !ledgerSnapshotMatches || lifecycleConflict) {
            return@withTransaction ReviewMutationResult.Conflict
        }
        check(database.pendingEntryDao().insertIgnore(receipt.pendingSnapshot) != -1L)
        check(database.ledgerEntryDao().moveToDeleted(currentLedger.id, deletedAtEpochMillis) == 1)
        LocalSyncMutationRecorder(database, clock, idGenerator).record(
            currentLedger.copy(deletedAtEpochMillis = deletedAtEpochMillis)
        )
        ReviewMutationResult.Applied(receipt.pendingSnapshot)
    }

    suspend fun confirmPending(
        pendingEntryId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity {
        val targetLedgerBookId = ensureDefaultLedgerBook().id
        return confirmPending(
            pendingEntryId = pendingEntryId,
            ledgerBookId = targetLedgerBookId,
            categoryId = categoryId,
            note = note,
            confirmedAtEpochMillis = confirmedAtEpochMillis
        )
    }

    suspend fun confirmPending(
        pendingEntryId: String,
        ledgerBookId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity {
        return confirmPendingForReview(
            pendingEntryId = pendingEntryId,
            ledgerBookId = ledgerBookId,
            overrides = ReviewConfirmationOverrides(categoryId = categoryId, note = note),
            confirmedAtEpochMillis = confirmedAtEpochMillis
        ).requireApplied("Pending entry cannot be confirmed: $pendingEntryId").ledgerEntry
    }

    suspend fun ignorePending(
        pendingEntryId: String,
        reason: IgnoreReason = IgnoreReason.USER_IGNORED,
        ignoredAtEpochMillis: Long = clock()
    ): IgnoredEntryEntity = ignorePendingForReview(
        pendingEntryId = pendingEntryId,
        reason = reason,
        ignoredAtEpochMillis = ignoredAtEpochMillis
    ).requireApplied("Pending entry cannot be ignored: $pendingEntryId")

    suspend fun recoverIgnored(ignoredEntryId: String): PendingEntryEntity {
        val ignored = database.ignoredEntryDao().getById(ignoredEntryId)
            ?: error("Ignored entry not found: $ignoredEntryId")
        return recoverIgnoredForReview(
            ignoredEntryId = ignoredEntryId,
            expectedPendingEntryId = ignored.originalPendingEntryId
        ).requireApplied("Ignored entry cannot be recovered: $ignoredEntryId")
    }

    suspend fun deleteIgnored(ignoredEntryId: String) {
        database.ignoredEntryDao().deleteById(ignoredEntryId)
    }

    suspend fun clearLocalData() = database.withTransaction {
        database.ledgerEntryDao().deleteAll()
        database.pendingEntryDao().deleteAll()
        database.ignoredEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.localSettingsDao().deleteAll()
        database.defaultFundingAccountCacheDao().deleteAll()
        database.ledgerBookDao().insert(components.ledgerBooks.defaultLedgerBook())
        database.categoryDao().insertIgnore(
            DefaultCategories.systemDefaults(clock()).map(Category::toEntity)
        )
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            )
        )
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }

    companion object {
        const val IGNORED_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
        const val DELETED_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
        const val SUPPORTED_CURRENCY = "CNY"
        const val DEFAULT_CATEGORY_ID = "uncategorized"
    }
}

data class LedgerEntryInput(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String?,
    val fundingAccountId: Long?,
    val newFundingAccountLabel: String?,
    val note: String?,
    val paymentSource: PaymentSource?
)

private fun PendingEntryEntity.applyEdit(edit: PendingEntryEdit): PendingEntryEntity {
    require(edit.amountMinor > 0) { "Amount must be greater than zero" }
    return copy(
        merchantTitle = edit.merchantTitle.trim().ifBlank { merchantTitle },
        amountMinor = edit.amountMinor,
        transactionTimeEpochMillis = edit.transactionTimeEpochMillis,
        transactionKind = edit.transactionKind,
        suggestedCategoryId = edit.suggestedCategoryId,
        suggestedCategoryLabel = edit.suggestedCategoryLabel?.trim()?.ifBlank { null },
        fundingAccountId = edit.fundingAccountId,
        fundingAccountLabel = edit.fundingAccountLabel?.trim()?.ifBlank { null },
        note = edit.note?.trim()?.ifBlank { null }
    )
}

private fun PendingEntryEntity.toConfirmedLedgerEntry(
    id: String,
    ledgerBookId: String,
    confirmedAtEpochMillis: Long,
    fundingAccountId: Long?
): LedgerEntryEntity = LedgerEntryEntity(
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
    reason: IgnoreReason,
    ignoredAtEpochMillis: Long
): IgnoredEntryEntity = IgnoredEntryEntity(
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
    expiresAtEpochMillis = ignoredAtEpochMillis + LocalLedgerRepository.IGNORED_RETENTION_MILLIS,
    reason = reason,
    suggestedCategoryLabel = suggestedCategoryLabel
)

private enum class ReviewExpectedTransition {
    EDIT,
    CONFIRM,
    IGNORE
}

private fun <T> transitionedReviewResult(
    expectedTransition: ReviewExpectedTransition,
    ledgerEntryCount: Int,
    ignoredEntryCount: Int
): ReviewMutationResult<T> = when {
    ledgerEntryCount == 0 && ignoredEntryCount == 0 -> ReviewMutationResult.NotFound
    expectedTransition == ReviewExpectedTransition.CONFIRM &&
        ledgerEntryCount == 1 && ignoredEntryCount == 0 -> ReviewMutationResult.AlreadyApplied
    expectedTransition == ReviewExpectedTransition.IGNORE &&
        ledgerEntryCount == 0 && ignoredEntryCount == 1 -> ReviewMutationResult.AlreadyApplied
    else -> ReviewMutationResult.Conflict
}

private fun <T> completedUndoResult(
    expectedPending: PendingEntryEntity,
    currentPending: PendingEntryEntity?,
    activeLedgerCount: Int,
    ignoredEntryCount: Int
): ReviewMutationResult<T> = when {
    currentPending == expectedPending && activeLedgerCount == 0 && ignoredEntryCount == 0 ->
        ReviewMutationResult.AlreadyApplied
    currentPending != null || activeLedgerCount > 0 || ignoredEntryCount > 0 ->
        ReviewMutationResult.Conflict
    else -> ReviewMutationResult.NotFound
}

private fun <T> ReviewMutationResult<T>.requireApplied(message: String): T = when (this) {
    is ReviewMutationResult.Applied -> value
    ReviewMutationResult.NotFound -> error(message)
    ReviewMutationResult.AlreadyApplied -> error("$message (already applied)")
    ReviewMutationResult.Conflict -> error("$message (conflict)")
}

private fun IgnoredEntryEntity.toPendingEntry(): PendingEntryEntity = PendingEntryEntity(
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
