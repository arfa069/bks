package com.bks.model.platform.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.settings.LocalNotificationPreferences
import com.bks.model.domain.billsync.BillSyncPipeline
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.PendingEntry
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.notifications.NotificationPreferences
import com.bks.model.domain.notifications.NotificationPreferencesRepository
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewQueueRepository
import com.bks.model.domain.review.ReviewQueueSnapshot
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun startupDoesNotReplayAndBurstNotifiesOnlyCommittedStillPendingEntries() = runTest {
        shadowOf(manager).setNotificationsEnabled(true)
        val old = entry("old")
        val first = entry("first")
        val second = entry("second")
        val repository = FakeReviewRepository(listOf(old))
        val preferences = FakePreferences()
        val runtime = ReviewNotificationRuntime(backgroundScope, repository, preferences, ReviewNotificationPublisher(context))
        runtime.start()
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)

        repository.setEntries(listOf(old, first, second))
        runtime.enqueue(listOf(first))
        runtime.enqueue(listOf(second))
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        repository.setEntries(listOf(old, second)) // first was confirmed while the batch was waiting
        advanceTimeBy(751)
        runCurrent()
        assertEquals(setOf("bks.review.second", "bks.review.summary"), manager.activeNotifications.map { it.tag }.toSet())

        repository.setEntries(listOf(old))
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        repository.setEntries(listOf(old, second)) // restore/undo without a capture signal
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun permissionAndAppSwitchDoNotReplayOldCapturesWhenEnabledLater() = runTest {
        shadowOf(manager).setNotificationsEnabled(false)
        val record = entry("denied")
        val repository = FakeReviewRepository(listOf(record))
        val preferences = FakePreferences()
        val runtime = ReviewNotificationRuntime(backgroundScope, repository, preferences, ReviewNotificationPublisher(context))
        runtime.start()
        runtime.enqueue(listOf(record))
        runCurrent()
        advanceTimeBy(751)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
        shadowOf(manager).setNotificationsEnabled(true)
        preferences.setEnabled(false)
        runCurrent()
        runtime.enqueue(listOf(record))
        preferences.setEnabled(true)
        runCurrent()
        advanceTimeBy(751)
        runCurrent()
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun groupedNotificationsHideLockScreenDetailsAndSilentlyReconcile() {
        shadowOf(manager).setNotificationsEnabled(true)
        var now = 0L
        val publisher = ReviewNotificationPublisher(context) { now }
        val first = entry("one")
        val second = entry("two")
        val preferences = NotificationPreferences()
        publisher.refresh(listOf(first), setOf(first.id), preferences)
        val child = manager.activeNotifications.single { it.id == 1 }.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, child.visibility)
        assertFalse(child.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("测试商户"))
        assertTrue(child.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("建议分类：蔬菜"))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, child.groupAlertBehavior)

        now = 1_000L
        publisher.refresh(listOf(first, second), setOf(second.id), preferences)
        val summary = manager.activeNotifications.single { it.id == 0 }.notification
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        publisher.refresh(listOf(second), emptySet(), preferences.copy(hideDetailsOnLockScreen = false))
        assertEquals(2, manager.activeNotifications.size)
        assertEquals(Notification.VISIBILITY_PUBLIC, manager.activeNotifications.single { it.id == 1 }.notification.visibility)
        publisher.refresh(emptyList(), emptySet(), preferences)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun textUsesCaptureTimeCategoryFallbackAndSafeMerchantFallback() {
        val text = reviewNotificationText(entry("text").copy(
            merchantTitle = "其他应用支付", suggestedCategoryLabel = null,
            capturedAtEpochMillis = 0, transactionTimeEpochMillis = 86_400_000
        ), ZoneId.of("UTC"))
        assertEquals("商户：商户待确认｜建议分类：未分类\n识别时间：1970-01-01 00:00:00｜来源：支付宝", text)
    }

    @Test
    fun clickIntentIsExplicitImmutableAndConsumedOnce() {
        val pending = requireNotNull(ReviewNotificationIntents.contentIntent(context, "first"))
        assertTrue(pending.isImmutable)
        val intent = shadowOf(pending).savedIntent
        assertNotNull(intent.component)
        assertEquals("first", ReviewNotificationIntents.consume(context, intent))
        assertNull(ReviewNotificationIntents.consume(context, intent))
        val other = requireNotNull(ReviewNotificationIntents.contentIntent(context, "second"))
        assertNotEquals(pending, other)
        assertNull(ReviewNotificationIntents.consume(context,
            Intent("${context.packageName}.action.OPEN_PENDING_REVIEW", Uri.parse("https://example.com/review"))))
    }

    @Test
    fun preferencesSurviveRepositoryRecreation() = runBlocking {
        val repository = LocalNotificationPreferences(context)
        repository.setEnabled(false)
        repository.setHideDetailsOnLockScreen(false)
        assertEquals(NotificationPreferences(false, false), LocalNotificationPreferences(context).state.value)
    }

    private fun entry(id: String): PendingEntry = BillSyncPipeline().sync(
        "支付成功\n￥48.40\n商户：测试商户", 0, PaymentSource.ALIPAY
    ).createdEntries.single().copy(id = id, suggestedCategoryLabel = "蔬菜")

    private class FakePreferences : NotificationPreferencesRepository {
        override val state = MutableStateFlow(NotificationPreferences())
        override suspend fun setEnabled(enabled: Boolean) { state.value = state.value.copy(enabled = enabled) }
        override suspend fun setHideDetailsOnLockScreen(hide: Boolean) {
            state.value = state.value.copy(hideDetailsOnLockScreen = hide)
        }
    }

    private class FakeReviewRepository(initial: List<PendingEntry>) : ReviewQueueRepository {
        val snapshot = MutableStateFlow(ReviewQueueSnapshot(initial, nowEpochMillis = 0, todayStartEpochMillis = 0))
        fun setEntries(entries: List<PendingEntry>) { snapshot.value = snapshot.value.copy(pendingEntries = entries) }
        override fun observeSnapshot() = snapshot
        override suspend fun loadSnapshot() = snapshot.value
        override suspend fun ensureSystemCategories() = Unit
        override suspend fun loadCategories(): List<Category> = emptyList()
        override suspend fun insertCapturedEntries(entries: List<PendingEntry>) = entries
        override suspend fun applyCategorizationRulesToUnclassified(rules: List<CategorizationRule>): Int = 0
        override suspend fun execute(command: ReviewPersistenceCommand): ReviewPersistenceResult = ReviewPersistenceResult.NotFound
    }
}
