package com.bks.model.data.remote.sync

import com.bks.api.LedgerSyncInitializeResponseContract
import com.bks.api.LedgerSyncPullResponseContract
import com.bks.api.LedgerSyncPushResponseContract
import com.bks.api.LedgerSyncSnapshotResponseContract
import com.bks.model.domain.sync.LedgerSyncInitializeResult
import com.bks.model.domain.sync.LedgerSyncPullResult
import com.bks.model.domain.sync.LedgerSyncPushResult
import com.bks.model.domain.sync.LedgerSyncSnapshotPage

internal fun LedgerSyncInitializeResponseContract.toDomain() =
    LedgerSyncInitializeResult(profileKey, recordCount, currentCursor)

internal fun LedgerSyncSnapshotResponseContract.toDomain() =
    LedgerSyncSnapshotPage(records.map { it.toDomain() }, nextOffset, currentCursor)

internal fun LedgerSyncPushResponseContract.toDomain() =
    LedgerSyncPushResult(results.map { it.toDomain() }, currentCursor)

internal fun LedgerSyncPullResponseContract.toDomain() = LedgerSyncPullResult(
    records.map { it.toDomain() }, conflicts.map { it.toDomain() }, nextCursor, hasMore
)
