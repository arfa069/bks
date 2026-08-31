package com.bks.model.application.account

import com.bks.model.domain.account.AccountDeletionState
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountCloudSettingsOperationResult
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.application.account.settingsSyncInFlight
import com.bks.model.data.local.ledger.FundingAccountRepository
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.ledger.FundingAccount
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.domain.settings.UserPreferences
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.ledger.LocalLedgerRepository
import com.bks.model.data.local.settings.LocalPreferencesRepository
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.model.domain.categorization.CloudAiSettingsGatewayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AccountCloudSettingsCoordinatorTest {
    private lateinit var database: BksDatabase
    private lateinit var preferencesRepository: LocalPreferencesRepository
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var sessionState: MutableStateFlow<AccountSessionCoordinatorState>
    private lateinit var gateway: TestCloudSettingsGateway

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesRepository = LocalPreferencesRepository(database)
        ledgerRepository = LocalLedgerRepository(database, idGenerator = { "funding-sync-id" })
        sessionState = MutableStateFlow(AccountSessionCoordinatorState(isRestoring = false))
        gateway = TestCloudSettingsGateway()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun tokenSwitchLoadsOnlyTheCurrentTokenSettings() = runTest {
        gateway.readResult = { token ->
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(aiConsentGranted = token == "token-b")
            )
        }
        val coordinator = coordinator()
        coordinator.start()
        coordinator.start()
        runCurrent()

        sessionState.value = verifiedState("token-a", "account-a")
        coordinator.state.first { it.loadedToken == "token-a" }
        sessionState.value = verifiedState("token-b", "account-b")
        coordinator.state.first { it.loadedToken == "token-b" }

        assertEquals(listOf("token-a", "token-b"), gateway.readTokens)
        assertEquals("token-b", coordinator.state.value.loadedToken)
        assertTrue(coordinator.state.value.aiSettings.aiConsentGranted)
    }

    @Test
    fun staleWriteResponseCannotReplaceNewTokenSettingsOrLocalCache() = runTest {
        gateway.readResult = { token ->
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(
                    aiConsentGranted = token == "token-b",
                    enhancedContextGranted = false
                )
            )
        }
        val writeGate = CompletableDeferred<Unit>()
        gateway.writeResult = { _, _ ->
            writeGate.await()
            CloudAiSettingsGatewayResult.Success(
                AiCategorizationSettings(aiConsentGranted = true, enhancedContextGranted = true)
            )
        }
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        sessionState.value = verifiedState("token-a", "account-a")
        coordinator.state.first { it.loadedToken == "token-a" }

        val staleWrite = async {
            coordinator.updateAiSettings(
                AiCategorizationSettings(aiConsentGranted = true, enhancedContextGranted = true)
            )
        }
        runCurrent()
        sessionState.value = verifiedState("token-b", "account-b")
        runCurrent()
        writeGate.complete(Unit)

        assertEquals(AccountCloudSettingsOperationResult.Stale, staleWrite.await())
        coordinator.state.first { it.loadedToken == "token-b" }
        assertEquals("token-b", coordinator.state.value.loadedToken)
        assertFalse(coordinator.state.value.aiSettings.enhancedContextGranted)
        assertFalse(preferencesRepository.userPreferences.first().aiSettings.enhancedContextGranted)
    }

    @Test
    fun cancelledAiWriteClearsInFlightStateAndAllowsRetry() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        gateway.writeResult = { _, settings ->
            writeGate.await()
            CloudAiSettingsGatewayResult.Success(settings)
        }
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        sessionState.value = verifiedState("token-a", "account-a")
        coordinator.state.first { it.loadedToken == "token-a" }

        val cancelledWrite = launch {
            coordinator.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))
        }
        runCurrent()
        assertTrue(coordinator.state.value.settingsSyncInFlight)

        cancelledWrite.cancelAndJoin()

        assertFalse(coordinator.state.value.settingsSyncInFlight)
        gateway.writeResult = { _, settings -> CloudAiSettingsGatewayResult.Success(settings) }
        assertEquals(
            AccountCloudSettingsOperationResult.Success,
            coordinator.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))
        )
    }

    @Test
    fun localDefaultSurvivesCloudFailureAndRemainsPending() = runTest {
        gateway.defaultWriteResult = { _, _ ->
            CloudAiSettingsGatewayResult.Failure(AiCategorizationFailureReason.NETWORK_FAILURE)
        }
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        sessionState.value = verifiedState("token-a", "account-a")
        coordinator.state.first { it.loadedToken == "token-a" }
        val fundingAccount = ledgerRepository.createFundingAccount("银行卡", null)

        val result = coordinator.setDefaultFundingAccount(fundingAccount.id)

        assertTrue(result is AccountCloudSettingsOperationResult.CloudFailure)
        assertEquals(fundingAccount.syncId, ledgerRepository.getDefaultFundingAccountSyncId())
        val cached = preferencesRepository.getCachedDefaultFundingAccount("account-a")
        assertEquals(fundingAccount.syncId, cached?.syncId)
        assertTrue(cached?.pendingUpload == true)
    }

    @Test
    fun pendingDefaultIsUploadedAndClearedWhenVerifiedSessionStarts() = runTest {
        preferencesRepository.cacheDefaultFundingAccount(
            accountKey = "account-a",
            syncId = "funding-pending",
            pendingUpload = true
        )
        gateway.defaultWriteResult = { _, syncId ->
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(aiConsentGranted = true),
                defaultFundingAccountSyncId = syncId,
                supportsDefaultFundingAccount = true
            )
        }
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()

        sessionState.value = verifiedState("token-a", "account-a")
        coordinator.state.first { it.loadedToken == "token-a" }

        assertEquals(listOf("token-a" to "funding-pending"), gateway.defaultWrites)
        assertEquals("funding-pending", ledgerRepository.getDefaultFundingAccountSyncId())
        assertFalse(preferencesRepository.getCachedDefaultFundingAccount("account-a")!!.pendingUpload)
        assertEquals("token-a", coordinator.state.value.loadedToken)
    }

    @Test
    fun defaultIsPersistedWhileInitialCloudLoadIsStillInFlight() = runTest {
        val readGate = CompletableDeferred<Unit>()
        gateway.readResult = {
            readGate.await()
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(),
                defaultFundingAccountSyncId = null,
                supportsDefaultFundingAccount = true
            )
        }
        val fundingAccount = ledgerRepository.createFundingAccount("银行卡", null)
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        sessionState.value = verifiedState("token-a", "account-a")
        runCurrent()
        assertTrue(coordinator.state.value.settingsSyncInFlight)

        val cloudWrite = launch {
            coordinator.setDefaultFundingAccount(fundingAccount.id)
        }

        ledgerRepository.state.first {
            it.defaultFundingAccountSyncId == fundingAccount.syncId
        }
        runCurrent()
        val pending = preferencesRepository.getCachedDefaultFundingAccount("account-a")
        assertEquals(fundingAccount.syncId, pending?.syncId)
        assertTrue(pending?.pendingUpload == true)

        cloudWrite.cancelAndJoin()
        readGate.complete(Unit)
        coordinator.state.first { it.loadedToken == "token-a" }

        assertEquals(fundingAccount.syncId, ledgerRepository.getDefaultFundingAccountSyncId())
        assertTrue(preferencesRepository.getCachedDefaultFundingAccount("account-a")!!.pendingUpload)
    }

    @Test
    fun offlineAndDeletionCoolingOffKeepWritesLocalOnly() = runTest {
        preferencesRepository.setDefaultFundingAccountSyncId(null)
        val fundingAccount = ledgerRepository.createFundingAccount("现金", null)
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        sessionState.value = signedInState(
            token = "offline-token",
            accountUuid = "account-a",
            status = AccountRuntimeStatus.OfflineUnverified
        )
        advanceUntilIdle()

        assertEquals(
            AccountCloudSettingsOperationResult.CloudWritesBlocked,
            coordinator.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))
        )
        assertEquals(
            AccountCloudSettingsOperationResult.CloudWritesBlocked,
            coordinator.setDefaultFundingAccount(fundingAccount.id)
        )
        assertEquals(fundingAccount.syncId, ledgerRepository.getDefaultFundingAccountSyncId())

        sessionState.value = signedInState(
            token = "deletion-token",
            accountUuid = "account-a",
            status = AccountRuntimeStatus.DeletionCoolingOff,
            deletionState = AccountDeletionState(1L, 2L)
        )
        advanceUntilIdle()
        assertEquals(
            AccountCloudSettingsOperationResult.CloudWritesBlocked,
            coordinator.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))
        )
        assertEquals(
            AccountCloudSettingsOperationResult.CloudWritesBlocked,
            coordinator.setDefaultFundingAccount(null)
        )

        assertTrue(gateway.writes.isEmpty())
        assertTrue(gateway.defaultWrites.isEmpty())
        assertEquals(null, ledgerRepository.getDefaultFundingAccountSyncId())
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = AccountCloudSettingsCoordinator(
        accountSessionState = sessionState,
        preferencesRepository = preferencesRepository,
        fundingAccountRepository = ledgerRepository,
        cloudGateway = gateway,
        applicationScope = backgroundScope
    )

    private fun verifiedState(token: String, accountUuid: String) = signedInState(
        token = token,
        accountUuid = accountUuid,
        status = AccountRuntimeStatus.Verified
    )

    private fun signedInState(
        token: String,
        accountUuid: String,
        status: AccountRuntimeStatus,
        deletionState: AccountDeletionState = AccountDeletionState()
    ) = AccountSessionCoordinatorState(
        session = AccountSession.SignedIn(token = token, accountUuid = accountUuid),
        isRestoring = false,
        runtimeState = AccountRuntimeState(status),
        deletionState = deletionState
    )

    private class TestCloudSettingsGateway : CloudAiSettingsGateway {
        val readTokens = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, AiCategorizationSettings>>()
        val defaultWrites = mutableListOf<Pair<String, String?>>()
        var readResult: suspend (String) -> CloudAiSettingsGatewayResult = {
            CloudAiSettingsGatewayResult.Success(AiCategorizationSettings())
        }
        var writeResult: suspend (String, AiCategorizationSettings) -> CloudAiSettingsGatewayResult = { _, settings ->
            CloudAiSettingsGatewayResult.Success(settings)
        }
        var defaultWriteResult: suspend (String, String?) -> CloudAiSettingsGatewayResult = { _, syncId ->
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(),
                defaultFundingAccountSyncId = syncId,
                supportsDefaultFundingAccount = true
            )
        }

        override suspend fun read(token: String): CloudAiSettingsGatewayResult {
            readTokens += token
            return readResult(token)
        }

        override suspend fun write(
            token: String,
            settings: AiCategorizationSettings
        ): CloudAiSettingsGatewayResult {
            writes += token to settings
            return writeResult(token, settings)
        }

        override suspend fun writeDefaultFundingAccount(
            token: String,
            syncId: String?
        ): CloudAiSettingsGatewayResult {
            defaultWrites += token to syncId
            return defaultWriteResult(token, syncId)
        }
    }
}
