package com.bks

import android.content.Context
import com.bks.data.local.BksDatabase
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.InstallationIdStore
import com.bks.feature.account.LocalModeSessionStore
import com.bks.feature.account.SecureAccountSessionStore
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.account.WechatAvatarCache
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.diagnostics.DiagnosticLogRepository
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.settings.LocalDataBackupRepository
import com.bks.feature.sync.HttpLedgerSyncRepository
import com.bks.feature.sync.LedgerSyncCoordinator
import com.bks.feature.sync.LedgerSyncLocalStore

internal data class BksLocalDependencies(
    val database: BksDatabase,
    val ledgerRepository: LocalLedgerRepository,
    val preferencesRepository: LocalPreferencesRepository,
    val dataBackupRepository: LocalDataBackupRepository,
    val reviewQueuePersistence: ReviewQueuePersistence
)

internal data class BksAccountDependencies(
    val accountRepository: AccountRepository,
    val localModeSessionStore: LocalModeSessionStore,
    val secureAccountSessionStore: SecureAccountSessionStore,
    val persistSessionOverride: ((com.bks.feature.account.AccountCredentials) -> Boolean)?,
    val wechatAuthGateway: WechatAuthGateway?,
    val wechatAvatarCache: WechatAvatarCache,
    val installationIdStore: InstallationIdStore
)

internal data class BksSyncDependencies(
    val localStore: LedgerSyncLocalStore,
    val coordinator: LedgerSyncCoordinator,
    val repository: HttpLedgerSyncRepository
)

internal data class BksAppDependencies(
    val context: Context,
    val local: BksLocalDependencies,
    val account: BksAccountDependencies,
    val sync: BksSyncDependencies,
    val aiCategorizationGateway: AiCategorizationGateway,
    val cloudAiSettingsGateway: CloudAiSettingsGateway,
    val diagnosticLogs: DiagnosticLogRepository
)
