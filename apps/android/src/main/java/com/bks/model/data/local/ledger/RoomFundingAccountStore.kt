package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.data.local.sync.LocalSyncMutationRecorder
import com.bks.model.domain.ledger.FundingAccountSourceScope
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.FundingAccountRemovalResult
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.domain.ledger.toFundingAccountSourceScope
import androidx.room.withTransaction

@Suppress("TooManyFunctions")
internal class RoomFundingAccountStore(
    private val database: BksDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) {
    private val syncRecorder = LocalSyncMutationRecorder(database, clock, idGenerator)
    val fundingAccounts = database.fundingAccountDao().observeFundingAccounts()

    suspend fun setDefaultFundingAccount(fundingAccountId: Long?) = database.withTransaction {
        val syncId = fundingAccountId?.let {
            requireNotNull(database.fundingAccountDao().getById(it)) { "Funding account not found: $it" }.syncId
        }
        database.localSettingsDao().upsert(
            requireNotNull(database.localSettingsDao().getById()).copy(defaultFundingAccountSyncId = syncId)
        )
    }

    suspend fun getDefaultFundingAccountSyncId(): String? =
        database.localSettingsDao().getById()?.defaultFundingAccountSyncId

    suspend fun createFundingAccount(
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        require(database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel) == null) {
            "Funding account already exists for this payment source"
        }
        val account = FundingAccountEntity(
            syncId = idGenerator(),
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        require(id != -1L) { "Funding account already exists for this payment source" }
        account.copy(id = id).also { syncRecorder.record(it) }
    }

    suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val existing = requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
            "Funding account not found: $fundingAccountId"
        }
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        val duplicate = database.fundingAccountDao().findByScopeAndLabel(
            sourceScope,
            normalizedLabel
        )
        require(duplicate == null || duplicate.id == existing.id) {
            "Funding account already exists for this payment source"
        }
        check(
            database.fundingAccountDao().update(
                id = existing.id,
                sourceScope = sourceScope,
                paymentSource = paymentSource,
                label = normalizedLabel
            ) == 1
        )
        existing.copy(
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel
        ).also { syncRecorder.record(it) }
    }

    suspend fun deleteFundingAccount(
        fundingAccountId: Long
    ): FundingAccountRemovalResult = database.withTransaction {
        if (database.fundingAccountDao().getById(fundingAccountId) == null) {
            return@withTransaction FundingAccountRemovalResult.NotFound
        }
        val activeLedgerEntryCount =
            database.ledgerEntryDao().countActiveByFundingAccountId(fundingAccountId)
        val deletedLedgerEntryCount =
            database.ledgerEntryDao().countDeletedByFundingAccountId(fundingAccountId)
        val pendingEntryCount =
            database.pendingEntryDao().countByFundingAccountId(fundingAccountId)
        val ignoredEntryCount =
            database.ignoredEntryDao().countByFundingAccountId(fundingAccountId)
        val hasLedgerReference = activeLedgerEntryCount > 0 || deletedLedgerEntryCount > 0
        val hasReviewReference = pendingEntryCount > 0 || ignoredEntryCount > 0
        if (hasLedgerReference || hasReviewReference) {
            return@withTransaction FundingAccountRemovalResult.Referenced(
                activeLedgerEntryCount = activeLedgerEntryCount,
                deletedLedgerEntryCount = deletedLedgerEntryCount,
                pendingEntryCount = pendingEntryCount,
                ignoredEntryCount = ignoredEntryCount
            )
        }
        val target = requireNotNull(database.fundingAccountDao().getById(fundingAccountId))
        if (target.syncId != null && database.localSettingsDao().getById()?.defaultFundingAccountSyncId == target.syncId) {
            database.localSettingsDao().upsert(
                requireNotNull(database.localSettingsDao().getById()).copy(defaultFundingAccountSyncId = null)
            )
        }
        check(database.fundingAccountDao().deleteById(fundingAccountId) == 1)
        target.syncId?.let {
            syncRecorder.recordDelete(
                com.bks.api.LedgerSyncEntityTypeContract.FUNDING_ACCOUNT,
                it
            )
        }
        FundingAccountRemovalResult.Deleted
    }

    internal suspend fun resolve(input: LedgerEntryCommand): Long? {
        require(input.fundingAccountId == null || input.newFundingAccountLabel.isNullOrBlank()) {
            "Choose an existing funding account or create a new one, not both"
        }
        input.fundingAccountId?.let { fundingAccountId ->
            requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
                "Funding account not found: $fundingAccountId"
            }
            return defaultFundingAccountIdOr(fundingAccountId)
        }
        val label = input.newFundingAccountLabel?.trim().orEmpty()
        if (label.isEmpty()) {
            return defaultFundingAccountIdOr(null)
        }
        val scope = fundingAccountScope(input.paymentSource)
        val existing = database.fundingAccountDao().findByScopeAndLabel(scope, label)
        if (existing != null) {
            return defaultFundingAccountIdOr(existing.id)
        }
        val account = FundingAccountEntity(
            syncId = idGenerator(),
            sourceScope = scope,
            paymentSource = input.paymentSource,
            label = label,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        return if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findByScopeAndLabel(scope, label)).id
        } else {
            syncRecorder.record(account.copy(id = id))
            defaultFundingAccountIdOr(id)
        }
    }

    private suspend fun defaultFundingAccountIdOr(fallback: Long?): Long? {
        val syncId = database.localSettingsDao().getById()?.defaultFundingAccountSyncId ?: return fallback
        return database.fundingAccountDao().findBySyncId(syncId)?.id ?: fallback
    }

    internal suspend fun resolvePending(pending: PendingEntryEntity): Long? {
        pending.fundingAccountId?.let { fundingAccountId ->
            if (database.fundingAccountDao().getById(fundingAccountId) != null) {
                return fundingAccountId
            }
        }
        val normalizedLabel = pending.fundingAccountLabel?.trim().orEmpty()
        if (normalizedLabel.isEmpty()) {
            return null
        }
        return database.fundingAccountDao().findByScopeAndLabel(
            sourceScope = fundingAccountScope(pending.source),
            label = normalizedLabel
        )?.id
    }

    private fun normalizeFundingAccountLabel(label: String): String =
        label.trim().also {
            require(it.isNotEmpty()) { "Funding account label is required" }
        }

    private fun fundingAccountScope(
        paymentSource: PaymentSource?
    ): FundingAccountSourceScope =
        paymentSource?.toFundingAccountSourceScope() ?: FundingAccountSourceScope.USER
}
