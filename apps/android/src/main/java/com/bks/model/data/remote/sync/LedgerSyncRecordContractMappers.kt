package com.bks.model.data.remote.sync

import com.bks.api.LedgerSyncConflictContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncRecordContract
import com.bks.model.domain.sync.LedgerSyncConflict
import com.bks.model.domain.sync.LedgerSyncMutation
import com.bks.model.domain.sync.LedgerSyncMutationResult
import com.bks.model.domain.sync.LedgerSyncRecord

internal fun LedgerSyncRecordContract.toDomain() = LedgerSyncRecord(
    entityType.toDomain(), entityId, version, revision, deleted, payload?.toDomain()
)

internal fun LedgerSyncRecord.toContract() = LedgerSyncRecordContract(
    entityType.toContract(), entityId, version, revision, deleted, payload?.toContract()
)

internal fun LedgerSyncMutationContract.toDomain() = LedgerSyncMutation(
    mutationId, entityType.toDomain(), entityId, baseVersion, deleted, payload?.toDomain()
)

internal fun LedgerSyncMutation.toContract() = LedgerSyncMutationContract(
    mutationId, entityType.toContract(), entityId, baseVersion, deleted, payload?.toContract()
)

internal fun LedgerSyncMutationResultContract.toDomain() = LedgerSyncMutationResult(
    mutationId, accepted, version, revision, conflictId, canonicalEntityId
)

internal fun LedgerSyncMutationResult.toContract() = LedgerSyncMutationResultContract(
    mutationId, accepted, version, revision, conflictId, canonicalEntityId
)

internal fun LedgerSyncConflictContract.toDomain() = LedgerSyncConflict(
    conflictId, entityType.toDomain(), entityId, canonicalVersion, canonicalDeleted,
    canonicalPayload?.toDomain(), candidateDeleted, candidatePayload?.toDomain(), createdAtMillis
)

internal fun LedgerSyncConflict.toContract() = LedgerSyncConflictContract(
    conflictId, entityType.toContract(), entityId, canonicalVersion, canonicalDeleted,
    canonicalPayload?.toContract(), candidateDeleted, candidatePayload?.toContract(), createdAtMillis
)
