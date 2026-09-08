package com.bks.model.platform.notifications

import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.review.ReviewQueueSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

internal fun reminderEntry(id: String, capturedAt: Long = 0): PendingEntry = BillSyncPipeline()
    .sync("支付成功\n￥12.00\n商户：测试商户", capturedAt, PaymentSource.ALIPAY).createdEntries.single().copy(id = id)

internal class ReminderTestRepository(entries: List<PendingEntry> = emptyList()) : ReviewQueueRepository {
    val snapshot = MutableStateFlow(ReviewQueueSnapshot(entries, nowEpochMillis = 0, todayStartEpochMillis = 0))
    fun setEntries(entries: List<PendingEntry>) { snapshot.value = snapshot.value.copy(pendingEntries = entries) }
    override fun observeSnapshot() = snapshot
    override suspend fun loadSnapshot() = snapshot.value
    override suspend fun ensureSystemCategories() = Unit
    override suspend fun loadCategories(): List<Category> = emptyList()
    override suspend fun insertCapturedEntries(entries: List<PendingEntry>) = entries
    override suspend fun applyCategorizationRulesToUnclassified(rules: List<CategorizationRule>) = 0
    override suspend fun execute(command: ReviewPersistenceCommand) = ReviewPersistenceResult.NotFound
}
