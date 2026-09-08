package com.bks.model.application.billsync

import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.application.review.ReviewQueueCaptureCoordinator
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.categorization.applyCategorizationSuggestion
import com.bks.model.domain.notifications.ReviewNotificationSink
import kotlinx.coroutines.flow.first

class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueueRepository,
    private val preferencesRepository: PreferencesRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val captureCoordinator: ReviewQueueCaptureCoordinator = ReviewQueueCaptureCoordinator.Shared,
    private val notifications: ReviewNotificationSink = ReviewNotificationSink { }
) {
    suspend fun process(pageText: String, paymentSource: PaymentSource): BillSyncResult =
        persist(recognize(pageText, paymentSource))

    internal fun recognize(
        pageText: String,
        paymentSource: PaymentSource
    ): BillSyncResult = pipeline.sync(
        pageText = pageText,
        capturedAtEpochMillis = clock(),
        paymentSource = paymentSource
    )

    internal suspend fun persist(result: BillSyncResult): BillSyncResult {
        if (!result.recognized) return result

        val (persisted, inserted) = captureCoordinator.serialize {
            reviewQueuePersistence.ensureSystemCategories()
            val rules = preferencesRepository.categorizationRules.first()
            val categories = reviewQueuePersistence.loadCategories()
            val createdEntries = result.createdEntries.map {
                it.applyCategorizationSuggestion(rules, categories)
            }
            val insertedEntries = if (createdEntries.isEmpty()) {
                emptyList()
            } else {
                reviewQueuePersistence.insertCapturedEntries(createdEntries)
            }
            result.copy(createdEntries = createdEntries, insertedCount = insertedEntries.size) to insertedEntries
        }
        // No notification permission, IPC or asynchronous work runs inside the capture lock.
        // A failed handoff must never make a committed capture look like a failed insert.
        if (inserted.isNotEmpty()) runCatching { notifications.enqueue(inserted) }
        return persisted
    }
}
