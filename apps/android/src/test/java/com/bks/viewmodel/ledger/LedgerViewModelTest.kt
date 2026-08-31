package com.bks.viewmodel.ledger

import com.bks.model.data.local.ledger.FundingAccountRepository
import com.bks.model.data.local.ledger.LocalLedgerDomainRepository
import com.bks.model.domain.ledger.DEFAULT_CATEGORY_ID
import com.bks.model.domain.settings.PreferencesRepository
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_NAME
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.data.local.ledger.LocalLedgerRepository
import com.bks.model.data.local.settings.LocalPreferencesRepository
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.model.domain.categorization.CloudAiSettingsGatewayResult
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerViewModelTest {
    private lateinit var database: BksDatabase
    private lateinit var repository: LocalLedgerRepository
    private lateinit var preferencesRepository: LocalPreferencesRepository
    private lateinit var sessionState: MutableStateFlow<AccountSessionCoordinatorState>
    private lateinit var gateway: TestCloudSettingsGateway

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val sequence = AtomicInteger()
        repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "generated-${sequence.incrementAndGet()}" }
        )
        preferencesRepository = LocalPreferencesRepository(database)
        sessionState = MutableStateFlow(verifiedAccount("token-a", "account-a"))
        gateway = TestCloudSettingsGateway()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aggregatesRoomFlowsAndMapsReportModels() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            val account = repository.createFundingAccount("微信零钱", PaymentSource.WECHAT)
            repository.setDefaultFundingAccount(account.id)
            viewModel.createManualEntry(input(title = "午餐", fundingAccountId = account.id))

            val state = viewModel.awaitState { it.entries.size == 1 && it.fundingAccounts.size == 1 }

            assertEquals(DEFAULT_LEDGER_BOOK_ID, state.activeLedgerBookId)
            assertEquals(DEFAULT_LEDGER_BOOK_NAME, state.activeLedgerName)
            assertEquals("午餐", state.entries.single().title)
            assertEquals("微信零钱", state.fundingAccounts.single().label)
            assertEquals(account.syncId, state.defaultFundingAccountSyncId)
            assertTrue(state.categories.isNotEmpty())
            assertEquals("2026-07", state.report.anchorMonthKey)
            assertEquals(3_590L, state.report.summary?.expenseMinor)
        }
    }

    @Test
    fun entryCrudKeepsRoomAsSourceOfTruth() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            viewModel.createManualEntry(input(title = "午餐"))
            val id = viewModel.awaitState { it.entries.size == 1 }.entries.single().id

            viewModel.updateEntry(id, input(title = "工作餐", amountMinor = 4_200))
            assertEquals("工作餐", viewModel.awaitState { it.entries.singleOrNull()?.title == "工作餐" }
                .entries.single().title)

            viewModel.deleteEntry(id)
            assertEquals(id, viewModel.awaitState { it.deletedEntries.size == 1 }.deletedEntries.single().id)

            viewModel.restoreEntry(id)
            assertEquals(id, viewModel.awaitState { it.entries.size == 1 }.entries.single().id)

            viewModel.deleteEntry(id)
            viewModel.awaitState { it.deletedEntries.size == 1 }
            viewModel.permanentlyDeleteEntry(id)
            viewModel.awaitState { it.entries.isEmpty() && it.deletedEntries.isEmpty() }
        }
    }

    @Test
    fun managementCrudMapsTypedResultsAndErrors() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            viewModel.createLedger("家庭账本")
            val familyLedger = viewModel.awaitState { it.ledgerBooks.size == 2 }
                .ledgerBooks.first { it.name == "家庭账本" }
            viewModel.selectLedger(familyLedger.id)
            viewModel.awaitState { it.activeLedgerBookId == familyLedger.id }

            viewModel.createFundingAccount("主卡", PaymentSource.ALIPAY)
            val account = viewModel.awaitState { it.fundingAccounts.size == 1 }.fundingAccounts.single()
            viewModel.updateFundingAccount(account.id, "支付宝主卡", PaymentSource.ALIPAY)
            viewModel.awaitState { it.fundingAccounts.singleOrNull()?.label == "支付宝主卡" }
            viewModel.createManualEntry(input(title = "晚餐", fundingAccountId = account.id))
            viewModel.awaitState { it.entries.size == 1 }

            assertTrue(viewModel.deleteLedger(familyLedger.id) is LedgerBookDeleteResult.NotEmpty)
            assertTrue(viewModel.deleteFundingAccount(account.id) is FundingAccountDeleteResult.Referenced)
            assertEquals(LedgerBookDeleteResult.Deleted, viewModel.deleteLedger(DEFAULT_LEDGER_BOOK_ID))
            assertEquals(LedgerBookDeleteResult.LastLedger, viewModel.deleteLedger(familyLedger.id))

            val missingLedger = runCatching { viewModel.deleteLedger("missing") }.exceptionOrNull()
            val missingAccount = runCatching { viewModel.deleteFundingAccount(Long.MAX_VALUE) }.exceptionOrNull()
            assertEquals("账本不存在", missingLedger?.message)
            assertEquals("资金账户不存在", missingAccount?.message)
        }
    }

    @Test
    fun cloudFailureKeepsLocalDefaultAndReturnsExistingMessage() = runTest {
        gateway.defaultWriteResult = CloudAiSettingsGatewayResult.Failure(
            AiCategorizationFailureReason.NETWORK_FAILURE
        )
        withViewModel { viewModel ->
            viewModel.awaitReady()
            advanceUntilIdle()
            val account = repository.createFundingAccount("主卡", PaymentSource.ALIPAY)

            val message = viewModel.setDefaultFundingAccount(account.id)

            assertEquals("默认账户已保存到本机，云端同步失败", message)
            assertEquals(account.syncId, repository.getDefaultFundingAccountSyncId())
            assertEquals(listOf("token-a" to account.syncId), gateway.defaultWrites)
            assertEquals(
                account.syncId,
                viewModel.awaitState { it.defaultFundingAccountSyncId == account.syncId }
                    .defaultFundingAccountSyncId
            )
        }
    }

    @Test
    fun accountDataReplacementDropsThePreviousProjection() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            viewModel.createManualEntry(input(title = "旧账户账目"))
            viewModel.awaitState { it.entries.singleOrNull()?.title == "旧账户账目" }

            sessionState.value = verifiedAccount("token-b", "account-b")
            repository.clearLocalData()
            viewModel.awaitState {
                it.activeLedgerBookId == DEFAULT_LEDGER_BOOK_ID && it.entries.isEmpty()
            }
            viewModel.createManualEntry(input(title = "新账户账目"))

            assertEquals(
                listOf("新账户账目"),
                viewModel.awaitState { it.entries.singleOrNull()?.title == "新账户账目" }
                    .entries.map(LedgerUiEntry::title)
            )
        }
    }

    @Test
    fun localDataReplacementBlocksWritesUntilTheClearedProjectionIsReady() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            viewModel.createManualEntry(input(title = "清理前账目"))
            viewModel.awaitState { it.entries.singleOrNull()?.title == "清理前账目" }
            val allowClear = CompletableDeferred<Unit>()

            val clearJob = async {
                viewModel.clearLocalData(beforeClear = { allowClear.await() })
            }
            runCurrent()

            assertTrue(viewModel.localDataReplacementInFlight.value)
            assertTrue(viewModel.uiState.value.localDataReplacementInFlight)
            assertEquals(null, viewModel.uiState.value.activeLedgerBookId)
            val failure = runCatching {
                viewModel.createManualEntry(input(title = "清理期间账目"))
            }.exceptionOrNull()
            assertEquals("本机数据正在删除，请稍后重试", failure?.message)

            allowClear.complete(Unit)
            clearJob.await()

            assertFalse(viewModel.localDataReplacementInFlight.value)
            assertFalse(viewModel.uiState.value.localDataReplacementInFlight)
            viewModel.createManualEntry(input(title = "清理后账目"))
            assertEquals(
                listOf("清理后账目"),
                viewModel.awaitState { it.entries.singleOrNull()?.title == "清理后账目" }
                    .entries.map(LedgerUiEntry::title)
            )
        }
    }

    @Test
    fun externalLocalDataReplacementUsesTheSameWriteGate() = runTest {
        withViewModel { viewModel ->
            viewModel.awaitReady()
            viewModel.createManualEntry(input(title = "替换前账目"))
            viewModel.awaitState { it.entries.singleOrNull()?.title == "替换前账目" }
            val allowReplacement = CompletableDeferred<Unit>()

            val replacementJob = async {
                viewModel.runLocalDataReplacement {
                    allowReplacement.await()
                    repository.clearLocalData()
                }
            }
            runCurrent()

            assertTrue(viewModel.localDataReplacementInFlight.value)
            val failure = runCatching {
                viewModel.createManualEntry(input(title = "替换期间账目"))
            }.exceptionOrNull()
            assertEquals("本机数据正在删除，请稍后重试", failure?.message)

            allowReplacement.complete(Unit)
            replacementJob.await()

            assertFalse(viewModel.localDataReplacementInFlight.value)
            viewModel.createManualEntry(input(title = "替换后账目"))
            assertEquals(
                listOf("替换后账目"),
                viewModel.awaitState { it.entries.singleOrNull()?.title == "替换后账目" }
                    .entries.map(LedgerUiEntry::title)
            )
        }
    }

    @Test
    fun initialStateIsEmptyUntilRoomEmits() = runTest {
        withViewModel(advanceBeforeBlock = false) { viewModel ->
            assertTrue(viewModel.uiState.value.entries.isEmpty())
            assertTrue(viewModel.uiState.value.categories.isEmpty())
            assertEquals(null, viewModel.uiState.value.activeLedgerBookId)
            viewModel.awaitReady()
        }
    }

    private suspend fun TestScope.withViewModel(
        advanceBeforeBlock: Boolean = true,
        block: suspend TestScope.(LedgerViewModel) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val cloudSettingsCoordinator = AccountCloudSettingsCoordinator(
                accountSessionState = sessionState,
                preferencesRepository = preferencesRepository,
                fundingAccountRepository = repository,
                cloudGateway = gateway,
                applicationScope = backgroundScope
            ).also { it.start() }
            val viewModel = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(
                        repository = LocalLedgerDomainRepository(repository),
                        cloudSettingsCoordinator = cloudSettingsCoordinator
                    ) as T
                }
            )[LedgerViewModel::class.java]
            if (advanceBeforeBlock) advanceUntilIdle()
            block(viewModel)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private suspend fun LedgerViewModel.awaitReady(): LedgerUiState = awaitState {
        it.activeLedgerBookId != null && it.categories.isNotEmpty()
    }

    private suspend fun LedgerViewModel.awaitState(
        predicate: (LedgerUiState) -> Boolean
    ): LedgerUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { uiState.first(predicate) }
    }

    private fun input(
        title: String,
        amountMinor: Long = 3_590,
        fundingAccountId: Long? = null
    ) = LedgerEntryFormSubmission(
        flowDirection = FlowDirection.OUTFLOW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = amountMinor,
        transactionTimeEpochMillis = NOW - 1_000,
        merchantTitle = title,
        categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
        fundingAccountId = fundingAccountId,
        newFundingAccountLabel = null,
        note = null,
        paymentSource = PaymentSource.ALIPAY
    )

    private fun verifiedAccount(token: String, accountUuid: String) = AccountSessionCoordinatorState(
        session = AccountSession.SignedIn(token = token, accountUuid = accountUuid),
        isRestoring = false,
        runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified)
    )

    private class TestCloudSettingsGateway : CloudAiSettingsGateway {
        val defaultWrites = mutableListOf<Pair<String, String?>>()
        var defaultWriteResult: CloudAiSettingsGatewayResult = CloudAiSettingsGatewayResult.Success(
            settings = AiCategorizationSettings(),
            supportsDefaultFundingAccount = true
        )

        override suspend fun read(token: String): CloudAiSettingsGatewayResult =
            CloudAiSettingsGatewayResult.Success(
                settings = AiCategorizationSettings(),
                supportsDefaultFundingAccount = true
            )

        override suspend fun write(
            token: String,
            settings: AiCategorizationSettings
        ): CloudAiSettingsGatewayResult = CloudAiSettingsGatewayResult.Success(settings)

        override suspend fun writeDefaultFundingAccount(
            token: String,
            syncId: String?
        ): CloudAiSettingsGatewayResult {
            defaultWrites += token to syncId
            return defaultWriteResult
        }
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-07-08T12:00:00Z").toEpochMilli()
    }
}
