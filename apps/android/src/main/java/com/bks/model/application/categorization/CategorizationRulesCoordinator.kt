package com.bks.model.application.categorization

import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.settings.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CategorizationRulesCoordinator(
    private val preferencesRepository: PreferencesRepository,
    private val reviewQueueRepository: ReviewQueueRepository
) {
    private val updateMutex = Mutex()

    suspend fun updateRules(update: CategorizationRulesUpdate) = updateMutex.withLock {
        preferencesRepository.updateCategorizationRules(update)
        applyCurrentRulesToUnclassifiedLocked()
    }

    suspend fun applyCurrentRulesToUnclassified(): Int = updateMutex.withLock {
        applyCurrentRulesToUnclassifiedLocked()
    }

    private suspend fun applyCurrentRulesToUnclassifiedLocked(): Int =
        reviewQueueRepository.applyCategorizationRulesToUnclassified(
            preferencesRepository.categorizationRules.first()
        )
}
