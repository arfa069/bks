package com.bks.model.data.local.sync

import com.bks.model.data.local.database.CategorizationRuleDao
import com.bks.model.data.local.database.CategoryDao
import com.bks.model.data.local.database.FundingAccountDao
import com.bks.model.data.local.database.LedgerBookDao
import com.bks.model.data.local.database.LedgerEntryDao
import com.bks.model.data.local.database.LedgerSyncDao
import com.bks.model.data.local.sync.LedgerSyncLocalStore
import com.bks.model.data.local.ledger.toEntity
import com.bks.model.data.remote.sync.entityId
import com.bks.model.data.remote.sync.entityType
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.LedgerBook
import com.bks.model.domain.ledger.LedgerEntry
import com.bks.viewmodel.ledger.label
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.sync.LedgerSyncConflict
import com.bks.model.domain.sync.LedgerSyncEntityType
import com.bks.model.domain.sync.LedgerSyncMutationResult
import com.bks.model.domain.sync.LedgerSyncPayload
import com.bks.model.domain.sync.LedgerSyncRecord
import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.CategorizationRuleEntity
import com.bks.model.data.local.database.CategoryEntity
import com.bks.model.data.local.settings.DefaultCategorizationRules
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.data.local.database.FundingAccountEntity
import com.bks.model.domain.ledger.FundingAccountSourceScope
import com.bks.model.data.local.database.LedgerBookEntity
import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class LedgerSyncRemoteMergeTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var store: LedgerSyncLocalStore
    private var nextId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        store = LedgerSyncLocalStore(
            database = database,
            clock = { NOW },
            idGenerator = { "sync-${++nextId}" }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun identicalCloudRecordDoesNotCreateConflictMutationDuringInitialMerge() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")

        store.mergeSnapshot(listOf(remoteBook(version = 4, name = "本机账本")))
        store.reconcile()

        assertTrue(store.listMutations(100).isEmpty())
        assertEquals(4L, database.ledgerSyncDao().getMetadata("LEDGER_BOOK", "book-local")?.serverVersion)
    }

    @Test
    fun pristineGeneratedDefaultsAdoptCloudSnapshotWithoutConflictMutations() = runBlocking {
        val localCreatedAt = NOW + 1_000
        val localCategory = DefaultCategories.systemDefaults(localCreatedAt).first { it.id == "food" }
        val localRule = DefaultCategorizationRules.rules.first { it.id == "default-food" }
        database.categoryDao().upsert(localCategory.toEntity())
        database.categorizationRuleDao().upsert(localRule)
        store.enable("profile-a")

        val cloudCategory = LedgerSyncPayload.Category(
            id = localCategory.id,
            name = localCategory.name,
            kind = localCategory.kind?.name,
            sortOrder = localCategory.sortOrder,
            isSystem = localCategory.isSystem,
            createdAtMillis = NOW
        )
        val cloudRule = LedgerSyncPayload.CategorizationRule(
            id = localRule.id,
            merchantContains = localRule.merchantContains,
            titleContains = localRule.titleContains,
            sourceLabel = localRule.sourceLabel,
            transactionKind = localRule.transactionKind,
            category = "云端保留分类",
            priority = localRule.priority,
            enabled = localRule.enabled,
            updatedAtMillis = NOW - 1
        )
        store.mergeSnapshot(
            listOf(
                LedgerSyncRecord(
                    LedgerSyncEntityType.CATEGORY,
                    cloudCategory.id,
                    3,
                    1,
                    false,
                    cloudCategory
                ),
                LedgerSyncRecord(
                    LedgerSyncEntityType.CATEGORIZATION_RULE,
                    cloudRule.id,
                    5,
                    2,
                    false,
                    cloudRule
                )
            )
        )

        assertTrue(store.listMutations(100).isEmpty())
        assertEquals(NOW, database.categoryDao().getCategory(localCategory.id)?.createdAtEpochMillis)
        assertEquals("云端保留分类", database.categorizationRuleDao().getById(localRule.id)?.category)
    }

    @Test
    fun legacyPristineDefaultRulesAdoptCloudSnapshotWithoutConflictMutations() = runBlocking {
        val legacyFoodRule = CategorizationRuleEntity(
            id = "default-food",
            merchantContains = "",
            titleContains = "餐饮",
            sourceLabel = "",
            transactionKind = "支出",
            category = "餐饮",
            priority = 100,
            enabled = true,
            updatedAtEpochMillis = 0
        )
        val legacyRefundRule = CategorizationRuleEntity(
            id = "default-refund",
            merchantContains = "",
            titleContains = "",
            sourceLabel = "",
            transactionKind = "退款",
            category = "退款",
            priority = 200,
            enabled = true,
            updatedAtEpochMillis = 0
        )
        database.categorizationRuleDao().upsert(legacyFoodRule)
        database.categorizationRuleDao().upsert(legacyRefundRule)
        store.enable("profile-a")

        val cloudFoodRule = LedgerSyncPayload.CategorizationRule(
            id = legacyFoodRule.id,
            merchantContains = "餐饮",
            titleContains = "",
            sourceLabel = "",
            transactionKind = "",
            category = "云端餐饮",
            priority = legacyFoodRule.priority,
            enabled = true,
            updatedAtMillis = NOW
        )
        val cloudRefundRule = LedgerSyncPayload.CategorizationRule(
            id = legacyRefundRule.id,
            merchantContains = "退款商户",
            titleContains = "",
            sourceLabel = "",
            transactionKind = "",
            category = "云端退款",
            priority = legacyRefundRule.priority,
            enabled = true,
            updatedAtMillis = NOW
        )

        store.mergeSnapshot(
            listOf(
                LedgerSyncRecord(
                    LedgerSyncEntityType.CATEGORIZATION_RULE,
                    cloudFoodRule.id,
                    2,
                    1,
                    false,
                    cloudFoodRule
                ),
                LedgerSyncRecord(
                    LedgerSyncEntityType.CATEGORIZATION_RULE,
                    cloudRefundRule.id,
                    3,
                    2,
                    false,
                    cloudRefundRule
                )
            )
        )

        assertTrue(store.listMutations(100).isEmpty())
        assertEquals("云端餐饮", database.categorizationRuleDao().getById(legacyFoodRule.id)?.category)
        assertEquals("云端退款", database.categorizationRuleDao().getById(legacyRefundRule.id)?.category)
    }

    @Test
    fun sameCategoryBusinessKeyUsesCloudIdAndQueuesDifferentLocalAttributesAsCandidate() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity(
                id = "local-food",
                name = "餐饮",
                kind = TransactionKind.EXPENSE,
                sortOrder = 9,
                isSystem = false,
                createdAtEpochMillis = NOW
            )
        )
        store.enable("profile-a")
        val cloudRecord = LedgerSyncRecord(
            entityType = LedgerSyncEntityType.CATEGORY,
            entityId = "cloud-food",
            version = 3,
            revision = 4,
            deleted = false,
            payload = LedgerSyncPayload.Category(
                id = "cloud-food",
                name = "餐饮",
                kind = TransactionKind.EXPENSE.name,
                sortOrder = 1,
                isSystem = true,
                createdAtMillis = NOW
            )
        )

        store.mergeSnapshot(listOf(cloudRecord))

        assertNull(database.categoryDao().getCategory("local-food"))
        assertEquals(1, database.categoryDao().getCategory("cloud-food")?.sortOrder)
        val mutation = store.listMutations(100).single()
        assertEquals("cloud-food", mutation.entityId)
        assertEquals(0L, mutation.baseVersion)
        assertEquals(9, (mutation.payload as LedgerSyncPayload.Category).sortOrder)
    }

    @Test
    fun acceptedCanonicalIdRemapsLocalCategoryWithoutCreatingDeleteTombstone() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity("local-food", "餐饮", TransactionKind.EXPENSE, 1, true, NOW)
        )
        store.enable("profile-a")
        store.reconcile()
        val mutation = store.listMutations(100).single()

        store.applyPushResults(
            listOf(
                LedgerSyncMutationResult(
                    mutationId = mutation.mutationId,
                    accepted = true,
                    version = 2,
                    revision = 2,
                    conflictId = null,
                    canonicalEntityId = "cloud-food"
                )
            )
        )
        store.reconcile()

        assertNull(database.categoryDao().getCategory("local-food"))
        assertNotNull(database.categoryDao().getCategory("cloud-food"))
        assertNotNull(database.ledgerSyncDao().getMetadata("CATEGORY", "cloud-food"))
        assertTrue(store.listMutations(100).isEmpty())
    }

    @Test
    fun remoteConflictKeepsCanonicalRecordVisibleAndStoresCandidate() = runBlocking {
        store.enable("profile-a")
        val canonical = remoteBook(version = 2, name = "云端账本")
        val candidate = LedgerSyncPayload.LedgerBook("book-local", "本机账本", NOW)

        store.applyRemote(
            records = listOf(canonical),
            conflicts = listOf(
                LedgerSyncConflict(
                    conflictId = "conflict-1",
                    entityType = LedgerSyncEntityType.LEDGER_BOOK,
                    entityId = "book-local",
                    canonicalVersion = 2,
                    canonicalDeleted = false,
                    canonicalPayload = canonical.payload,
                    candidateDeleted = false,
                    candidatePayload = candidate,
                    createdAtMillis = NOW
                )
            )
        )

        assertEquals("云端账本", database.ledgerBookDao().getById("book-local")?.name)
        assertEquals("conflict-1", store.conflicts.first().single().conflictId)
        assertTrue(database.ledgerSyncDao().getMetadata("LEDGER_BOOK", "book-local")?.blockedByConflict == true)
    }

    @Test
    fun remoteGeneratedDefaultConflictsUseCanonicalWithoutPromptingForPristineCandidates() = runBlocking {
        val localCreatedAt = NOW + 1_000
        val localCategory = DefaultCategories.systemDefaults(localCreatedAt).first { it.id == "food" }
        val localRule = DefaultCategorizationRules.rules.first { it.id == "default-food" }
        val housingRule = DefaultCategorizationRules.rules.first { it.id == "default-housing" }
        val editedRule = housingRule.copy(priority = 999, updatedAtEpochMillis = localCreatedAt)
        database.categoryDao().upsert(localCategory.toEntity())
        database.categorizationRuleDao().upsert(localRule)
        database.categorizationRuleDao().upsert(editedRule)
        store.enable("profile-a")

        val canonicalCategory = LedgerSyncPayload.Category(
            localCategory.id,
            localCategory.name,
            localCategory.kind?.name,
            localCategory.sortOrder,
            localCategory.isSystem,
            NOW
        )
        val candidateCategory = canonicalCategory.copy(createdAtMillis = localCreatedAt)
        val canonicalRule = LedgerSyncPayload.CategorizationRule(
            localRule.id,
            localRule.merchantContains,
            localRule.titleContains,
            localRule.sourceLabel,
            localRule.transactionKind,
            "云端保留分类",
            localRule.priority,
            localRule.enabled,
            NOW - 1
        )
        val candidateRule = canonicalRule.copy(
            category = localRule.category,
            updatedAtMillis = localRule.updatedAtEpochMillis
        )
        val editedCandidate = LedgerSyncPayload.CategorizationRule(
            editedRule.id,
            editedRule.merchantContains,
            editedRule.titleContains,
            editedRule.sourceLabel,
            editedRule.transactionKind,
            editedRule.category,
            editedRule.priority,
            editedRule.enabled,
            editedRule.updatedAtEpochMillis
        )
        val editedCanonical = LedgerSyncPayload.CategorizationRule(
            housingRule.id,
            housingRule.merchantContains,
            housingRule.titleContains,
            housingRule.sourceLabel,
            housingRule.transactionKind,
            housingRule.category,
            housingRule.priority,
            housingRule.enabled,
            housingRule.updatedAtEpochMillis
        )

        store.applyRemote(
            records = emptyList(),
            conflicts = listOf(
                LedgerSyncConflict(
                    "generated-category",
                    LedgerSyncEntityType.CATEGORY,
                    localCategory.id,
                    3,
                    false,
                    canonicalCategory,
                    false,
                    candidateCategory,
                    NOW
                ),
                LedgerSyncConflict(
                    "generated-rule",
                    LedgerSyncEntityType.CATEGORIZATION_RULE,
                    localRule.id,
                    4,
                    false,
                    canonicalRule,
                    false,
                    candidateRule,
                    NOW
                ),
                LedgerSyncConflict(
                    "edited-rule",
                    LedgerSyncEntityType.CATEGORIZATION_RULE,
                    editedRule.id,
                    2,
                    false,
                    editedCanonical,
                    false,
                    editedCandidate,
                    NOW
                )
            )
        )

        assertEquals(listOf("edited-rule"), store.conflicts.first().map { it.conflictId })
        assertEquals(NOW, database.categoryDao().getCategory(localCategory.id)?.createdAtEpochMillis)
        assertEquals("云端保留分类", database.categorizationRuleDao().getById(localRule.id)?.category)
        assertFalse(
            database.ledgerSyncDao()
                .getMetadata(LedgerSyncEntityType.CATEGORY.name, localCategory.id)
                ?.blockedByConflict == true
        )
        assertTrue(
            database.ledgerSyncDao()
                .getMetadata(LedgerSyncEntityType.CATEGORIZATION_RULE.name, editedRule.id)
                ?.blockedByConflict == true
        )
    }

    @Test
    fun remoteParentUpdatesPreserveExistingLedgerEntryReferences() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity("food", "旧分类", TransactionKind.EXPENSE, 9, false, NOW)
        )
        val fundingId = database.fundingAccountDao().insertIgnore(
            FundingAccountEntity(
                syncId = "funding-sync",
                sourceScope = FundingAccountSourceScope.WECHAT,
                paymentSource = PaymentSource.WECHAT,
                label = "旧资金账户",
                createdAtEpochMillis = NOW
            )
        )
        database.ledgerBookDao().insert(localBook())
        database.ledgerEntryDao().upsert(
            LedgerEntryEntity(
                id = "entry-1",
                ledgerBookId = "book-local",
                paymentSource = PaymentSource.WECHAT,
                originalCaptureSource = null,
                entryOrigin = EntryOrigin.MANUAL,
                originPendingEntryId = null,
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 100,
                currency = "CNY",
                merchantTitle = "保留引用",
                transactionTimeEpochMillis = NOW,
                categoryId = "food",
                fundingAccountId = fundingId,
                note = null,
                evidenceSummary = null,
                parsedFieldsText = null,
                confirmedAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                deletedAtEpochMillis = null
            )
        )

        store.applyRemote(
            records = listOf(
                LedgerSyncRecord(
                    LedgerSyncEntityType.CATEGORY,
                    "food",
                    2,
                    1,
                    false,
                    LedgerSyncPayload.Category(
                        "food", "云端分类", TransactionKind.EXPENSE.name, 1, true, NOW - 1
                    )
                ),
                LedgerSyncRecord(
                    LedgerSyncEntityType.FUNDING_ACCOUNT,
                    "funding-sync",
                    2,
                    2,
                    false,
                    LedgerSyncPayload.FundingAccount(
                        "funding-sync",
                        FundingAccountSourceScope.WECHAT.name,
                        PaymentSource.WECHAT.name,
                        "云端资金账户",
                        NOW - 1
                    )
                ),
                remoteBook(version = 2, name = "云端账本")
            ),
            conflicts = emptyList()
        )

        val entry = database.ledgerEntryDao().getById("entry-1")
        assertEquals("food", entry?.categoryId)
        assertEquals(fundingId, entry?.fundingAccountId)
        assertEquals("book-local", entry?.ledgerBookId)
        assertEquals("云端分类", database.categoryDao().getCategory("food")?.name)
        assertEquals(
            "云端资金账户",
            database.fundingAccountDao().findBySyncId("funding-sync")?.label
        )
        assertEquals("云端账本", database.ledgerBookDao().getById("book-local")?.name)
    }

    @Test
    fun newerRemoteTombstoneWinsOverOlderCreateFromSamePullPage() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")
        val payload = LedgerSyncPayload.LedgerEntry(
            id = "entry-1",
            ledgerBookId = "book-local",
            paymentSource = null,
            originalCaptureSource = null,
            entryOrigin = EntryOrigin.MANUAL.name,
            flowDirection = FlowDirection.OUTFLOW.name,
            transactionKind = TransactionKind.EXPENSE.name,
            amountMinor = 100,
            currency = "CNY",
            merchantTitle = "已永久删除账目",
            transactionTimeMillis = NOW,
            categoryId = null,
            fundingAccountSyncId = null,
            note = null,
            confirmedAtMillis = NOW,
            updatedAtMillis = NOW,
            deletedAtMillis = null
        )

        store.applyRemote(
            records = listOf(
                LedgerSyncRecord(
                    LedgerSyncEntityType.LEDGER_ENTRY,
                    "entry-1",
                    version = 1,
                    revision = 10,
                    deleted = false,
                    payload = payload
                ),
                LedgerSyncRecord(
                    LedgerSyncEntityType.LEDGER_ENTRY,
                    "entry-1",
                    version = 2,
                    revision = 11,
                    deleted = true,
                    payload = null
                )
            ),
            conflicts = emptyList()
        )

        assertNull(database.ledgerEntryDao().getById("entry-1"))
        assertTrue(database.ledgerSyncDao().getMetadata("LEDGER_ENTRY", "entry-1")?.deleted == true)
    }

    private fun localBook() = LedgerBookEntity("book-local", "本机账本", NOW)

    private fun remoteBook(version: Long, name: String) = LedgerSyncRecord(
        entityType = LedgerSyncEntityType.LEDGER_BOOK,
        entityId = "book-local",
        version = version,
        revision = version,
        deleted = false,
        payload = LedgerSyncPayload.LedgerBook("book-local", name, NOW)
    )

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
