package com.bks.model.application.billsync

import com.bks.model.application.billsync.BillSyncCaptureProcessor
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.PendingEntryDao
import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.settings.PreferencesRepository
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.data.local.ledger.LocalLedgerRepository
import com.bks.model.data.local.settings.LocalPreferencesRepository
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.data.local.ledger.ReviewQueuePersistence
import com.bks.model.domain.review.ReviewPersistenceCommand
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class BillSyncCaptureProcessorTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var persistence: ReviewQueuePersistence
    private lateinit var processor: BillSyncCaptureProcessor

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = LocalLedgerRepository(database, clock = { NOW })
        persistence = ReviewQueuePersistence(repository, nowProvider = { NOW }, zoneId = ZoneId.of("UTC"))
        processor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-21 10:00" }),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            clock = { NOW }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun acceptedCapturePersistsReviewOnlyAndConfirmationMovesItToLedger() = runBlocking {
        val result = processor.process(
            pageText = "支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12",
            paymentSource = PaymentSource.ALIPAY
        )

        assertEquals(1, result.insertedCount)
        val pending = database.pendingEntryDao().listPendingEntries().single()
        assertEquals(PaymentSource.ALIPAY, pending.source)
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, pending.captureReason)
        assertEquals(ConfidenceState.NEEDS_REVIEW, pending.confidence)
        assertNull(pending.fundingAccountId)
        assertNull(pending.fundingAccountLabel)
        assertNull(pending.evidenceSummary)
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
        val reviewEntry = persistence.loadSnapshot().pendingEntries.single()
        assertEquals(PaymentSource.ALIPAY, reviewEntry.source)
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, reviewEntry.captureReason)

        persistence.execute(
            ReviewPersistenceCommand.Confirm(
                stableId = pending.id,
                targetLedgerBookId = com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
            )
        )

        assertNull(database.pendingEntryDao().getById(pending.id))
        val ledger = database.ledgerEntryDao().listLedgerEntries().single()
        assertEquals(PaymentSource.ALIPAY, ledger.paymentSource)
        assertNull(ledger.fundingAccountId)
        assertEquals(pending.id, ledger.originPendingEntryId)
    }

    @Test
    fun repeatedCapturePersistsSeparatePendingEntries() = runBlocking {
        var now = NOW
        val repeatedProcessor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-21 10:00" }),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            clock = { now }
        )
        val page = "支付成功\n¥4.99\n交易方式\n花呗"

        repeatedProcessor.process(page, PaymentSource.ALIPAY)
        now += 1
        repeatedProcessor.process(page, PaymentSource.ALIPAY)

        assertEquals(2, database.pendingEntryDao().listPendingEntries().size)
    }

    @Test
    fun recognitionDoesNotPersistUntilExplicitlyRequested() = runBlocking {
        val recognized = processor.recognize(
            "支付成功\n¥5.99\n交易方式\n花呗",
            PaymentSource.ALIPAY
        )

        assertTrue(recognized.recognized)
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())

        processor.persist(recognized)

        assertEquals(1, database.pendingEntryDao().listPendingEntries().size)
    }

    @Test
    fun persistenceReportsDatabaseInsertCountInsteadOfParsedEntryCount() = runBlocking {
        val recognized = processor.recognize(
            "支付成功\n¥5.99\n交易方式\n花呗",
            PaymentSource.ALIPAY
        )

        val first = processor.persist(recognized)
        val duplicate = processor.persist(recognized)

        assertEquals(1, first.insertedCount)
        assertEquals(0, duplicate.insertedCount)
        assertEquals(1, duplicate.createdEntries.size)
        assertEquals(1, database.pendingEntryDao().listPendingEntries().size)
    }

    private companion object {
        const val NOW = 1_755_741_600_000L
    }
}
