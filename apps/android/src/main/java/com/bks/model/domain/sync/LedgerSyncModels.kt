package com.bks.model.domain.sync

import kotlinx.coroutines.flow.Flow

const val LEDGER_SYNC_BATCH_SIZE = 100

enum class LedgerSyncEntityType {
    CATEGORY,
    FUNDING_ACCOUNT,
    LEDGER_BOOK,
    LEDGER_ENTRY,
    CATEGORIZATION_RULE
}

enum class LedgerSyncConflictChoice {
    CANONICAL,
    CANDIDATE
}

sealed interface LedgerSyncPayload {
    data class Category(
        val id: String,
        val name: String,
        val kind: String?,
        val sortOrder: Int,
        val isSystem: Boolean,
        val createdAtMillis: Long
    ) : LedgerSyncPayload

    data class FundingAccount(
        val syncId: String,
        val sourceScope: String,
        val paymentSource: String?,
        val label: String,
        val createdAtMillis: Long
    ) : LedgerSyncPayload

    data class LedgerBook(val id: String, val name: String, val createdAtMillis: Long) : LedgerSyncPayload

    data class LedgerEntry(
        val id: String,
        val ledgerBookId: String,
        val paymentSource: String?,
        val originalCaptureSource: String?,
        val entryOrigin: String,
        val flowDirection: String,
        val transactionKind: String,
        val amountMinor: Long,
        val currency: String,
        val merchantTitle: String,
        val transactionTimeMillis: Long,
        val categoryId: String?,
        val fundingAccountSyncId: String?,
        val note: String?,
        val confirmedAtMillis: Long,
        val updatedAtMillis: Long,
        val deletedAtMillis: Long?
    ) : LedgerSyncPayload

    data class CategorizationRule(
        val id: String,
        val merchantContains: String,
        val titleContains: String,
        val sourceLabel: String,
        val transactionKind: String,
        val category: String,
        val priority: Int,
        val enabled: Boolean,
        val updatedAtMillis: Long
    ) : LedgerSyncPayload
}

data class LedgerSyncRecord(
    val entityType: LedgerSyncEntityType,
    val entityId: String,
    val version: Long,
    val revision: Long,
    val deleted: Boolean,
    val payload: LedgerSyncPayload?
)

data class LedgerSyncMutation(
    val mutationId: String,
    val entityType: LedgerSyncEntityType,
    val entityId: String,
    val baseVersion: Long,
    val deleted: Boolean,
    val payload: LedgerSyncPayload?
)

data class LedgerSyncMutationResult(
    val mutationId: String,
    val accepted: Boolean,
    val version: Long?,
    val revision: Long?,
    val conflictId: String?,
    val canonicalEntityId: String? = null
)

data class LedgerSyncConflict(
    val conflictId: String,
    val entityType: LedgerSyncEntityType,
    val entityId: String,
    val canonicalVersion: Long,
    val canonicalDeleted: Boolean,
    val canonicalPayload: LedgerSyncPayload?,
    val candidateDeleted: Boolean,
    val candidatePayload: LedgerSyncPayload?,
    val createdAtMillis: Long
)

data class LedgerSyncLocalState(
    val profileKey: String? = null,
    val enabled: Boolean = false,
    val cursor: Long = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null
)

data class LedgerSyncInitializeResult(val profileKey: String, val recordCount: Int, val currentCursor: Long)
data class LedgerSyncSnapshotPage(val records: List<LedgerSyncRecord>, val nextOffset: Int?, val currentCursor: Long)
data class LedgerSyncPushResult(val results: List<LedgerSyncMutationResult>, val currentCursor: Long)
data class LedgerSyncPullResult(
    val records: List<LedgerSyncRecord>,
    val conflicts: List<LedgerSyncConflict>,
    val nextCursor: Long,
    val hasMore: Boolean
)

sealed interface LedgerSyncRemoteResult<out T> {
    data class Success<T>(val value: T) : LedgerSyncRemoteResult<T>
    data class Failure(val code: String?, val message: String, val retryable: Boolean) : LedgerSyncRemoteResult<Nothing>
}

interface LedgerSyncRepository {
    val available: Boolean
    val insecureHttpTestMode: Boolean
    suspend fun initialize(token: String, deviceId: String): LedgerSyncRemoteResult<LedgerSyncInitializeResult>
    suspend fun snapshot(token: String, offset: Int): LedgerSyncRemoteResult<LedgerSyncSnapshotPage>
    suspend fun push(
        token: String,
        deviceId: String,
        mutations: List<LedgerSyncMutation>
    ): LedgerSyncRemoteResult<LedgerSyncPushResult>
    suspend fun pull(token: String, deviceId: String, cursor: Long): LedgerSyncRemoteResult<LedgerSyncPullResult>
    suspend fun resolve(
        token: String,
        conflictId: String,
        expectedVersion: Long,
        choice: LedgerSyncConflictChoice
    ): LedgerSyncRemoteResult<LedgerSyncRecord>
}

fun interface LedgerDataChangeRegistration {
    fun close()
}

interface LedgerSyncLocalRepository :
    LedgerSyncLocalStateRepository,
    LedgerSyncMutationStore,
    LedgerSyncSnapshotStore

interface LedgerSyncLocalStateRepository {
    val state: Flow<LedgerSyncLocalState?>
    val outboxCount: Flow<Int>
    val conflicts: Flow<List<LedgerSyncConflict>>
    fun observeFormalDataChanges(onChange: () -> Unit): LedgerDataChangeRegistration
    suspend fun currentState(): LedgerSyncLocalState
    suspend fun formalRecordCount(): Int
    suspend fun pendingMutationCount(): Int
    suspend fun enable(profileKey: String)
    suspend fun disableAndUnbind()
    suspend fun pauseWithError(message: String)
    suspend fun markSuccess(cursor: Long)
    suspend fun reconcile()
}

interface LedgerSyncMutationStore {
    suspend fun listMutations(limit: Int): List<LedgerSyncMutation>
    suspend fun applyPushResults(results: List<LedgerSyncMutationResult>)
    suspend fun applyRemote(records: List<LedgerSyncRecord>, conflicts: List<LedgerSyncConflict>)
    suspend fun resolved(record: LedgerSyncRecord, conflictId: String)
}

interface LedgerSyncSnapshotStore {
    suspend fun replaceWithSnapshot(records: List<LedgerSyncRecord>)
    suspend fun switchProfileWithSnapshot(profileKey: String, records: List<LedgerSyncRecord>)
    suspend fun mergeSnapshot(records: List<LedgerSyncRecord>)
}
