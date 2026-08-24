package com.bks.feature.billsync

import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.review.ReviewQueueCaptureCoordinator
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.review.applyCategorizationSuggestion
import kotlinx.coroutines.flow.first

class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val captureCoordinator: ReviewQueueCaptureCoordinator = ReviewQueueCaptureCoordinator.Shared
) {
    suspend fun process(pageText: String): BillSyncResult = persist(recognize(pageText))

    internal fun recognize(pageText: String): BillSyncResult = pipeline.sync(
        pageText = pageText,
        capturedAtEpochMillis = clock()
    )

    internal suspend fun persist(result: BillSyncResult): BillSyncResult {
        if (!result.recognized) return result

        return captureCoordinator.serialize {
            reviewQueuePersistence.ensureSystemCategories()
            val rules = preferencesRepository.categorizationRules.first()
            val createdEntries = result.createdEntries.map { it.applyCategorizationSuggestion(rules) }
            if (createdEntries.isNotEmpty()) {
                reviewQueuePersistence.insertCapturedEntries(createdEntries)
            }
            result.copy(createdEntries = createdEntries)
        }
    }
}
