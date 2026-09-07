package com.bks.view.review

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.FundingAccount
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.viewmodel.ledger.label
import com.bks.viewmodel.review.ReviewEntryUiModel
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.bks.model.domain.ledger.ConfidenceState
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun learnedRuleUsesOnlyMerchantAndCategory() {
        val request = PendingCategoryRuleSave(
            entry = sampleEntry(),
            edit = PendingReviewEdit(
                title = " 中国电信 ",
                amountText = "3.99",
                timeText = "2026-07-08 12:20",
                transactionKind = "支出",
                categoryId = "communication",
                category = "通讯",
                fundingAccountId = null,
                fundingAccount = "",
                note = ""
            )
        ).toRuleRequest()

        assertEquals("中国电信", request.merchantContains)
        assertEquals("通讯", request.category)
    }

    @Test
    fun sharedEditorConfirmsSelectedCategoryAndFundingAccount() {
        val nextCommand = AtomicReference<ReviewPersistenceCommand?>()
        composeRule.setContent {
            ReviewQueueScreen(
                state = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())),
                onCommand = { nextCommand.set(it) },
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("shopping", "购物", TransactionKind.EXPENSE)
                ),
                fundingAccounts = listOf(
                    fundingAccount(42, "微信零钱", PaymentSource.WECHAT),
                    fundingAccount(84, "支付宝余额", PaymentSource.ALIPAY)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-OUTFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-INFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-NEUTRAL").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-merchant").performScrollTo()
        composeRule.onNodeWithText("商户（可选）").assertIsDisplayed()
        composeRule.onNodeWithText("商户/标题（可选）").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("工作餐")
        composeRule.onNodeWithTag("manual-entry-amount").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("45.80")
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-funding-account").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("支付宝余额").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-note").performTextInput("客户会议")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) { nextCommand.get() != null }
        val confirmed = requireNotNull(nextCommand.get()) as ReviewPersistenceCommand.Confirm
        val edit = requireNotNull(confirmed.edit)
        assertEquals("工作餐", edit.title)
        assertEquals(4_580L, edit.amountMinor)
        assertEquals("shopping", edit.categoryId)
        assertEquals("购物", edit.category)
        assertEquals(84L, edit.fundingAccountId)
        assertEquals("支付宝余额", edit.fundingAccountLabel)
        assertEquals("客户会议", edit.note)
    }

    @Test
    fun invalidAmountKeepsDialogOpenAndDoesNotSilentlyUpdateRow() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("工作餐")
        composeRule.onNodeWithTag("manual-entry-amount").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("abc")
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("金额必须大于 0，且最多保留两位小数").assertIsDisplayed()
        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()

        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        composeRule.onNodeWithText("放弃修改").performClick()
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onNodeWithText("¥35.90").assertIsDisplayed()
        composeRule.onAllNodesWithText("工作餐").assertCountEquals(0)
    }

    @Test
    fun categoryCorrectionAsksBeforeSavingRule() {
        composeRule.setContent {
            TestReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("shopping", "购物", TransactionKind.EXPENSE)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("保存为分类规则？").assertIsDisplayed()
        composeRule.onNodeWithText("以后遇到相同商户时，自动建议“购物”。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
    }

    @Test
    fun savingRuleFinishesBeforeConfirmation() {
        val events = CopyOnWriteArrayList<String>()
        val ruleSaved = CompletableDeferred<Unit>()
        composeRule.setContent {
            ReviewQueueScreen(
                state = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("shopping", "购物", TransactionKind.EXPENSE)
                ),
                onCategorizationRuleRequested = {
                    events += "rule-started"
                    ruleSaved.await()
                    events += "rule-finished"
                },
                onCommand = { events += "confirmed" }
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.onNodeWithText("购物").performClick()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.waitUntil { events.contains("rule-started") }
        assertEquals(listOf("rule-started"), events)
        ruleSaved.complete(Unit)
        composeRule.waitUntil { events.contains("confirmed") }
        assertEquals(listOf("rule-started", "rule-finished", "confirmed"), events)
    }

    @Test
    fun duplicateReviewUsesCategoryPickerAndStartsWithEmptyNote() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(
                    pendingEntries = listOf(
                        sampleEntry(confidence = ConfidenceState.DUPLICATE_SUSPECT)
                    )
                ),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE),
                    category("shopping", "购物", TransactionKind.EXPENSE)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-note").assertTextEquals("备注（可选）", "")
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").assertIsDisplayed()
    }

    @Test
    fun sharedEditorListsExistingFundingAccounts() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())),
                fundingAccounts = listOf(
                    fundingAccount(42, "微信零钱", PaymentSource.WECHAT),
                    fundingAccount(84, "支付宝余额", PaymentSource.ALIPAY)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-funding-account")
            .performScrollTo()
            .assertTextContains("微信零钱")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("支付宝余额").assertIsDisplayed()
    }

    @Test
    fun sharedEditorSelectsDefaultFundingAccount() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry())),
                fundingAccounts = listOf(
                    fundingAccount(42, "微信零钱", PaymentSource.WECHAT),
                    fundingAccount(84, "支付宝余额", PaymentSource.ALIPAY)
                ),
                defaultFundingAccountSyncId = "funding-84"
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("manual-entry-funding-account")
            .performScrollTo()
            .assertTextContains("支付宝余额")
    }

    @Test
    fun editorDraftIsRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ReviewQueueScreen(
                initialState = testReviewQueueUiState(pendingEntries = listOf(sampleEntry()))
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("manual-entry-merchant").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("未保存工作餐")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-entry-merchant").assertTextContains("未保存工作餐")
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

    private fun fundingAccount(
        id: Long,
        label: String,
        source: PaymentSource
    ): FundingAccountUiModel = FundingAccountUiModel(
        id = id,
        syncId = "funding-$id",
        paymentSource = source,
        label = label
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
}
