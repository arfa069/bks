package com.bks.view.review

import com.bks.viewmodel.review.formatReviewDateTime
import com.bks.viewmodel.review.parseReviewDateTime
import com.bks.viewmodel.review.toTransactionKind
import com.bks.viewmodel.review.ReviewAiSuggestionResult
import com.bks.model.domain.review.ReviewEntryEdit
import com.bks.viewmodel.ledger.label
import com.bks.viewmodel.review.amountMinorToText
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.review.parseReviewAmountMinor
import com.bks.viewmodel.review.ReviewQueueEntry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.FlowDirection
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.view.common.entry.LedgerEntryFormConfig
import com.bks.viewmodel.ledger.LedgerEntryFormState
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import com.bks.view.common.entry.SharedLedgerEntryForm
internal data class PendingCategoryRuleSave(
    val entry: ReviewQueueEntry,
    val edit: PendingReviewEdit
) {
    fun toRuleRequest(): ReviewCategoryRuleRequest = ReviewCategoryRuleRequest(
        id = "rule-${entry.id}-${entry.capturedAtEpochMillis}",
        merchantContains = edit.title.trim(),
        sourceLabel = entry.sourceLabel,
        transactionKind = edit.transactionKind.trim(),
        category = edit.category.trim(),
        updatedAtEpochMillis = entry.capturedAtEpochMillis
    )
}

internal data class ReviewCategoryRuleRequest(
    val id: String,
    val merchantContains: String,
    val sourceLabel: String,
    val transactionKind: String,
    val category: String,
    val updatedAtEpochMillis: Long
)


internal data class PendingReviewEdit(
    val title: String,
    val amountText: String,
    val timeText: String,
    val transactionKind: String,
    val categoryId: String?,
    val category: String,
    val fundingAccountId: Long?,
    val fundingAccount: String,
    val note: String
) {
    fun toEntryEdit(): ReviewEntryEdit = ReviewEntryEdit(
        title = title,
        amountMinor = requireNotNull(parseReviewAmountMinor(amountText)) {
            "Review amount is invalid"
        },
        transactionTimeEpochMillis = requireNotNull(
            parseReviewDateTime(timeText, java.time.ZoneId.systemDefault())
        ) { "Review transaction time is invalid" },
        transactionKind = transactionKind.toTransactionKind(),
        categoryId = categoryId,
        category = category,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = fundingAccount,
        note = note.trim().ifBlank { null }
    )

    companion object {
        fun from(
            input: LedgerEntryFormSubmission,
            categories: List<LedgerCategoryUiModel>,
            fundingAccounts: List<FundingAccountUiModel>
        ): PendingReviewEdit = PendingReviewEdit(
            title = input.merchantTitle,
            amountText = amountMinorToText(input.amountMinor),
            timeText = formatReviewDateTime(
                input.transactionTimeEpochMillis,
                java.time.ZoneId.systemDefault()
            ),
            transactionKind = input.transactionKind.toReviewLabel(),
            categoryId = input.categoryId,
            category = input.categoryId.toReviewCategoryName(categories),
            fundingAccountId = input.fundingAccountId,
            fundingAccount = fundingAccounts
                .firstOrNull { it.id == input.fundingAccountId }
                ?.label
                .orEmpty(),
            note = input.note.orEmpty()
        )
    }
}

internal data class ReviewPendingEntryEditorConfig(
    val modifier: Modifier,
    val snackbarHostState: SnackbarHostState,
    val onExit: () -> Unit,
    val onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> ReviewAiSuggestionResult,
    val onConfirm: (PendingReviewEdit) -> Unit
)

internal data class ReviewEditorToolActions(
    val onDraftChange: (LedgerEntryFormState) -> Unit,
    val onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> ReviewAiSuggestionResult
)

internal fun ReviewQueueEntry.hasCategoryCorrection(category: String): Boolean {
    return category.trim().isNotBlank() && category.trim() != this.category
}

internal fun LedgerCategoryUiModel.reviewDisplayName(): String =
    DefaultCategories.nameForId(id) ?: name

@Composable
internal fun ReviewPendingEntryEditor(
    entry: ReviewQueueEntry,
    availableCategories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    defaultFundingAccountSyncId: String?,
    config: ReviewPendingEntryEditorConfig
) {
    val initial = remember(entry, availableCategories, fundingAccounts, defaultFundingAccountSyncId) {
        val entryState = entry.toLedgerEntryFormState(availableCategories, fundingAccounts)
        entryState.copy(
            fundingAccountId = fundingAccounts
                .firstOrNull { it.syncId == defaultFundingAccountSyncId }
                ?.id
                ?: entryState.fundingAccountId
        )
    }
    Box(modifier = config.modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "编辑待确认账目",
            initial = initial,
            categories = availableCategories,
            fundingAccounts = fundingAccounts,
            config = LedgerEntryFormConfig(
                flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
                allowCreateFundingAccount = false,
                saveLabel = "确认入账",
                onExit = config.onExit,
                onSave = { input ->
                    config.onConfirm(PendingReviewEdit.from(input, availableCategories, fundingAccounts))
                },
                onDelete = null,
                snackbarHostState = config.snackbarHostState,
                showDefaultFundingAccountHint = true,
                leadingContent = { draft, onDraftChange ->
                    ReviewEditorTools(
                        entry = entry,
                        draft = draft,
                        categories = availableCategories,
                        fundingAccounts = fundingAccounts,
                        actions = ReviewEditorToolActions(
                            onDraftChange = onDraftChange,
                            onAiSuggest = config.onAiSuggest
                        )
                    )
                }
            )
        )
        SnackbarHost(
            hostState = config.snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}
