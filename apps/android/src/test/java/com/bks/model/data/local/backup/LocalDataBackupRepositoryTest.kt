package com.bks.model.data.local.backup

import com.bks.model.data.local.backup.decryptPersistedLocalData
import com.bks.model.data.local.backup.encryptPersistedLocalData
import com.bks.model.data.local.backup.LocalDataBackupRepository
import com.bks.model.data.local.backup.PersistedLocalDataSnapshot
import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategoryDao
import com.bks.model.data.local.database.FundingAccountDao
import com.bks.model.data.local.database.IgnoredEntryDao
import com.bks.model.data.local.database.LedgerBookDao
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.database.LocalSettingsDao
import com.bks.model.data.local.database.PendingEntryDao
import com.bks.model.data.remote.sync.entityId
import com.bks.model.data.remote.sync.entityType
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.LedgerEntry
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.domain.settings.UserPreferences
import com.bks.viewmodel.ledger.label
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.AccountSyncMetadataEntity
import com.bks.model.data.local.database.AccountSyncOutboxEntity
import com.bks.model.data.local.database.AccountSyncConflictEntity
import com.bks.model.data.local.database.AccountSyncStateEntity
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.data.local.database.CategorizationRuleEntity
import com.bks.model.data.local.database.CategoryEntity
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_NAME
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.domain.ledger.FundingAccountSourceScope
import com.bks.model.domain.ledger.IgnoreReason
import com.bks.model.data.local.database.IgnoredEntryEntity
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.data.local.ledger.TestLedgerRepository
import com.bks.model.data.local.settings.RoomPreferencesRepository
import com.bks.model.data.local.database.LocalSettingsEntity
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.data.local.database.PendingEntryEntity
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.data.local.review.RoomReviewQueueRepository
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDataBackupRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var backupRepository: LocalDataBackupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        backupRepository = LocalDataBackupRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun versionFiveBackupRoundTripRestoresAllLedgersReferencesAndUiState() = runBlocking {
        populateDatabase()
        val expected = readSnapshot().let { snapshot ->
            snapshot.copy(settings = requireNotNull(snapshot.settings).copy(automaticBookkeepingEnabled = false))
        }

        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)

        assertTrue(backup.startsWith("AUTO_ACCOUNTING_BACKUP_V5:"))
        assertFalse(backup.contains("Coffee Shop"))
        assertFalse(
            requireNotNull(decryptPersistedLocalData(backup, PASSPHRASE).settings)
                .automaticBookkeepingEnabled
        )

        TestLedgerRepository(database).clearLocalData()
        RoomPreferencesRepository(database).clearLocalData()
        backupRepository.importEncryptedBackup(backup, PASSPHRASE)

        assertEquals(expected, readSnapshot())

        val ledgerRepository = TestLedgerRepository(database)
        val preferencesRepository = RoomPreferencesRepository(database)
        val reviewState = RoomReviewQueueRepository(
            database = database,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        ).observeSnapshot().first()

        assertEquals(listOf("pending-1"), reviewState.pendingEntries.map { it.id })
        assertEquals(listOf("ignored-1"), reviewState.ignoredEntries.map { it.id })
        assertEquals(
            listOf("ledger-1"),
            ledgerRepository.ledgerEntries(DEFAULT_LEDGER_BOOK_ID).first().map { it.id }
        )
        assertEquals(
            listOf("ledger-deleted"),
            ledgerRepository.deletedLedgerEntries(SECONDARY_LEDGER_BOOK_ID).first().map { it.id }
        )
        assertEquals(
            listOf(DEFAULT_LEDGER_BOOK_ID, SECONDARY_LEDGER_BOOK_ID),
            ledgerRepository.ledgerBooks.first().map { it.id }
        )
        assertEquals(
            SECONDARY_LEDGER_BOOK_ID,
            ledgerRepository.activeLedgerBook.first()?.id
        )
        assertEquals(listOf("rule-1"), preferencesRepository.categorizationRules.first().map { it.id })
        assertTrue(preferencesRepository.userPreferences.first().aiSettings.aiConsentGranted)
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test
    fun importingBackupCreatesOutboxWhenAccountSyncIsEnabled() = runBlocking {
        populateDatabase()
        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity(profileKey = "profile-a", enabled = true))
        database.ledgerSyncDao().upsertMetadata(
            AccountSyncMetadataEntity(
                entityType = "LEDGER_BOOK",
                entityId = "obsolete-book",
                serverVersion = 3,
                syncedPayload = "{}",
                deleted = false
            )
        )
        database.ledgerSyncDao().upsertMetadata(
            AccountSyncMetadataEntity(
                entityType = "LEDGER_ENTRY",
                entityId = "ledger-1",
                serverVersion = 5,
                syncedPayload = "restore-before-server-payload",
                deleted = false,
                blockedByConflict = true
            )
        )
        database.ledgerSyncDao().upsertConflict(
            AccountSyncConflictEntity(
                conflictId = "restore-before-conflict",
                entityType = "LEDGER_ENTRY",
                entityId = "ledger-1",
                canonicalVersion = 5,
                canonicalDeleted = false,
                canonicalPayload = "restore-before-canonical-private-payload",
                candidateDeleted = false,
                candidatePayload = "restore-before-candidate-private-payload",
                createdAtMillis = NOW - 2
            )
        )
        database.ledgerSyncDao().upsertOutbox(
            AccountSyncOutboxEntity(
                mutationId = "stale-create",
                entityType = "LEDGER_ENTRY",
                entityId = "restore-replaced-private-entry",
                baseVersion = 0,
                deleted = false,
                payload = "stale-private-payload",
                createdAtMillis = NOW - 1
            )
        )

        backupRepository.importEncryptedBackup(backup, PASSPHRASE)

        val mutations = database.ledgerSyncDao().listOutbox(100)
        assertTrue(mutations.any { it.entityType == "LEDGER_ENTRY" && !it.deleted })
        assertTrue(mutations.any { it.entityId == "obsolete-book" && it.deleted && it.baseVersion == 3L })
        assertFalse(mutations.any { it.entityId == "restore-replaced-private-entry" })
        assertFalse(mutations.any { it.payload == "stale-private-payload" })
        assertTrue(mutations.any { it.entityId == "ledger-1" && !it.deleted })
        assertFalse(
            mutations.any {
                it.payload == "restore-before-canonical-private-payload" ||
                    it.payload == "restore-before-candidate-private-payload"
            }
        )
        assertFalse(
            database.ledgerSyncDao().getMetadata("LEDGER_ENTRY", "ledger-1")?.blockedByConflict
                ?: true
        )
        assertTrue(database.ledgerSyncDao().observeConflicts().first().isEmpty())
        assertTrue(database.fundingAccountDao().getAllFundingAccounts().all { it.syncId != null })
    }

    @Test
    fun wrongPassphraseFailsBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)

        val failure = runCatching {
            backupRepository.importEncryptedBackup(backup, "wrong-passphrase")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("pending-1", database.pendingEntryDao().getById("pending-1")?.id)
        assertEquals("ledger-1", database.ledgerEntryDao().getById("ledger-1")?.id)
        assertEquals("rule-1", database.categorizationRuleDao().listRules().single().id)
    }

    @Test
    fun exportRequiresPassphraseLongerThanEightCharacters() = runBlocking {
        val failure = runCatching {
            backupRepository.exportEncryptedBackup("12345678")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(
            "Backup passphrase must be longer than 8 characters",
            failure?.message
        )
    }

    @Test
    fun validationDoesNotReplaceCurrentSnapshot() = runBlocking {
        populateDatabase()
        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)
        database.pendingEntryDao().deleteAll()

        backupRepository.validateEncryptedBackup(backup, PASSPHRASE)

        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
        assertEquals("ledger-1", database.ledgerEntryDao().getById("ledger-1")?.id)
    }

    @Test
    fun invalidBackupFieldsFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalid = original.copy(
            ledgerEntries = original.ledgerEntries.mapIndexed { index, entry ->
                if (index == 0) entry.copy(amountMinor = -1) else entry
            }
        )

        val failure = runCatching {
            backupRepository.importEncryptedBackup(
                encryptPersistedLocalData(invalid, PASSPHRASE),
                PASSPHRASE
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun duplicateCategoryNamesInVersionFiveBackupFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val existingCategory = original.categories.single()
        val invalid = original.copy(
            categories = listOf(existingCategory.copy(id = "duplicate-food")) + original.categories
        )
        val invalidBackup = encryptPersistedLocalData(invalid, PASSPHRASE)

        assertTrue(invalidBackup.startsWith("AUTO_ACCOUNTING_BACKUP_V5:"))

        val failure = runCatching {
            backupRepository.importEncryptedBackup(invalidBackup, PASSPHRASE)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("Backup contains duplicate category names", failure?.message)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun invalidRuleAndMissingReferencesFailDuringReadOnlyValidation() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalid = original.copy(
            pendingEntries = original.pendingEntries.map {
                it.copy(suggestedCategoryId = "missing-category")
            },
            categorizationRules = original.categorizationRules.map { it.copy(category = "") }
        )

        val failure = runCatching {
            backupRepository.validateEncryptedBackup(
                encryptPersistedLocalData(invalid, PASSPHRASE),
                PASSPHRASE
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun invalidLedgerBookAndActiveLedgerReferencesFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalidSnapshots = listOf(
            original.copy(
                ledgerEntries = original.ledgerEntries.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(ledgerBookId = "missing-ledger") else entry
                }
            ),
            original.copy(
                settings = requireNotNull(original.settings).copy(
                    activeLedgerId = "missing-ledger"
                )
            )
        )

        invalidSnapshots.forEach { invalid ->
            val failure = runCatching {
                backupRepository.importEncryptedBackup(
                    encryptPersistedLocalData(invalid, PASSPHRASE),
                    PASSPHRASE
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(original, readSnapshot())
        }
    }

    @Test
    fun unsupportedBackupFormatFailsBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val unsupportedFormat = backupRepository.exportEncryptedBackup(PASSPHRASE)
            .replaceFirst("AUTO_ACCOUNTING_BACKUP_V5:", "AUTO_ACCOUNTING_BACKUP_V6:")

        val failure = runCatching {
            backupRepository.importEncryptedBackup(unsupportedFormat, PASSPHRASE)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun versionTwoBackupImportsWithLegacyLedgerSemantics() = runBlocking {
        backupRepository.importEncryptedBackup(V2_BACKUP_FIXTURE, PASSPHRASE)

        val fundingAccount = database.fundingAccountDao().getAllFundingAccounts().single()
        val ledgerEntry = database.ledgerEntryDao().listLedgerEntries().single()

        assertEquals(FundingAccountSourceScope.WECHAT, fundingAccount.sourceScope)
        assertEquals(PaymentSource.WECHAT, fundingAccount.paymentSource)
        assertEquals(FlowDirection.INFLOW, ledgerEntry.flowDirection)
        assertEquals(PaymentSource.ALIPAY, ledgerEntry.paymentSource)
        assertEquals(PaymentSource.ALIPAY, ledgerEntry.originalCaptureSource)
        assertEquals(EntryOrigin.LEGACY_CAPTURE, ledgerEntry.entryOrigin)
        assertEquals(ledgerEntry.confirmedAtEpochMillis, ledgerEntry.updatedAtEpochMillis)
        assertEquals(null, ledgerEntry.deletedAtEpochMillis)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, ledgerEntry.ledgerBookId)
        assertEquals(
            LedgerBookEntity(
                id = DEFAULT_LEDGER_BOOK_ID,
                name = DEFAULT_LEDGER_BOOK_NAME,
                createdAtEpochMillis = 0
            ),
            database.ledgerBookDao().getAll().single()
        )
        assertEquals(
            DEFAULT_LEDGER_BOOK_ID,
            database.localSettingsDao().getById()?.activeLedgerId
        )
    }

    @Test
    fun versionThreeBackupReplacesReferencedDataAndUsesDefaultLedger() = runBlocking {
        populateDatabase()

        backupRepository.importEncryptedBackup(V3_BACKUP_FIXTURE, PASSPHRASE)

        val ledgerEntry = database.ledgerEntryDao().listAllLedgerEntries().single()

        assertEquals("v3-ledger", ledgerEntry.id)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, ledgerEntry.ledgerBookId)
        assertEquals("legacy evidence", ledgerEntry.evidenceSummary)
        assertEquals("amount=1.23", ledgerEntry.parsedFieldsText)
        assertEquals(
            LedgerBookEntity(
                id = DEFAULT_LEDGER_BOOK_ID,
                name = DEFAULT_LEDGER_BOOK_NAME,
                createdAtEpochMillis = 0
            ),
            database.ledgerBookDao().getAll().single()
        )
        assertEquals(
            DEFAULT_LEDGER_BOOK_ID,
            database.localSettingsDao().getById()?.activeLedgerId
        )
        assertTrue(database.categoryDao().getAllCategories().isEmpty())
        assertTrue(database.fundingAccountDao().getAllFundingAccounts().isEmpty())
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    private suspend fun populateDatabase() {
        database.ledgerBookDao().insertAll(
            listOf(
                LedgerBookEntity(
                    id = DEFAULT_LEDGER_BOOK_ID,
                    name = DEFAULT_LEDGER_BOOK_NAME,
                    createdAtEpochMillis = NOW - 10_000
                ),
                LedgerBookEntity(
                    id = SECONDARY_LEDGER_BOOK_ID,
                    name = "旅行账本",
                    createdAtEpochMillis = NOW - 5_000
                )
            )
        )
        database.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = "food",
                    name = "Food",
                    kind = TransactionKind.EXPENSE,
                    sortOrder = 1,
                    isSystem = true,
                    createdAtEpochMillis = NOW
                )
            )
        )
        database.fundingAccountDao().upsertAll(
            listOf(
                FundingAccountEntity(
                    id = 7,
                    sourceScope = FundingAccountSourceScope.WECHAT,
                    paymentSource = PaymentSource.WECHAT,
                    label = "Wallet",
                    createdAtEpochMillis = NOW
                )
            )
        )
        database.pendingEntryDao().upsertAll(listOf(samplePending()))
        val ledgerEntry = LedgerEntryEntity(
                    id = "ledger-1",
                    ledgerBookId = DEFAULT_LEDGER_BOOK_ID,
                    paymentSource = PaymentSource.ALIPAY,
                    originalCaptureSource = PaymentSource.ALIPAY,
                    entryOrigin = EntryOrigin.LEGACY_CAPTURE,
                    originPendingEntryId = "confirmed-source",
                    flowDirection = FlowDirection.OUTFLOW,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 2590,
                    currency = "CNY",
                    merchantTitle = "Metro",
                    transactionTimeEpochMillis = NOW - 2_000,
                    categoryId = "food",
                    fundingAccountId = 7,
                    note = "Commute",
                    evidenceSummary = "Alipay receipt",
                    parsedFieldsText = "amount=25.90",
                    confirmedAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                    deletedAtEpochMillis = null
                )
        database.ledgerEntryDao().upsertAll(
            listOf(
                ledgerEntry,
                ledgerEntry.copy(
                    id = "ledger-deleted",
                    ledgerBookId = SECONDARY_LEDGER_BOOK_ID,
                    deletedAtEpochMillis = NOW + 1_000
                )
            )
        )
        database.ignoredEntryDao().upsertAll(
            listOf(
                IgnoredEntryEntity(
                    id = "ignored-1",
                    originalPendingEntryId = "ignored-source",
                    source = PaymentSource.WECHAT,
                    captureReason = CaptureReason.NOTIFICATION,
                    confidence = ConfidenceState.NEEDS_REVIEW,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 990,
                    currency = "CNY",
                    merchantTitle = "Snack",
                    transactionTimeEpochMillis = NOW - 3_000,
                    capturedAtEpochMillis = NOW - 2_500,
                    suggestedCategoryId = "food",
                    fundingAccountId = 7,
                    fundingAccountLabel = "Wallet",
                    note = null,
                    evidenceSummary = "receipt",
                    parsedFieldsText = "amount=9.90",
                    ignoredAtEpochMillis = NOW - 1_000,
                    expiresAtEpochMillis = NOW + 10_000,
                    reason = IgnoreReason.USER_IGNORED,
                    suggestedCategoryLabel = "Food"
                )
            )
        )
        database.categorizationRuleDao().upsertAll(
            listOf(
                CategorizationRuleEntity(
                    id = "rule-1",
                    merchantContains = "Coffee",
                    titleContains = "",
                    sourceLabel = "WeChat",
                    transactionKind = "Expense",
                    category = "Food",
                    priority = 5,
                    enabled = true,
                    updatedAtEpochMillis = NOW
                )
            )
        )
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = true,
                enhancedContextGranted = true,
                activeLedgerId = SECONDARY_LEDGER_BOOK_ID,
                automaticBookkeepingEnabled = true
            )
        )
    }

    private suspend fun readSnapshot(): PersistedLocalDataSnapshot =
        PersistedLocalDataSnapshot(
            categories = database.categoryDao().getAllCategories(),
            fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
            pendingEntries = database.pendingEntryDao().listPendingEntries(),
            ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries().sortedBy { it.id },
            ignoredEntries = database.ignoredEntryDao().listAll(),
            categorizationRules = database.categorizationRuleDao().listRules(),
            settings = database.localSettingsDao().getById(),
            ledgerBooks = database.ledgerBookDao().getAll()
        )

    private fun samplePending(): PendingEntryEntity = PendingEntryEntity(
        id = "pending-1",
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.HIGH,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3590,
        currency = "CNY",
        merchantTitle = "Coffee Shop",
        transactionTimeEpochMillis = NOW - 1_000,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = "food",
        fundingAccountId = 7,
        fundingAccountLabel = "Wallet",
        note = "Meeting",
        evidenceSummary = "receipt",
        parsedFieldsText = "amount=35.90",
        suggestedCategoryLabel = "Food"
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val PASSPHRASE = "Aa123456!"
        const val SECONDARY_LEDGER_BOOK_ID = "travel-ledger"
        const val V2_BACKUP_FIXTURE =
            "AUTO_ACCOUNTING_BACKUP_V2:" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwd+ybCCUSB4eWV/jCNk3d+5mIsNnmnR5Rar1Rfo" +
                "3eq4hqIRfuKYvQPAVpT9E4NW+9N7+9Ab5xVRSsRi9QcDMnIh2tdTJEbhGcvtz0P9sLWRk9FNBSpK" +
                "feMiF8Jn2/Go4SXEq45NeWLbAig6JZhxlxfUU9BOrsiXPhxVH9LvFB4jPoE3St8BL7NrOay8C0QY" +
                "qfZE9o5PO/QznRyp2RoYLlwPR4wZqkeD9vgdpgikdYcWNm4xvY0wptd3PXqzSQUbfIXyu+M16Tn" +
                "yZRS928CX4ZVwfZAx"
        const val V3_BACKUP_FIXTURE =
            "AUTO_ACCOUNTING_BACKUP_V3:" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwd+ybCCUSB5eWV/jCNk3d65mIsNnmnR4xar1RjJ" +
                "q4ScopJ2G5Cf6mKsPKbMUMsWMO19w9AdsFASAoU29AcDPDMI+PFxHmCPNP7d/XTKsLWRlZdOGS5N" +
                "aK11F8JprNGe1Qnkn+YhHAW6YVE6Xph3xVHRSMcKrsicaC91WKmdd3ZBE7tuSt8BIv8OOs3fcmV" +
                "KzJA+9I8oWpbVgn/rqH58QD9qRowZqkvjm5dowDX0NMZWcQtVvY0xOegVBXqzSQUbfODyu+Naiga" +
                "o+NQz8fdFNQpUnzTFYS8etOriZ+Kzm8jtw63viHDKgoA="
    }
}
