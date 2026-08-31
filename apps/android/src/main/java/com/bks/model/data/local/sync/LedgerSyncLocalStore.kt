@file:Suppress("TooManyFunctions")

package com.bks.model.data.local.sync

import com.bks.model.data.remote.sync.toContract
import com.bks.model.data.remote.sync.toDomain
import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategoryDao
import com.bks.model.data.local.database.FundingAccountDao
import com.bks.model.data.local.database.LedgerBookDao
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerSyncDao
import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncConflictContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncRecordContract
import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.AccountSyncConflictEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.domain.sync.LedgerDataChangeRegistration
import com.bks.model.domain.sync.LedgerSyncConflict
import com.bks.model.domain.sync.LedgerSyncEntityType
import com.bks.model.domain.sync.LedgerSyncLocalRepository
import com.bks.model.domain.sync.LedgerSyncLocalState
import com.bks.model.domain.sync.LedgerSyncMutation
import com.bks.model.domain.sync.LedgerSyncMutationResult
import com.bks.model.domain.sync.LedgerSyncRecord
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LedgerSyncLocalStore(
    internal val database: BksDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : LedgerSyncLocalRepository {
    private val recordApplier = LedgerSyncRecordApplier(database, clock, idGenerator)
    private val snapshotOperations = LedgerSyncSnapshotOperations(
        database = database,
        recordApplier = recordApplier,
        clock = clock,
        idGenerator = idGenerator
    )

    override val state: Flow<LedgerSyncLocalState?> =
        database.ledgerSyncDao().observeState().map { it?.toDomain() }
    override val outboxCount: Flow<Int> = database.ledgerSyncDao().observeOutboxCount()
    override val conflicts: Flow<List<LedgerSyncConflict>> =
        database.ledgerSyncDao().observeConflicts().map { values -> values.map { it.toDomain() } }

    override fun observeFormalDataChanges(onChange: () -> Unit): LedgerDataChangeRegistration {
        val observer = object : InvalidationTracker.Observer(SYNCED_TABLES) {
            override fun onInvalidated(tables: Set<String>) = onChange()
        }
        database.invalidationTracker.addObserver(observer)
        return LedgerDataChangeRegistration { database.invalidationTracker.removeObserver(observer) }
    }

    override suspend fun currentState(): LedgerSyncLocalState =
        (database.ledgerSyncDao().getState() ?: AccountSyncStateEntity()).toDomain()

    override suspend fun formalRecordCount(): Int = database.withTransaction {
        database.categoryDao().getAllCategories().size +
            database.fundingAccountDao().getAllFundingAccounts().size +
            database.ledgerBookDao().getAll().size +
            database.ledgerEntryDao().listAllLedgerEntries().size +
            database.categorizationRuleDao().listRules().size
    }

    override suspend fun pendingMutationCount(): Int = database.ledgerSyncDao().outboxCount()

    override suspend fun enable(profileKey: String) = database.withTransaction {
        require(profileKey.isNotBlank())
        val current = database.ledgerSyncDao().getState()
        require(current?.profileKey == null || current.profileKey == profileKey) {
            "Local ledger is bound to another account"
        }
        recordApplier.ensureFundingSyncIds()
        database.ledgerSyncDao().upsertState(
            (current ?: AccountSyncStateEntity()).copy(profileKey = profileKey, enabled = true, lastError = null)
        )
    }

    override suspend fun disableAndUnbind() = database.withTransaction {
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }

    override suspend fun pauseWithError(message: String) {
        val current = database.ledgerSyncDao().getState() ?: AccountSyncStateEntity()
        database.ledgerSyncDao().upsertState(current.copy(lastError = message))
    }

    override suspend fun markSuccess(cursor: Long) {
        val current = database.ledgerSyncDao().getState() ?: AccountSyncStateEntity()
        database.ledgerSyncDao().upsertState(
            current.copy(cursor = cursor, lastSuccessAtMillis = clock(), lastError = null)
        )
    }

    override suspend fun reconcile() = database.withTransaction {
        if (database.ledgerSyncDao().getState()?.enabled != true) return@withTransaction
        recordApplier.ensureFundingSyncIds()
        snapshotOperations.reconcileOutbox()
    }

    override suspend fun listMutations(limit: Int): List<LedgerSyncMutation> =
        database.ledgerSyncDao().listOutbox(limit).map { it.toContract().toDomain() }

    override suspend fun applyPushResults(results: List<LedgerSyncMutationResult>) =
        database.withTransaction {
            snapshotOperations.applyPushResults(results.map { it.toContract() })
        }

    override suspend fun applyRemote(
        records: List<LedgerSyncRecord>,
        conflicts: List<LedgerSyncConflict>
    ) = database.withTransaction {
        snapshotOperations.applyRemote(records.map { it.toContract() }, conflicts.map { it.toContract() })
    }

    override suspend fun replaceWithSnapshot(records: List<LedgerSyncRecord>) =
        database.withTransaction {
            snapshotOperations.replaceFormalData(records.map { it.toContract() }, resetActiveLedger = false)
        }

    override suspend fun switchProfileWithSnapshot(
        profileKey: String,
        records: List<LedgerSyncRecord>
    ) = database.withTransaction {
        require(profileKey.isNotBlank())
        check(database.ledgerSyncDao().outboxCount() == 0) {
            "Pending sync mutations must be uploaded first"
        }
        database.ledgerSyncDao().upsertState(
            AccountSyncStateEntity(profileKey = profileKey, enabled = true)
        )
        snapshotOperations.replaceFormalData(records.map { it.toContract() }, resetActiveLedger = true)
    }

    override suspend fun mergeSnapshot(records: List<LedgerSyncRecord>) =
        database.withTransaction {
            snapshotOperations.mergeSnapshot(records.map { it.toContract() })
        }

    override suspend fun resolved(record: LedgerSyncRecord, conflictId: String) =
        database.withTransaction {
            snapshotOperations.resolved(record.toContract(), conflictId)
        }

    private companion object {
        val SYNCED_TABLES = arrayOf(
            "categories",
            "funding_accounts",
            "ledger_books",
            "ledger_entries",
            "categorization_rules"
        )
    }
}

private fun AccountSyncStateEntity.toDomain() = LedgerSyncLocalState(
    profileKey = profileKey,
    enabled = enabled,
    cursor = cursor,
    lastSuccessAtMillis = lastSuccessAtMillis,
    lastError = lastError
)

private fun AccountSyncConflictEntity.toDomain(): LedgerSyncConflict {
    val contractType = LedgerSyncEntityTypeContract.valueOf(entityType)
    return LedgerSyncConflict(
        conflictId = conflictId,
        entityType = LedgerSyncEntityType.valueOf(entityType),
        entityId = entityId,
        canonicalVersion = canonicalVersion,
        canonicalDeleted = canonicalDeleted,
        canonicalPayload = canonicalPayload?.let {
            LedgerSyncJsonContracts.parsePayload(contractType, it).toDomain()
        },
        candidateDeleted = candidateDeleted,
        candidatePayload = candidatePayload?.let {
            LedgerSyncJsonContracts.parsePayload(contractType, it).toDomain()
        },
        createdAtMillis = createdAtMillis
    )
}
