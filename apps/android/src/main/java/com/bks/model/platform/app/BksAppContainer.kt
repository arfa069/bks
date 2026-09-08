package com.bks.model.platform.app

import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountSessionStore
import com.bks.model.domain.account.LocalModeStore
import com.bks.model.domain.categorization.AiCategorizationGateway
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.domain.sync.LedgerSyncRepository
import android.content.Context
import com.bks.BuildConfig
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.BksDatabaseProvider
import com.bks.model.data.local.ledger.RoomLedgerRepository
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.data.local.settings.RoomPreferencesRepository
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.domain.settings.DataBackupRepository
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.platform.account.AndroidWechatAuthGateway
import com.bks.model.application.account.AccountSessionCoordinator
import com.bks.model.data.remote.account.HttpAccountRepository
import com.bks.model.data.local.account.InstallationIdStore
import com.bks.model.data.local.account.LocalModeSessionStore
import com.bks.model.data.local.account.SecureAccountSessionStore
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.platform.billsync.BillSyncCaptureCoordinator
import com.bks.model.platform.billsync.BillSyncCaptureOperations
import com.bks.model.application.billsync.BillSyncCaptureProcessor
import com.bks.model.application.categorization.CategorizationRulesCoordinator
import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.data.remote.categorization.HttpAiCategorizationGateway
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.model.data.remote.categorization.HttpCloudAiSettingsGateway
import com.bks.model.domain.diagnostics.DiagnosticLogRepository
import com.bks.model.data.local.diagnostics.DiagnosticLogs
import com.bks.model.data.local.review.RoomReviewQueueRepository
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.data.local.backup.LocalDataBackupRepository
import com.bks.model.data.remote.sync.HttpLedgerSyncRepository
import com.bks.model.platform.sync.AndroidLedgerSyncSchedulingGateway
import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.application.sync.LedgerSyncRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import com.bks.model.data.local.settings.LocalNotificationPreferences
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import com.bks.model.platform.notifications.ReviewNotificationPublisher
import com.bks.model.platform.notifications.ReviewNotificationRuntime
import com.bks.model.data.local.settings.LocalAttentionReminderState
import com.bks.model.platform.notifications.AttentionReminderCoordinator
import com.bks.model.platform.notifications.AttentionReminderRuntime
import com.bks.model.platform.notifications.AttentionNotificationPublisher
import com.bks.model.platform.notifications.BacklogReminderScheduler
import com.bks.model.platform.notifications.BookkeepingHealthObserver

internal class BksAppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val billSyncCoordinatorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val billSyncCaptureDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val database: BksDatabase by lazy { BksDatabaseProvider.get(applicationContext) }
    val ledgerRepository: LedgerRepository by lazy { RoomLedgerRepository(database) }
    val preferencesRepository: PreferencesRepository by lazy { RoomPreferencesRepository(database) }
    val dataBackupRepository: DataBackupRepository by lazy { LocalDataBackupRepository(database) }
    val reviewQueuePersistence: ReviewQueueRepository by lazy {
        RoomReviewQueueRepository(database)
    }
    val notificationPreferences: NotificationPreferencesRepository by lazy {
        LocalNotificationPreferences(applicationContext)
    }
    val reviewNotificationRuntime: ReviewNotificationRuntime by lazy {
        ReviewNotificationRuntime(
            applicationScope, reviewQueuePersistence, notificationPreferences,
            ReviewNotificationPublisher(applicationContext)
        )
    }
    val categorizationRulesCoordinator: CategorizationRulesCoordinator by lazy {
        CategorizationRulesCoordinator(preferencesRepository, reviewQueuePersistence)
    }
    val attentionReminderCoordinator: AttentionReminderCoordinator by lazy {
        AttentionReminderCoordinator(reviewQueuePersistence, notificationPreferences,
            LocalAttentionReminderState(applicationContext), AttentionNotificationPublisher(applicationContext))
    }
    val attentionReminderRuntime: AttentionReminderRuntime by lazy {
        AttentionReminderRuntime(applicationScope,
            BookkeepingHealthObserver(applicationContext,
                preferencesRepository.userPreferences.map { it.automaticBookkeepingEnabled }).observe(),
            notificationPreferences, reviewQueuePersistence, attentionReminderCoordinator
        ) { enabled -> BacklogReminderScheduler.setEnabled(applicationContext, enabled) }
    }
    val ledgerSyncLocalStore: com.bks.model.domain.sync.LedgerSyncLocalRepository by lazy {
        LedgerSyncLocalStore(database)
    }
    val localModeSessionStore: LocalModeStore by lazy { LocalModeSessionStore(applicationContext) }
    val secureAccountSessionStore: AccountSessionStore by lazy {
        SecureAccountSessionStore(applicationContext)
    }
    private val installationIdStore: InstallationIdStore by lazy {
        InstallationIdStore(applicationContext)
    }
    val accountRepository: AccountRepository by lazy {
        HttpAccountRepository(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            installationId = installationIdStore::getOrCreate,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val aiCategorizationGateway: AiCategorizationGateway by lazy {
        HttpAiCategorizationGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val cloudAiSettingsGateway: CloudAiSettingsGateway by lazy {
        HttpCloudAiSettingsGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val ledgerSyncRepository: LedgerSyncRepository by lazy {
        HttpLedgerSyncRepository(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val wechatAuthGateway: WechatAuthGateway? by lazy {
        BuildConfig.BKS_WECHAT_APP_ID
            .takeIf(String::isNotBlank)
            ?.let { appId -> AndroidWechatAuthGateway(applicationContext, appId) }
    }
    val diagnosticLogs: DiagnosticLogRepository by lazy { DiagnosticLogs.get(applicationContext) }
    val accountSessionCoordinator: AccountSessionCoordinator by lazy {
        createAccountSessionCoordinator()
    }
    val accountCloudSettingsCoordinator: AccountCloudSettingsCoordinator by lazy {
        createAccountCloudSettingsCoordinator(
            sessionCoordinator = accountSessionCoordinator,
            gateway = cloudAiSettingsGateway
        )
    }
    val ledgerSyncCoordinator: LedgerSyncCoordinator by lazy {
        LedgerSyncCoordinator(
            localStore = ledgerSyncLocalStore,
            repository = ledgerSyncRepository,
            deviceId = installationIdStore::getOrCreate
        )
    }
    val ledgerSyncRuntime: LedgerSyncRuntime by lazy {
        createLedgerSyncRuntime(accountSessionCoordinator)
    }
    val billSyncCaptureCoordinator: BillSyncCaptureCoordinator by lazy {
        val processor = createBillSyncCaptureProcessor()
        BillSyncCaptureCoordinator(
            context = applicationContext,
            enabledIntent = preferencesRepository.userPreferences.map {
                it.automaticBookkeepingEnabled
            },
            processor = BillSyncCaptureOperations(
                recognize = processor::recognize,
                persist = processor::persist
            ),
            applicationScope = billSyncCoordinatorScope,
            captureDispatcher = billSyncCaptureDispatcher,
            diagnosticRecorder = diagnosticLogs
        )
    }

    fun createBillSyncCaptureProcessor(): BillSyncCaptureProcessor = BillSyncCaptureProcessor(
        pipeline = BillSyncPipeline(),
        reviewQueuePersistence = RoomReviewQueueRepository(database),
        preferencesRepository = preferencesRepository,
        notifications = reviewNotificationRuntime
    )

    fun createAccountSessionCoordinator(
        repository: AccountRepository = accountRepository,
        persistSession: ((AccountCredentials) -> Boolean)? = null
    ): AccountSessionCoordinator =
        AccountSessionCoordinator(
            accountRepository = repository,
            secureSessionStore = secureAccountSessionStore,
            localModeSessionStore = localModeSessionStore,
            applicationScope = applicationScope,
            persistSession = persistSession ?: secureAccountSessionStore::save
        )

    fun createAccountCloudSettingsCoordinator(
        sessionCoordinator: AccountSessionCoordinator,
        gateway: CloudAiSettingsGateway
    ): AccountCloudSettingsCoordinator = AccountCloudSettingsCoordinator(
        accountSessionState = sessionCoordinator.state,
        preferencesRepository = preferencesRepository,
        fundingAccountRepository = ledgerRepository,
        cloudGateway = gateway,
        applicationScope = applicationScope
    )

    fun createLedgerSyncRuntime(
        sessionCoordinator: AccountSessionCoordinator
    ): LedgerSyncRuntime = LedgerSyncRuntime(
        localStore = ledgerSyncLocalStore,
        coordinator = ledgerSyncCoordinator,
        accountSessionState = sessionCoordinator.state,
        scheduler = AndroidLedgerSyncSchedulingGateway(applicationContext),
        applicationScope = applicationScope,
        insecureHttpTestMode = ledgerSyncRepository.insecureHttpTestMode
    )
}

internal val Context.bksAppContainer: BksAppContainer
    get() = (applicationContext as BksApplication).appContainer
