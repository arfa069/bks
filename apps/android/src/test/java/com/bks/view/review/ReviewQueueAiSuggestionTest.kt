package com.bks.view.review

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.DEFAULT_CATEGORY_ID
import com.bks.view.common.components.Button
import com.bks.viewmodel.review.ReviewAiSuggestionResult
import com.bks.viewmodel.review.ReviewEntryUiModel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.data.local.ledger.TestLedgerRepository
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueAiSuggestionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signedInConsentedUserCanRequestAiCategorySuggestion() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("transport", "交通", TransactionKind.EXPENSE)
                ),
                onAiSuggest = { _, _ ->
                    ReviewAiSuggestionResult(category = "交通", message = "AI 建议：交通")
                }
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI 建议分类").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manual-entry-category")
            .performScrollTo()
            .assertTextContains("交通")
    }

    @Test
    fun emptyCategoryListUsesDisplayedSystemDefaultsForAiRequest() {
        val action = CapturingAiSuggestionAction()
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(
                    pendingEntries = listOf(sampleEntry().copy(category = ""))
                ),
                categories = emptyList(),
                fallbackCategories = DefaultCategories.systemDefaults(0).map { category ->
                    LedgerCategoryUiModel(category.id, category.name, category.kind)
                },
                onAiSuggest = action::invoke
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { action.categoryCandidates.get() != null }

        val expected = DefaultCategories.systemDefaults(0)
            .filter { category ->
                category.kind == TransactionKind.EXPENSE &&
                    category.id != com.bks.model.domain.ledger.DEFAULT_CATEGORY_ID
            }
            .map { category -> DefaultCategories.nameForId(category.id) ?: category.name }
            .distinct()
        val actual = requireNotNull(action.categoryCandidates.get())
        assertTrue(actual.isNotEmpty())
        assertEquals(expected, actual)
    }

    @Test
    fun expenseDraftSendsOnlySelectableBusinessCategoriesToAi() {
        val action = CapturingAiSuggestionAction()
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(
                    pendingEntries = listOf(sampleEntry().copy(category = ""))
                ),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("salary", "工资", TransactionKind.INCOME),
                    LedgerCategoryUiModel(
                        id = com.bks.model.domain.ledger.DEFAULT_CATEGORY_ID,
                        name = "未分类",
                        kind = null
                    )
                ),
                onAiSuggest = action::invoke
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { action.categoryCandidates.get() != null }

        assertEquals(
            listOf("餐饮"),
            requireNotNull(action.categoryCandidates.get())
        )
    }

    @Test
    fun aiRequestShowsLoadingAndDisablesDuplicateSubmissionUntilSuccess() {
        val deferred = CompletableDeferred<ReviewAiSuggestionResult>()
        val action = DeferredAiSuggestionAction(deferred)
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("transport", "交通", TransactionKind.EXPENSE)
                ),
                onAiSuggest = action::invoke
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { action.calls.get() == 1 }

        composeRule.onNodeWithTag("ai-suggest-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-suggest-button").assertIsNotEnabled()
        assertEquals(1, action.calls.get())

        deferred.complete(
            ReviewAiSuggestionResult(category = "交通", message = "AI 建议：交通")
        )
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("AI 建议：交通").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("AI 建议：交通").assertIsDisplayed()
    }

    @Test
    fun aiFailureShowsStableMessage() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(category("food", "餐饮", TransactionKind.EXPENSE)),
                onAiSuggest = { _, _ ->
                    ReviewAiSuggestionResult(message = "AI 请求过于频繁，请稍后重试")
                }
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("AI 请求过于频繁，请稍后重试").assertIsDisplayed()
    }

    private fun category(
        id: String,
        name: String,
        kind: TransactionKind
    ): LedgerCategoryUiModel = LedgerCategoryUiModel(
        id = id,
        name = name,
        kind = kind
    )

    private fun scrollToFirstPendingEntry() {
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(3)
        composeRule.waitForIdle()
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW
    ): ReviewEntryUiModel = ReviewEntryUiModel(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        categoryId = "food",
        category = "餐饮",
        fundingAccountId = 42L,
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = confidence,
        capturedAtEpochMillis = NOW,
        captureTimeText = "2026-07-08 12:21",
        note = null,
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }

    private class CapturingAiSuggestionAction {
        val categoryCandidates = AtomicReference<List<String>?>()

        suspend fun invoke(
            entry: ReviewEntryUiModel,
            categoryCandidates: List<String>
        ): ReviewAiSuggestionResult {
            check(entry.id.isNotBlank())
            this.categoryCandidates.set(categoryCandidates)
            return ReviewAiSuggestionResult(
                category = categoryCandidates.first(),
                message = "AI 建议：${categoryCandidates.first()}"
            )
        }
    }

    private class DeferredAiSuggestionAction(
        private val deferred: CompletableDeferred<ReviewAiSuggestionResult>
    ) {
        val calls = AtomicInteger(0)

        suspend fun invoke(
            entry: ReviewEntryUiModel,
            categoryCandidates: List<String>
        ): ReviewAiSuggestionResult {
            check(entry.id.isNotBlank())
            check(categoryCandidates.isNotEmpty())
            calls.incrementAndGet()
            return deferred.await()
        }
    }
}
