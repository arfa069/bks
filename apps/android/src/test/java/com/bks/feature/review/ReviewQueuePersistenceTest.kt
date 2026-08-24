package com.bks.feature.review

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.data.local.AccountSyncStateEntity
import com.bks.data.local.BksDatabase
import com.bks.data.local.CaptureReason
import com.bks.data.local.ConfidenceState
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.data.local.IgnoreReason
import com.bks.data.local.IgnoredEntryEntity
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.data.local.PendingEntryEntity
import com.bks.data.local.TransactionKind
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueuePersistenceTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var repository: LocalLedgerRepository
    private lateinit var persistence: ReviewQueuePersistence
    private var nextId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "generated-${++nextId}" }
        )
        persistence = ReviewQueuePersistence(
            repository = repository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeStateRestoresPendingAndIgnoredMetadataForReviewQueue() = runBlocking {
        repository.seedSystemCategories()
        val pendingFundingAccount = repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        val ignoredFundingAccount = repository.createFundingAccount("微信零钱", PaymentSource.WECHAT)
        repository.upsertPending(
            samplePending(
                id = "pending-duplicate",
                confidence = ConfidenceState.DUPLICATE_SUSPECT,
                captureReason = CaptureReason.BILL_SYNC,
                suggestedCategoryId = "food",
                fundingAccountId = pendingFundingAccount.id
            )
        )
        repository.upsertIgnored(sampleIgnored("ignored-lunch", ignoredFundingAccount.id))

        val state = persistence.observeState().first()

        assertEquals(listOf("pending-duplicate"), state.pendingEntries.map { it.id })
        val pending = state.pendingEntries.single()
        assertEquals("food", pending.categoryId)
        assertEquals("餐饮", pending.category)
        assertEquals("支付宝", pending.sourceLabel)
        assertEquals("补录账单", pending.captureReasonLabel)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, pending.confidence)
        assertEquals(pendingFundingAccount.id, pending.fundingAccountId)
        assertEquals(listOf("商户=午餐", "金额=35.90"), pending.parsedFields)
        assertEquals(1, state.duplicateSuspectCount)
        assertEquals(1, state.todaysNewlyCapturedCount)

        val ignored = state.recoverableIgnoredEntries.single()
        assertEquals("pending-ignored", ignored.originalPendingId)
        assertEquals(ignoredFundingAccount.id, ignored.entry.fundingAccountId)
        assertEquals("微信支付收款凭证 午餐 35.90", ignored.entry.rawEvidenceText)
    }

    @Test
    fun explicitIgnoreAndRecoverMoveOnlyTheStableTarget() = runBlocking {
        repository.upsertPending(samplePending(id = "pending-target"))
        repository.upsertPending(samplePending(id = "pending-unrelated"))

        val ignoredResult = persistence.execute(ReviewPersistenceCommand.Ignore("pending-target"))

        val ignoredUndo = (ignoredResult as ReviewPersistenceResult.Applied).undo
            as ReviewPersistenceUndo.Ignored
        assertNull(database.pendingEntryDao().getById("pending-target"))
        assertEquals("pending-unrelated", database.pendingEntryDao().getById("pending-unrelated")?.id)
        val ignored = database.ignoredEntryDao().getById(ignoredUndo.ignoredEntryId)
        assertEquals("pending-target", ignored?.originalPendingEntryId)

        val recoveredResult = persistence.execute(
            ReviewPersistenceCommand.Recover(
                ignoredEntryId = ignoredUndo.ignoredEntryId,
                stableId = "pending-target"
            )
        )

        assertTrue(recoveredResult is ReviewPersistenceResult.Applied)
        assertEquals("pending-target", database.pendingEntryDao().getById("pending-target")?.id)
        assertNull(database.ignoredEntryDao().getById(ignoredUndo.ignoredEntryId))
        assertEquals("pending-unrelated", database.pendingEntryDao().getById("pending-unrelated")?.id)
    }

    @Test
    fun editAndConfirmAreAtomicAndUndoRestoresTheRealRoomSnapshotWithOutbox() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()
        val originalAccount = repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        val selectedAccount = repository.createFundingAccount("支付宝银行卡", PaymentSource.ALIPAY)
        val original = samplePending(
            confidence = ConfidenceState.DUPLICATE_SUSPECT,
            captureReason = CaptureReason.BILL_SYNC,
            suggestedCategoryId = "food",
            fundingAccountId = originalAccount.id
        )
        repository.upsertPending(original)
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity(enabled = true))

        val result = persistence.execute(
            ReviewPersistenceCommand.Confirm(
                stableId = original.id,
                targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID,
                edit = ReviewEntryEdit(
                    title = "工作餐",
                    amountMinor = 4_580,
                    transactionTimeEpochMillis = NOW - 30_000,
                    transactionKind = TransactionKind.EXPENSE,
                    categoryId = "shopping",
                    category = "购物",
                    fundingAccountId = selectedAccount.id,
                    fundingAccountLabel = selectedAccount.label,
                    note = "客户会议"
                )
            )
        )

        val undo = (result as ReviewPersistenceResult.Applied).undo
            as ReviewPersistenceUndo.Confirmed
        assertNull(database.pendingEntryDao().getById(original.id))
        val ledger = database.ledgerEntryDao().getById(undo.receipt.ledgerEntry.id)!!
        assertEquals("工作餐", ledger.merchantTitle)
        assertEquals(4_580L, ledger.amountMinor)
        assertEquals("shopping", ledger.categoryId)
        assertEquals(selectedAccount.id, ledger.fundingAccountId)
        assertEquals("客户会议", ledger.note)
        assertEquals(
            ledger.id,
            database.ledgerSyncDao().findOutbox(
                LedgerSyncEntityTypeContract.LEDGER_ENTRY.name,
                ledger.id
            )?.entityId
        )

        val undoResult = persistence.execute(ReviewPersistenceCommand.UndoConfirm(undo.receipt))

        assertTrue(undoResult is ReviewPersistenceResult.Applied)
        val restored = database.pendingEntryDao().getById(original.id)
        assertEquals(undo.receipt.pendingSnapshot, restored)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, restored?.confidence)
        assertEquals(CaptureReason.BILL_SYNC, restored?.captureReason)
        assertEquals(original.capturedAtEpochMillis, restored?.capturedAtEpochMillis)
        assertEquals("购物", restored?.suggestedCategoryLabel)
        assertEquals(selectedAccount.label, restored?.fundingAccountLabel)
        val deletedLedger = database.ledgerEntryDao().getById(ledger.id)!!
        assertEquals(NOW, deletedLedger.deletedAtEpochMillis)
        val undoOutbox = database.ledgerSyncDao().findOutbox(
            LedgerSyncEntityTypeContract.LEDGER_ENTRY.name,
            ledger.id
        )
        assertFalse(requireNotNull(undoOutbox).deleted)
        assertTrue(requireNotNull(undoOutbox.payload).contains("deletedAtMillis"))
    }

    @Test
    fun staleConfirmAndIgnoreDoNotLoseConcurrentAccessibilityCaptures() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending(id = "confirm-target"))
        repository.upsertPending(samplePending(id = "ignore-target"))
        val staleConfirm = ReviewPersistenceCommand.Confirm(
            stableId = "confirm-target",
            targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID
        )
        val staleIgnore = ReviewPersistenceCommand.Ignore("ignore-target")

        coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    persistence.insertCapturedEntries(
                        listOf(samplePending(id = "capture-during-confirm").toReviewEntry(ZoneId.of("UTC")))
                    )
                },
                async(Dispatchers.IO) { persistence.execute(staleConfirm) }
            ).awaitAll()
        }
        coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    persistence.insertCapturedEntries(
                        listOf(samplePending(id = "capture-during-ignore").toReviewEntry(ZoneId.of("UTC")))
                    )
                },
                async(Dispatchers.IO) { persistence.execute(staleIgnore) }
            ).awaitAll()
        }

        assertEquals(
            setOf("capture-during-confirm", "capture-during-ignore"),
            database.pendingEntryDao().listPendingEntries().map { it.id }.toSet()
        )
        assertEquals(listOf("confirm-target"), database.ledgerEntryDao().listLedgerEntries().map {
            it.originPendingEntryId
        })
        assertEquals(
            listOf("ignore-target"),
            database.ignoredEntryDao().listRecoverable(NOW).map { it.originalPendingEntryId }
        )
    }

    @Test
    fun concurrentConfirmAppliesOnceWithoutDuplicateLedgerEntries() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        val command = ReviewPersistenceCommand.Confirm(
            stableId = "pending-lunch",
            targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID
        )

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) { persistence.execute(command) },
                async(Dispatchers.IO) { persistence.execute(command) }
            ).awaitAll()
        }

        assertEquals(1, results.count { it is ReviewPersistenceResult.Applied })
        assertEquals(1, results.count { it is ReviewPersistenceResult.AlreadyApplied })
        assertEquals(1, database.ledgerEntryDao().listLedgerEntries().size)
        assertNull(database.pendingEntryDao().getById("pending-lunch"))
    }

    @Test
    fun terminalLifecycleRejectsLateCaptureWithTheSameStableId() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending(id = "confirmed-capture"))
        assertTrue(
            persistence.execute(
                ReviewPersistenceCommand.Confirm("confirmed-capture", DEFAULT_LEDGER_BOOK_ID)
            ) is ReviewPersistenceResult.Applied
        )

        val insertedAfterConfirm = persistence.insertCapturedEntries(
            listOf(samplePending(id = "confirmed-capture").toReviewEntry(ZoneId.of("UTC")))
        )

        repository.upsertPending(samplePending(id = "ignored-capture"))
        assertTrue(
            persistence.execute(ReviewPersistenceCommand.Ignore("ignored-capture"))
                is ReviewPersistenceResult.Applied
        )

        val insertedAfterIgnore = persistence.insertCapturedEntries(
            listOf(samplePending(id = "ignored-capture").toReviewEntry(ZoneId.of("UTC")))
        )

        assertEquals(0, insertedAfterConfirm)
        assertEquals(0, insertedAfterIgnore)
        assertNull(database.pendingEntryDao().getById("confirmed-capture"))
        assertNull(database.pendingEntryDao().getById("ignored-capture"))
        assertEquals(
            listOf("confirmed-capture"),
            database.ledgerEntryDao().listLedgerEntries().map { it.originPendingEntryId }
        )
        assertEquals(
            listOf("ignored-capture"),
            database.ignoredEntryDao().listRecoverable(NOW).map { it.originalPendingEntryId }
        )
    }

    @Test
    fun oppositeTerminalTransitionsAndEditsReturnConflict() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending(id = "confirmed-target"))
        assertTrue(
            persistence.execute(
                ReviewPersistenceCommand.Confirm("confirmed-target", DEFAULT_LEDGER_BOOK_ID)
            ) is ReviewPersistenceResult.Applied
        )

        assertEquals(
            ReviewPersistenceResult.Conflict,
            persistence.execute(ReviewPersistenceCommand.Ignore("confirmed-target"))
        )
        assertEquals(
            ReviewPersistenceResult.Conflict,
            persistence.execute(ReviewPersistenceCommand.Edit("confirmed-target", sampleEdit()))
        )

        repository.upsertPending(samplePending(id = "ignored-target"))
        assertTrue(
            persistence.execute(ReviewPersistenceCommand.Ignore("ignored-target"))
                is ReviewPersistenceResult.Applied
        )

        assertEquals(
            ReviewPersistenceResult.Conflict,
            persistence.execute(
                ReviewPersistenceCommand.Confirm("ignored-target", DEFAULT_LEDGER_BOOK_ID)
            )
        )
        assertEquals(
            ReviewPersistenceResult.Conflict,
            persistence.execute(ReviewPersistenceCommand.Edit("ignored-target", sampleEdit()))
        )
    }

    @Test
    fun concurrentConfirmAndIgnoreProduceOneAppliedAndOneConflict() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    persistence.execute(
                        ReviewPersistenceCommand.Confirm(
                            stableId = "pending-lunch",
                            targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID
                        )
                    )
                },
                async(Dispatchers.IO) {
                    persistence.execute(ReviewPersistenceCommand.Ignore("pending-lunch"))
                }
            ).awaitAll()
        }

        assertEquals(1, results.count { it is ReviewPersistenceResult.Applied })
        assertEquals(1, results.count { it is ReviewPersistenceResult.Conflict })
        assertNull(database.pendingEntryDao().getById("pending-lunch"))
        val ledgerCount = database.ledgerEntryDao().listLedgerEntries().count {
            it.originPendingEntryId == "pending-lunch"
        }
        val ignoredCount = database.ignoredEntryDao().listRecoverable(NOW).count {
            it.originalPendingEntryId == "pending-lunch"
        }
        assertEquals(1, ledgerCount + ignoredCount)
    }

    @Test
    fun changedLedgerMakesUndoConfirmReturnConflictAndReloadSafeState() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        val confirmed = persistence.execute(
            ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
        ) as ReviewPersistenceResult.Applied
        val undo = confirmed.undo as ReviewPersistenceUndo.Confirmed
        database.ledgerEntryDao().upsert(undo.receipt.ledgerEntry.copy(note = "later edit"))

        val result = persistence.execute(ReviewPersistenceCommand.UndoConfirm(undo.receipt))

        assertEquals(ReviewPersistenceResult.Conflict, result)
        assertNull(database.pendingEntryDao().getById("pending-lunch"))
        assertEquals("later edit", database.ledgerEntryDao().getById(undo.receipt.ledgerEntry.id)?.note)
    }

    @Test
    fun deletedLedgerHistoryDoesNotBlockReconfirmAndEachReceiptOnlyUndoesItsOwnLedger() = runBlocking {
        repository.ensureDefaultLedgerBook()
        repository.upsertPending(samplePending())
        val firstConfirmation = persistence.execute(
            ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
        ) as ReviewPersistenceResult.Applied
        val firstUndo = firstConfirmation.undo as ReviewPersistenceUndo.Confirmed

        assertTrue(
            persistence.execute(ReviewPersistenceCommand.UndoConfirm(firstUndo.receipt))
                is ReviewPersistenceResult.Applied
        )
        assertEquals(
            ReviewPersistenceResult.AlreadyApplied,
            persistence.execute(ReviewPersistenceCommand.UndoConfirm(firstUndo.receipt))
        )

        val secondConfirmation = persistence.execute(
            ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
        ) as ReviewPersistenceResult.Applied
        val secondUndo = secondConfirmation.undo as ReviewPersistenceUndo.Confirmed

        assertEquals(
            ReviewPersistenceResult.Conflict,
            persistence.execute(ReviewPersistenceCommand.UndoConfirm(firstUndo.receipt))
        )
        assertTrue(
            persistence.execute(ReviewPersistenceCommand.UndoConfirm(secondUndo.receipt))
                is ReviewPersistenceResult.Applied
        )
        assertEquals(secondUndo.receipt.pendingSnapshot, database.pendingEntryDao().getById("pending-lunch"))
        assertEquals(2, database.ledgerEntryDao().listAllLedgerEntries().size)
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
    }

    @Test
    fun outboxFailureRollsBackLedgerInsertAndPendingDelete() = runBlocking {
        val calls = AtomicInteger()
        val failingRepository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = {
                if (calls.incrementAndGet() == 1) "ledger-before-failure" else error("outbox failure")
            }
        )
        val failingPersistence = ReviewQueuePersistence(
            repository = failingRepository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        )
        failingRepository.ensureDefaultLedgerBook()
        failingRepository.upsertPending(samplePending())
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity(enabled = true))

        val failure = runCatching {
            failingPersistence.execute(
                ReviewPersistenceCommand.Confirm("pending-lunch", DEFAULT_LEDGER_BOOK_ID)
            )
        }.exceptionOrNull()

        assertEquals("outbox failure", failure?.message)
        assertEquals("pending-lunch", database.pendingEntryDao().getById("pending-lunch")?.id)
        assertTrue(database.ledgerEntryDao().listAllLedgerEntries().isEmpty())
        assertEquals(0, database.ledgerSyncDao().outboxCount())
    }

    @Test
    fun captureInsertDoesNotOverwriteAnExistingStableId() = runBlocking {
        repository.upsertPending(samplePending(id = "stable-capture", note = "first"))

        val inserted = persistence.insertCapturedEntries(
            listOf(
                samplePending(id = "stable-capture", note = "replacement")
                    .toReviewEntry(ZoneId.of("UTC"))
            )
        )

        assertEquals(0, inserted)
        assertEquals("first", database.pendingEntryDao().getById("stable-capture")?.note)
    }

    @Test
    fun legacyDedupeMessagesAreNotExposedAsUserNotes() = runBlocking {
        repository.upsertPending(
            samplePending(
                id = "pending-suspect",
                confidence = ConfidenceState.DUPLICATE_SUSPECT,
                note = "可能与 午餐 重复，请确认后再入账"
            )
        )
        repository.upsertPending(
            samplePending(
                id = "pending-merged",
                confidence = ConfidenceState.HIGH,
                captureReason = CaptureReason.DUPLICATE_MERGE,
                note = "已合并通知捕获和账单同步证据"
            )
        )

        val restored = persistence.observeState().first().pendingEntries.associateBy { it.id }

        assertNull(restored.getValue("pending-suspect").note)
        assertNull(restored.getValue("pending-merged").note)
    }

    private fun samplePending(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        captureReason: CaptureReason = CaptureReason.BILL_SYNC,
        suggestedCategoryId: String? = null,
        fundingAccountId: Long? = null,
        fundingAccountLabel: String? = "支付宝余额",
        note: String? = "客户会议"
    ): PendingEntryEntity = PendingEntryEntity(
        id = id,
        source = PaymentSource.ALIPAY,
        captureReason = captureReason,
        confidence = confidence,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3_590,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = NOW - 60_000,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = suggestedCategoryId,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = fundingAccountLabel,
        note = note,
        evidenceSummary = "支付宝账单 午餐 35.90",
        parsedFieldsText = "商户=午餐\n金额=35.90"
    )

    private fun sampleEdit() = ReviewEntryEdit(
        title = "工作餐",
        amountMinor = 4_580,
        transactionTimeEpochMillis = NOW - 30_000,
        transactionKind = TransactionKind.EXPENSE,
        categoryId = "food",
        category = "餐饮",
        fundingAccountId = null,
        fundingAccountLabel = "支付宝余额",
        note = "已编辑"
    )

    private fun sampleIgnored(
        id: String,
        fundingAccountId: Long? = null
    ): IgnoredEntryEntity = IgnoredEntryEntity(
        id = id,
        originalPendingEntryId = "pending-ignored",
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3_590,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = NOW - 120_000,
        capturedAtEpochMillis = NOW - 60_000,
        suggestedCategoryId = null,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "微信零钱",
        note = null,
        evidenceSummary = "微信支付收款凭证 午餐 35.90",
        parsedFieldsText = "商户=午餐\n金额=35.90",
        ignoredAtEpochMillis = NOW - 30_000,
        expiresAtEpochMillis = NOW + LocalLedgerRepository.IGNORED_RETENTION_MILLIS,
        reason = IgnoreReason.USER_IGNORED
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
