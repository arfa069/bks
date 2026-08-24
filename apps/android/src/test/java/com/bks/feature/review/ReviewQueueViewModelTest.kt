package com.bks.feature.review

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.bks.data.local.AccountSyncStateEntity
import com.bks.data.local.BksDatabase
import com.bks.data.local.CaptureReason
import com.bks.data.local.ConfidenceState
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.data.local.PendingEntryEntity
import com.bks.data.local.TransactionKind
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var repository: LocalLedgerRepository
    private lateinit var persistence: ReviewQueuePersistence
    private lateinit var replacementInFlight: MutableStateFlow<Boolean>
    private var nextId = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "generated-${++nextId}" }
        )
        persistence = ReviewQueuePersistence(repository, { NOW }, ZoneId.of("UTC"))
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
        val failingRepository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = {
                if (calls.incrementAndGet() == 1) "ledger-before-failure" else error("outbox failure")
            }
        )
        failingRepository.ensureDefaultLedgerBook()
        failingRepository.upsertPending(samplePending())
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity(enabled = true))
        val failingPersistence = ReviewQueuePersistence(
            failingRepository,
            { NOW },
            ZoneId.of("UTC")
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
                persistence.loadState()
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

    private suspend fun withViewModel(
        activePersistence: ReviewQueuePersistence = persistence,
        stateLoader: suspend () -> ReviewQueueState = activePersistence::loadState,
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
        predicate: (ReviewQueueState) -> Boolean
    ): ReviewQueueState = withTimeout(5_000) { uiState.first(predicate) }

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

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
