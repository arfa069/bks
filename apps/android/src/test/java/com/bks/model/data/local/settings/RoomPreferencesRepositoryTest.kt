package com.bks.model.data.local.settings

import com.bks.model.data.local.database.BksDatabase
import com.bks.model.data.local.database.CategorizationRuleEntity
import com.bks.model.data.local.database.DEFAULT_CATEGORIZATION_RULES_CALLBACK
import com.bks.model.data.local.settings.DefaultCategorizationRules
import com.bks.model.data.local.settings.RoomPreferencesRepository
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.settings.UserPreferences
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.applyCategorizationSuggestion
import com.bks.viewmodel.review.ReviewEntryUiModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomPreferencesRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun defaultFundingCacheIsIsolatedByAccountKeyAndRetainsPendingUpload() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = RoomPreferencesRepository(database)
        repository.cacheDefaultFundingAccount("account-a", "funding-a", pendingUpload = true)
        repository.cacheDefaultFundingAccount("account-b", null, pendingUpload = false)

        assertEquals("funding-a", repository.getCachedDefaultFundingAccount("account-a")?.syncId)
        assertTrue(repository.getCachedDefaultFundingAccount("account-a")!!.pendingUpload)
        assertEquals(null, repository.getCachedDefaultFundingAccount("account-b")?.syncId)
        database.close()
    }

    @Test
    fun newDatabaseStartsWithEditableDefaultRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "default-rules-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(
            context,
            BksDatabase::class.java,
            databaseName
        )
            .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
            .allowMainThreadQueries()
            .build()

        val rules = runBlocking { database.categorizationRuleDao().listRules() }

        assertEquals(
            DefaultCategorizationRules.rules.map { it.id }.toSet(),
            rules.map { it.id }.toSet()
        )
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun editedAndDeletedDefaultRulesAreNotRestoredOnReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "edited-default-rules-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabaseWithDefaults(context, databaseName)
        val repository = RoomPreferencesRepository(database)
        runBlocking {
            val rules = repository.categorizationRules.first()
                .filterNot { it.id == "default-shopping" }
                .map { rule ->
                    if (rule.id == "default-food") rule.copy(category = "购物") else rule
                }
            repository.updateCategorizationRules { rules }
        }
        database.close()

        val reopened = openDatabaseWithDefaults(context, databaseName)
        val persistedRules = runBlocking {
            RoomPreferencesRepository(reopened).categorizationRules.first()
        }

        assertFalse(persistedRules.any { it.id == "default-shopping" })
        assertEquals("购物", persistedRules.first { it.id == "default-food" }.category)
        reopened.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun concurrentRuleUpdatesUseTheLatestPersistedSnapshot() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = RoomPreferencesRepository(database)
        repository.updateCategorizationRules {
            listOf(
                sampleRule(id = "rule-a"),
                sampleRule(id = "rule-b"),
                sampleRule(id = "rule-c")
            )
        }

        coroutineScope {
            launch {
                repository.updateCategorizationRules { rules ->
                    rules.filterNot { it.id == "rule-a" }
                }
            }
            launch {
                repository.updateCategorizationRules { rules ->
                    rules.filterNot { it.id == "rule-b" }
                }
            }
        }

        assertEquals(
            listOf("rule-c"),
            database.categorizationRuleDao().listRules().map { it.id }
        )
        database.close()
    }

    @Test
    fun migrationSeedDoesNotOverwriteEditedRuleWithStableId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val edited = DefaultCategorizationRules.rules
            .first { it.id == "default-food" }
            .copy(category = "购物", updatedAtEpochMillis = 99)

        runBlocking {
            database.categorizationRuleDao().upsertAll(listOf(edited))
            DefaultCategorizationRules.insertMissing(database.openHelper.writableDatabase)
            val rules = database.categorizationRuleDao().listRules()

            assertEquals(8, rules.size)
            assertEquals("购物", rules.first { it.id == "default-food" }.category)
            assertEquals(99, rules.first { it.id == "default-food" }.updatedAtEpochMillis)
        }

        database.close()
    }

    @Test
    fun rulesAndSettingsSurviveDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "preferences-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        val database = openDatabase(context, databaseName)
        val repository = RoomPreferencesRepository(database)
        runBlocking {
            repository.updateCategorizationRules {
                listOf(
                    sampleRule(
                        id = "ride",
                        merchantContains = "metro",
                        category = "transport",
                        priority = 10,
                        updatedAtEpochMillis = 20
                    )
                )
            }
            repository.updateAiSettings(
                AiCategorizationSettings(
                    aiConsentGranted = true,
                    enhancedContextGranted = true
                )
            )
            repository.setAutomaticBookkeepingEnabled(true)
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val reopenedRepository = RoomPreferencesRepository(reopenedDatabase)

        val persistedRules = runBlocking { reopenedRepository.categorizationRules.first() }
        val persistedPreferences = runBlocking { reopenedRepository.userPreferences.first() }

        assertEquals(listOf("ride"), persistedRules.map { it.id })
        val persistedRule = persistedRules.single()
        assertEquals("metro", persistedRule.merchantContains)
        assertEquals("transport", persistedRule.category)
        assertEquals(10, persistedRule.priority)
        assertEquals(20, persistedRule.updatedAtEpochMillis)
        assertTrue(persistedPreferences.aiSettings.aiConsentGranted)
        assertTrue(persistedPreferences.aiSettings.enhancedContextGranted)
        assertTrue(persistedPreferences.automaticBookkeepingEnabled)

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun disablingAiPersistsWithEnhancedContextRevoked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "preferences-ai-disabled-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(context, databaseName)
        val repository = RoomPreferencesRepository(database)

        runBlocking {
            repository.updateAiSettings(
                AiCategorizationSettings(
                    aiConsentGranted = false,
                    enhancedContextGranted = true
                )
            )
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val preferences = runBlocking {
            RoomPreferencesRepository(reopenedDatabase).userPreferences.first()
        }

        assertFalse(preferences.aiSettings.aiConsentGranted)
        assertFalse(preferences.aiSettings.enhancedContextGranted)
        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun savedRuleAppliesToLaterPendingEntryAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "rule-match-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        val database = openDatabase(context, databaseName)
        runBlocking {
            RoomPreferencesRepository(database).updateCategorizationRules {
                listOf(
                    sampleRule(
                        id = "coffee",
                        merchantContains = "coffee",
                        category = "餐饮"
                    )
                )
            }
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val rules = runBlocking {
            RoomPreferencesRepository(reopenedDatabase).categorizationRules.first()
        }
        val entry = PendingEntry(
            id = "pending-coffee",
            source = PaymentSource.WECHAT,
            captureReason = CaptureReason.BILL_SYNC,
            confidence = ConfidenceState.NEEDS_REVIEW,
            transactionKind = TransactionKind.EXPENSE,
            amountMinor = 1_000,
            currency = "CNY",
            merchantTitle = "Coffee Shop",
            transactionTimeEpochMillis = 1,
            capturedAtEpochMillis = 1,
            suggestedCategoryId = null,
            fundingAccountId = null,
            fundingAccountLabel = null,
            note = null,
            evidenceSummary = null,
            parsedFieldsText = null,
            suggestedCategoryLabel = null
        )

        val suggestedEntry = entry.applyCategorizationSuggestion(
            rules,
            DefaultCategories.systemDefaults(createdAtEpochMillis = 0)
        )

        assertEquals("food", suggestedEntry.suggestedCategoryId)

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun legacyRulePromotesTitleAndClearsCompatibilityFieldsWhenSaved() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BksDatabase::class.java
        ).allowMainThreadQueries().build()
        database.categorizationRuleDao().upsert(
            CategorizationRuleEntity(
                id = "legacy-rule",
                merchantContains = "",
                titleContains = " 中国电信 ",
                sourceLabel = "支付宝",
                transactionKind = "支出",
                category = "通讯",
                priority = 0,
                enabled = true,
                updatedAtEpochMillis = 1
            )
        )
        val repository = RoomPreferencesRepository(database)

        val legacyRule = repository.categorizationRules.first().single()
        assertEquals("中国电信", legacyRule.merchantContains)

        repository.updateCategorizationRules { it }
        val persisted = database.categorizationRuleDao().listRules().single()
        assertEquals("中国电信", persisted.merchantContains)
        assertEquals("", persisted.titleContains)
        assertEquals("", persisted.sourceLabel)
        assertEquals("", persisted.transactionKind)
        database.close()
    }

    @Test
    fun legacyRuleWithoutMerchantOrTitleIsNotExposed() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BksDatabase::class.java
        ).allowMainThreadQueries().build()
        database.categorizationRuleDao().upsert(
            CategorizationRuleEntity(
                id = "legacy-source-rule",
                merchantContains = "",
                titleContains = "",
                sourceLabel = "支付宝",
                transactionKind = "支出",
                category = "通讯",
                priority = 0,
                enabled = true,
                updatedAtEpochMillis = 1
            )
        )

        assertTrue(RoomPreferencesRepository(database).categorizationRules.first().isEmpty())
        database.close()
    }

    @Test
    fun clearLocalDataRestoresDefaultRulesAndResetsSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = RoomPreferencesRepository(database)

        runBlocking {
            repository.updateCategorizationRules { listOf(sampleRule()) }
            repository.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))
            repository.setAutomaticBookkeepingEnabled(true)

            repository.clearLocalData()

            assertEquals(
                DefaultCategorizationRules.rules.map { it.id }.toSet(),
                repository.categorizationRules.first().map { it.id }.toSet()
            )
            assertFalse(repository.userPreferences.first().aiSettings.aiConsentGranted)
            assertFalse(repository.userPreferences.first().automaticBookkeepingEnabled)
        }

        database.close()
    }

    private fun openDatabase(
        context: Context,
        databaseName: String
    ): BksDatabase = Room.databaseBuilder(
        context,
        BksDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().build()

    private fun openDatabaseWithDefaults(
        context: Context,
        databaseName: String
    ): BksDatabase = Room.databaseBuilder(
        context,
        BksDatabase::class.java,
        databaseName
    )
        .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
        .allowMainThreadQueries()
        .build()

    private fun sampleRule(
        id: String = "rule-1",
        merchantContains: String = "merchant",
        category: String = "food",
        priority: Int = 0,
        updatedAtEpochMillis: Long = 0
    ): CategorizationRule = CategorizationRule(
        id = id,
        merchantContains = merchantContains,
        category = category,
        priority = priority,
        updatedAtEpochMillis = updatedAtEpochMillis
    )
}
