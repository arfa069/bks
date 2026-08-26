package com.bks

import android.content.Context
import androidx.activity.ComponentActivity
import com.bks.data.local.BksDatabase
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.account.AccountCloudSettingsCoordinator
import com.bks.feature.account.AndroidWechatAuthGateway
import com.bks.feature.account.AccountSessionCoordinator
import com.bks.feature.account.HttpAccountRepository
import com.bks.feature.account.InstallationIdStore
import com.bks.feature.account.LocalModeSessionStore
import com.bks.feature.account.SecureAccountSessionStore
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.account.WechatAvatarCache
import com.bks.feature.billsync.BillSyncCaptureCoordinator
import com.bks.feature.billsync.BillSyncCaptureOperations
import com.bks.feature.billsync.BillSyncCaptureProcessor
import com.bks.feature.billsync.BillSyncPipeline
import com.bks.feature.billsync.BillSyncStateCoordinator
import com.bks.feature.categorization.HttpAiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.categorization.HttpCloudAiSettingsGateway
import com.bks.feature.diagnostics.DiagnosticLogRepository
import com.bks.feature.diagnostics.DiagnosticLogs
import com.bks.feature.diagnostics.DiagnosticLogsViewModel
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.settings.LocalDataBackupRepository
import com.bks.feature.sync.HttpLedgerSyncRepository
import com.bks.feature.sync.AndroidLedgerSyncSchedulingGateway
import com.bks.feature.sync.LedgerSyncCoordinator
import com.bks.feature.sync.LedgerSyncLocalStore
import com.bks.feature.sync.LedgerSyncRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

internal class BksAppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val billSyncCoordinatorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: BksDatabase by lazy { BksDatabaseProvider.get(applicationContext) }
    val ledgerRepository: LocalLedgerRepository by lazy { LocalLedgerRepository(database) }
    val preferencesRepository: LocalPreferencesRepository by lazy { LocalPreferencesRepository(database) }
    val dataBackupRepository: LocalDataBackupRepository by lazy { LocalDataBackupRepository(database) }
    val reviewQueuePersistence: ReviewQueuePersistence by lazy {
        ReviewQueuePersistence(ledgerRepository)
    }
    val ledgerSyncLocalStore: LedgerSyncLocalStore by lazy { LedgerSyncLocalStore(database) }
    val localModeSessionStore: LocalModeSessionStore by lazy { LocalModeSessionStore(applicationContext) }
    val secureAccountSessionStore: SecureAccountSessionStore by lazy {
        SecureAccountSessionStore(applicationContext)
    }
    val installationIdStore: InstallationIdStore by lazy { InstallationIdStore(applicationContext) }
    val accountRepository: HttpAccountRepository by lazy {
        HttpAccountRepository(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            installationId = installationIdStore::getOrCreate,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val aiCategorizationGateway: HttpAiCategorizationGateway by lazy {
        HttpAiCategorizationGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val cloudAiSettingsGateway: HttpCloudAiSettingsGateway by lazy {
        HttpCloudAiSettingsGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val ledgerSyncRepository: HttpLedgerSyncRepository by lazy {
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
    val wechatAvatarCache: WechatAvatarCache by lazy { WechatAvatarCache(applicationContext) }
    val diagnosticLogs: DiagnosticLogRepository by lazy { DiagnosticLogs.get(applicationContext) }
    val accountSessionCoordinator: AccountSessionCoordinator by lazy {
        createAccountSessionCoordinator(BksAppOverrides())
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
            diagnosticRecorder = diagnosticLogs
        )
    }

    fun resolveAccountSessionCoordinator(
        overrides: BksAppOverrides
    ): AccountSessionCoordinator = if (overrides.hasAccountSessionOverrides()) {
        createAccountSessionCoordinator(overrides).also { it.start() }
    } else {
        accountSessionCoordinator
    }

    fun resolveAccountCloudSettingsCoordinator(
        overrides: BksAppOverrides,
        sessionCoordinator: AccountSessionCoordinator
    ): AccountCloudSettingsCoordinator = if (overrides.hasCloudSettingsOverrides()) {
        createAccountCloudSettingsCoordinator(
            sessionCoordinator = sessionCoordinator,
            gateway = overrides.cloudAiSettingsGateway ?: cloudAiSettingsGateway
        ).also { it.start() }
    } else {
        accountCloudSettingsCoordinator
    }

    fun resolveLedgerSyncRuntime(
        overrides: BksAppOverrides,
        sessionCoordinator: AccountSessionCoordinator
    ): LedgerSyncRuntime = if (overrides.hasAccountSessionOverrides()) {
        createLedgerSyncRuntime(sessionCoordinator).also { it.start() }
    } else {
        ledgerSyncRuntime
    }

    fun createBillSyncCaptureProcessor(): BillSyncCaptureProcessor = BillSyncCaptureProcessor(
        pipeline = BillSyncPipeline(),
        reviewQueuePersistence = ReviewQueuePersistence(ledgerRepository),
        preferencesRepository = preferencesRepository
    )

    fun createDiagnosticLogsViewModel(): DiagnosticLogsViewModel =
        DiagnosticLogsViewModel(diagnosticLogs)

    fun createBillSyncStateCoordinator(activity: ComponentActivity): BillSyncStateCoordinator =
        BillSyncStateCoordinator(activity)

    private fun createAccountSessionCoordinator(overrides: BksAppOverrides): AccountSessionCoordinator =
        AccountSessionCoordinator(
            accountRepository = overrides.accountRepository ?: accountRepository,
            secureSessionStore = secureAccountSessionStore,
            localModeSessionStore = localModeSessionStore,
            applicationScope = applicationScope,
            persistSession = overrides.persistAccountSession ?: secureAccountSessionStore::save,
            onCredentialsAccepted = { credentials -> wechatAvatarCache.prepareUrl(credentials.avatarUrl) },
            onMoveToLocalMode = {
                wechatAvatarCache.clear()
            }
        )

    private fun createAccountCloudSettingsCoordinator(
        sessionCoordinator: AccountSessionCoordinator,
        gateway: CloudAiSettingsGateway
    ): AccountCloudSettingsCoordinator = AccountCloudSettingsCoordinator(
        accountSessionState = sessionCoordinator.state,
        preferencesRepository = preferencesRepository,
        fundingAccountRepository = ledgerRepository,
        cloudGateway = gateway,
        applicationScope = applicationScope
    )

    private fun createLedgerSyncRuntime(
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

private fun BksAppOverrides.hasAccountSessionOverrides(): Boolean =
    accountRepository != null || persistAccountSession != null

private fun BksAppOverrides.hasCloudSettingsOverrides(): Boolean =
    hasAccountSessionOverrides() || cloudAiSettingsGateway != null

internal val Context.bksAppContainer: BksAppContainer
    get() = (applicationContext as BksApplication).appContainer
