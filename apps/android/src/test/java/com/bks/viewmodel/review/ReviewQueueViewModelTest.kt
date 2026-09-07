package com.bks.viewmodel.review

import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.database.PendingEntryDao
import com.bks.model.data.local.review.RoomReviewQueueRepository
import com.bks.model.data.local.ledger.toDomain
import com.bks.model.domain.categorization.suggestCategory
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewPersistenceUndo
import com.bks.view.review.confidenceLabel
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.ledger.TestLedgerRepository
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.application.account.AccountCloudSettingsState
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.domain.categorization.AiCategorizationClient
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.model.domain.categorization.AiCategorizationGateway
import com.bks.model.domain.categorization.AiCategorizationGatewayResult
import com.bks.model.domain.categorization.AiCategorizationPayload
import com.bks.model.domain.categorization.AiCategorizationResponse
import com.bks.model.domain.categorization.AiCategorizationSettings
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewQueueViewModelTest {
    private lateinit var database: BksDatabase
    private lateinit var repository: TestLedgerRepository
    private lateinit var persistence: RoomReviewQueueRepository
    private lateinit var replacementInFlight: MutableStateFlow<Boolean>
    private var nextId = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TestLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "generated-${++nextId}" }
        )
        persistence = RoomReviewQueueRepository(
            database = database,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC"),
            idGenerator = { "generated-${++nextId}" }
        )
        replacementInFlight = MutableStateFlow(false)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun confirmExposesOptimisticOperationThenUndoRestoresRoomSnapshot() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        withViewModel { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.id == "pending-lunch" }
            val transactionStarted = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val blocker = async(Dispatchers.IO) {
                database.withTransaction {
                    transactionStarted.complete(Unit)
                    releaseTransaction.await()
                }
            }
            transactionStarted.await()

            viewModel.execute(
                ReviewPersistenceCommand.Confirm(
                    stableId = "pending-lunch",
                    targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID
                )
            )
            yield()

            val optimistic = viewModel.uiState.value
            assertTrue(optimistic.pendingEntries.isEmpty())
            assertEquals(
                ReviewQueueOperation.InFlight(
                    stableId = "pending-lunch",
                    kind = ReviewQueueOperationKind.CONFIRM
                ),
                optimistic.operation
            )

            releaseTransaction.complete(Unit)
            blocker.await()
            val confirmed = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle && it.undoEvent != null
            }
            assertEquals("已确认 午餐", confirmed.undoEvent?.message)
            assertTrue(confirmed.pendingEntries.isEmpty())

            viewModel.undoLastAction()

            val restored = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle &&
                    it.pendingEntries.singleOrNull()?.id == "pending-lunch"
            }
            assertEquals(ConfidenceState.DUPLICATE_SUSPECT, restored.pendingEntries.single().confidence)
            assertEquals("支付宝余额", restored.pendingEntries.single().fundingAccountLabel)
            assertEquals(null, restored.undoEvent)
        }
    }

    @Test
    fun typedConflictClearsOptimisticStateAndReloadsRoomProjection() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        val confirmed = persistence.execute(
            ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
        ) as ReviewPersistenceResult.Applied
        repository.upsertPending(samplePending(note = "captured again"))
        assertNotNull((confirmed.undo as ReviewPersistenceUndo.Confirmed).receipt)

        withViewModel { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.note == "captured again" }
            val notice = async { withTimeout(5_000) { viewModel.notices.first() } }

            viewModel.execute(ReviewPersistenceCommand.Ignore("pending-lunch"))

            assertEquals("记录已发生变化，待确认列表已刷新", notice.await())
            val reloaded = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle &&
                    it.pendingEntries.singleOrNull()?.note == "captured again"
            }
            assertEquals("pending-lunch", reloaded.pendingEntries.single().id)
            assertEquals(null, reloaded.undoEvent)
        }
    }

    @Test
    fun persistenceFailureRollsBackAndReloadsInsteadOfKeepingOptimisticSuccess() = runBlocking {
        val calls = AtomicInteger()
        val failingIdGenerator = {
            if (calls.incrementAndGet() == 1) "ledger-before-failure" else error("outbox failure")
        }
        val failingRepository = TestLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = failingIdGenerator
        )
        failingRepository.ensureDefaultLedgerBook()
        failingRepository.upsertPending(samplePending())
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity(enabled = true))
        val failingPersistence = RoomReviewQueueRepository(
            database = database,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC"),
            idGenerator = failingIdGenerator
        )

        withViewModel(failingPersistence) { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.id == "pending-lunch" }
            val notice = async { withTimeout(5_000) { viewModel.notices.first() } }

            viewModel.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )

            assertEquals("保存待确认操作失败，请重试", notice.await())
            val reloaded = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle &&
                    it.pendingEntries.singleOrNull()?.id == "pending-lunch"
            }
            assertEquals("pending-lunch", reloaded.pendingEntries.single().id)
            assertTrue(database.ledgerEntryDao().listAllLedgerEntries().isEmpty())
        }
    }

    @Test
    fun localDataReplacementBlocksCommandsAndClearsUndoMetadata() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        withViewModel { viewModel ->
            viewModel.awaitState { it.pendingEntries.isNotEmpty() }
            viewModel.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )
            viewModel.awaitState { it.undoEvent != null }
            replacementInFlight.value = true
            yield()
            val notice = async { withTimeout(5_000) { viewModel.notices.first() } }

            viewModel.execute(ReviewPersistenceCommand.Ignore("pending-lunch"))

            assertEquals("本机数据正在删除，请稍后重试", notice.await())
            assertEquals(null, viewModel.uiState.value.undoEvent)
            assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
        }
    }

    @Test
    fun completedReplacementInvalidatesACommandResultThatResumesLater() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        withViewModel { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.id == "pending-lunch" }
            val transactionStarted = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val blocker = async(Dispatchers.IO) {
                database.withTransaction {
                    transactionStarted.complete(Unit)
                    releaseTransaction.await()
                }
            }
            transactionStarted.await()

            viewModel.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )
            yield()
            replacementInFlight.value = true
            yield()
            replacementInFlight.value = false
            yield()
            releaseTransaction.complete(Unit)
            blocker.await()

            val settled = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle && it.pendingEntries.isEmpty()
            }
            assertEquals(null, settled.undoEvent)
        }
    }

    @Test
    fun replacementDuringSuccessReloadCannotRepublishTheOldUndoReceipt() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        val reloadStarted = CompletableDeferred<Unit>()
        val releaseReload = CompletableDeferred<Unit>()

        withViewModel(
            stateLoader = {
                reloadStarted.complete(Unit)
                releaseReload.await()
                persistence.loadSnapshot().toUiState(ZoneId.of("UTC"))
            }
        ) { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.id == "pending-lunch" }

            viewModel.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )
            reloadStarted.await()
            replacementInFlight.value = true
            yield()
            replacementInFlight.value = false
            yield()
            releaseReload.complete(Unit)

            val settled = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle && it.pendingEntries.isEmpty()
            }
            assertEquals(null, settled.undoEvent)
            yield()
            assertEquals(null, viewModel.uiState.value.undoEvent)
        }
    }

    @Test
    fun replacementPreparationWaitsForTheActiveReviewOperationAndClearsItsUndo() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        withViewModel { viewModel ->
            viewModel.awaitState { it.pendingEntries.singleOrNull()?.id == "pending-lunch" }
            val transactionStarted = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val blocker = async(Dispatchers.IO) {
                database.withTransaction {
                    transactionStarted.complete(Unit)
                    releaseTransaction.await()
                }
            }
            transactionStarted.await()

            viewModel.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )
            yield()
            val preparation = async {
                viewModel.awaitIdleAndResetForLocalDataReplacement()
            }
            yield()

            assertTrue(!preparation.isCompleted)
            releaseTransaction.complete(Unit)
            blocker.await()
            preparation.await()

            val settled = viewModel.awaitState {
                it.operation == ReviewQueueOperation.Idle
            }
            assertEquals(null, settled.undoEvent)
        }
    }

    @Test
    fun aiSuggestionUsesCurrentVerifiedAccountAndCloudSettings() = runBlocking {
        val gateway = CapturingAiGateway()
        val accountState = MutableStateFlow(
            AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-1"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified)
            )
        )
        val cloudState = MutableStateFlow(
            AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(
                    aiConsentGranted = true,
                    enhancedContextGranted = true
                ),
                loadedToken = "token-1"
            )
        )

        withViewModel(
            aiDependencies = ReviewAiDependencies(
                client = AiCategorizationClient(gateway),
                accountSessionState = accountState,
                cloudSettingsState = cloudState
            )
        ) { viewModel ->
            val result = viewModel.suggestCategory(
                samplePending().toDomain().toUiModel(ZoneId.of("UTC")),
                listOf("餐饮", "交通")
            )

            assertEquals("交通", result.category)
            assertEquals("token-1", gateway.token.get())
            assertEquals(listOf("餐饮", "交通"), gateway.payload.get()?.categoryCandidates)
            assertTrue(gateway.payload.get()?.rawEvidenceText?.isNotBlank() == true)
        }
    }

    @Test
    fun aiSuggestionRejectsCloudSettingsLoadedForAnotherSession() = runBlocking {
        val gateway = CapturingAiGateway()
        val accountState = MutableStateFlow(
            AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-1"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified)
            )
        )
        val cloudState = MutableStateFlow(
            AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(
                    aiConsentGranted = true,
                    enhancedContextGranted = true
                ),
                loadedToken = "token-old"
            )
        )

        withViewModel(
            aiDependencies = ReviewAiDependencies(
                client = AiCategorizationClient(gateway),
                accountSessionState = accountState,
                cloudSettingsState = cloudState
            )
        ) { viewModel ->
            val result = viewModel.suggestCategory(
                samplePending().toDomain().toUiModel(ZoneId.of("UTC")),
                listOf("餐饮")
            )

            assertEquals(null, result.category)
            assertEquals("开启云端 AI 后才能获取分类建议", result.message)
            assertEquals(null, gateway.token.get())
            assertEquals(null, gateway.payload.get())
        }
    }

    @Test
    fun aiSuggestionRejectsCloudSettingsWhileCloudWritesAreBlocked() = runBlocking {
        val gateway = CapturingAiGateway()
        val accountState = MutableStateFlow(
            AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-1"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
            )
        )
        val cloudState = MutableStateFlow(
            AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(
                    aiConsentGranted = true,
                    enhancedContextGranted = true
                ),
                loadedToken = "token-1"
            )
        )

        withViewModel(
            aiDependencies = ReviewAiDependencies(
                client = AiCategorizationClient(gateway),
                accountSessionState = accountState,
                cloudSettingsState = cloudState
            )
        ) { viewModel ->
            val result = viewModel.suggestCategory(
                samplePending().toDomain().toUiModel(ZoneId.of("UTC")),
                listOf("餐饮")
            )

            assertEquals(null, result.category)
            assertEquals("开启云端 AI 后才能获取分类建议", result.message)
            assertEquals(null, gateway.token.get())
            assertEquals(null, gateway.payload.get())
        }
    }

    @Test
    fun aiSuggestionMapsGatewayFailureToStableReviewMessage() = runBlocking {
        val accountState = MutableStateFlow(
            AccountSessionCoordinatorState(
                session = AccountSession.SignedIn(token = "token-1"),
                isRestoring = false,
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified)
            )
        )
        val cloudState = MutableStateFlow(
            AccountCloudSettingsState(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                loadedToken = "token-1"
            )
        )
        val gateway = object : AiCategorizationGateway {
            override suspend fun suggestCategory(
                token: String,
                payload: AiCategorizationPayload
            ): AiCategorizationGatewayResult = AiCategorizationGatewayResult.Failure(
                AiCategorizationFailureReason.RATE_LIMITED
            )
        }

        withViewModel(
            aiDependencies = ReviewAiDependencies(
                client = AiCategorizationClient(gateway),
                accountSessionState = accountState,
                cloudSettingsState = cloudState
            )
        ) { viewModel ->
            val result = viewModel.suggestCategory(
                samplePending().toDomain().toUiModel(ZoneId.of("UTC")),
                listOf("餐饮")
            )

            assertEquals(null, result.category)
            assertEquals("AI 请求过于频繁，请稍后重试", result.message)
        }
    }

    private suspend fun withViewModel(
        activePersistence: RoomReviewQueueRepository = persistence,
        stateLoader: suspend () -> ReviewQueueUiState = {
            activePersistence.loadSnapshot().toUiState(ZoneId.of("UTC"))
        },
        aiDependencies: ReviewAiDependencies? = null,
        block: suspend (ReviewQueueViewModel) -> Unit
    ) {
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewQueueViewModel(
                    persistence = activePersistence,
                    localDataReplacementInFlight = replacementInFlight,
                    aiDependencies = aiDependencies ?: ReviewAiDependencies(),
                    stateLoader = stateLoader
                ) as T
            }
        )[ReviewQueueViewModel::class.java]
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    private suspend fun ReviewQueueViewModel.awaitState(
        predicate: (ReviewQueueUiState) -> Boolean
    ): ReviewQueueUiState = withTimeout(5_000) { uiState.first(predicate) }

    private fun samplePending(note: String? = "客户会议"): PendingEntryEntity = PendingEntryEntity(
        id = "pending-lunch",
        source = PaymentSource.ALIPAY,
        captureReason = CaptureReason.BILL_SYNC,
        confidence = ConfidenceState.DUPLICATE_SUSPECT,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3_590,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = NOW - 60_000,
        capturedAtEpochMillis = NOW - 10_000,
        suggestedCategoryId = null,
        fundingAccountId = null,
        fundingAccountLabel = "支付宝余额",
        note = note,
        evidenceSummary = "evidence",
        parsedFieldsText = "商户=午餐"
    )

    private class CapturingAiGateway : AiCategorizationGateway {
        val token = AtomicReference<String?>()
        val payload = AtomicReference<AiCategorizationPayload?>()

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult {
            this.token.set(token)
            this.payload.set(payload)
            return AiCategorizationGatewayResult.Success(
                AiCategorizationResponse(
                    category = "交通",
                    confidenceLabel = "高",
                    explanation = "测试建议"
                )
            )
        }
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
