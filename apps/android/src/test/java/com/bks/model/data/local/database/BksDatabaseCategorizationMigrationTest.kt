package com.bks.model.data.local.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BksDatabaseCategorizationMigrationTest {
    @Test
    fun migrationFromTwelveAddsOnlyNewMerchantRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-12-13-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val current = Room.databaseBuilder(context, BksDatabase::class.java, databaseName)
            .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            current.categorizationRuleDao().deleteById("default-mcdonalds")
            current.categorizationRuleDao().deleteById("default-china-telecom")
            current.categorizationRuleDao().deleteById("default-shopping")
            val editedFood = requireNotNull(
                current.categorizationRuleDao().getById("default-food")
            ).copy(category = "购物", updatedAtEpochMillis = 99)
            current.categorizationRuleDao().upsert(editedFood)
        }
        current.close()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { versionTwelve ->
            versionTwelve.execSQL("PRAGMA user_version = 12")
        }

        val migrated = Room.databaseBuilder(context, BksDatabase::class.java, databaseName)
            .addMigrations(BksDatabase.MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()
        val rules = runBlocking { migrated.categorizationRuleDao().listRules() }

        assertEquals("餐饮", rules.single { it.id == "default-mcdonalds" }.category)
        assertEquals("通讯", rules.single { it.id == "default-china-telecom" }.category)
        assertTrue(rules.none { it.id == "default-shopping" })
        assertEquals("购物", rules.single { it.id == "default-food" }.category)
        assertEquals(99, rules.single { it.id == "default-food" }.updatedAtEpochMillis)
        migrated.close()
        context.deleteDatabase(databaseName)
    }
}
