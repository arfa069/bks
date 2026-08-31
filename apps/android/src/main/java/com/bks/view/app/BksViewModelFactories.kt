package com.bks.view.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountSessionCoordinator
import com.bks.model.domain.categorization.AiCategorizationClient
import com.bks.model.domain.categorization.AiCategorizationGateway
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.platform.app.BksAppContainer
import com.bks.viewmodel.ledger.LedgerViewModel
import com.bks.viewmodel.monitoring.AutomaticBookkeepingViewModel
import com.bks.viewmodel.review.ReviewAiDependencies
import com.bks.viewmodel.review.ReviewQueueViewModel
import com.bks.viewmodel.settings.DataAndBackupViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun ledgerFactory(
    repository: LedgerRepository,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
        repository = repository,
        cloudSettingsCoordinator = cloudSettingsCoordinator
    ) as T
}

internal fun reviewQueueFactory(
    persistence: ReviewQueueRepository,
    aiCategorizationGateway: AiCategorizationGateway,
    ledgerViewModel: LedgerViewModel,
    accountSessionCoordinator: AccountSessionCoordinator,
    cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewQueueViewModel(
        persistence = persistence,
        localDataReplacementInFlight = ledgerViewModel.localDataReplacementInFlight,
        aiDependencies = ReviewAiDependencies(
            client = AiCategorizationClient(aiCategorizationGateway),
            accountSessionState = accountSessionCoordinator.state,
            cloudSettingsState = cloudSettingsCoordinator.state
        )
    ) as T
}

internal fun dataAndBackupFactory(
    container: BksAppContainer,
    applicationOwners: BksApplicationOwners,
    ledgerViewModel: LedgerViewModel,
    reviewQueueViewModel: ReviewQueueViewModel,
    clearAvatarCache: () -> Unit
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DataAndBackupViewModel(
        exportEncryptedBackup = container.dataBackupRepository::exportEncryptedBackup,
        validateEncryptedBackup = container.dataBackupRepository::validateEncryptedBackup,
        importEncryptedBackup = container.dataBackupRepository::importEncryptedBackup,
        serializeWithSync = container.ledgerSyncCoordinator::withLocalDataReplacement,
        runLocalDataReplacement = ledgerViewModel::runLocalDataReplacement,
        clearLocalData = ledgerViewModel::clearLocalData,
        awaitReviewIdle = reviewQueueViewModel::awaitIdleAndResetForLocalDataReplacement,
        clearAvatarCache = clearAvatarCache,
        clearCloudSettingsLocalState = applicationOwners.cloudSettingsCoordinator::clearLocalState,
        disableSync = applicationOwners.ledgerSyncRuntime::disable,
        clearDiagnostics = {
            container.diagnosticLogs.clear(keepEnabledPreference = false)
        }
    ) as T
}

internal fun automaticBookkeepingFactory(
    repository: PreferencesRepository
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AutomaticBookkeepingViewModel(
            enabled = repository.userPreferences
                .map { it.automaticBookkeepingEnabled }
                .distinctUntilChanged(),
            persistEnabled = repository::setAutomaticBookkeepingEnabled
        ) as T
}
