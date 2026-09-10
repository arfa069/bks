package com.bks.view.review

import com.bks.viewmodel.review.ReviewAiSuggestionResult

import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.viewmodel.review.ReviewEntryUiModel
import com.bks.viewmodel.review.ReviewQueueUiState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import com.bks.view.common.components.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import com.bks.view.common.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bks.model.domain.ledger.DEFAULT_LEDGER_ID
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import kotlinx.coroutines.launch
@Composable
@Suppress("LongParameterList")
internal fun ReviewQueueScreen(
    modifier: Modifier = Modifier,
    initialState: ReviewQueueUiState,
    targetLedgerBookId: String? = DEFAULT_LEDGER_ID,
    targetLedgerName: String = "默认账本",
    categories: List<LedgerCategoryUiModel> = emptyList(),
    fallbackCategories: List<LedgerCategoryUiModel> = emptyList(),
    fundingAccounts: List<FundingAccountUiModel> = emptyList(),
    defaultFundingAccountSyncId: String? = null,
    onCategorizationRuleRequested: suspend (ReviewCategoryRuleRequest) -> Unit = {},
    onAiSuggest: suspend (ReviewEntryUiModel, List<String>) -> ReviewAiSuggestionResult = { _, _ ->
        ReviewAiSuggestionResult(message = "登录后才能使用云端 AI 分类")
    },
    onCommand: (ReviewPersistenceCommand) -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissUndo: () -> Unit = {},
    onNavigateHome: () -> Unit = {}
) {
    ReviewQueueScreen(
        state = initialState,
        targetLedgerBookId = targetLedgerBookId,
        targetLedgerName = targetLedgerName,
        categories = categories,
        fallbackCategories = fallbackCategories,
        fundingAccounts = fundingAccounts,
        defaultFundingAccountSyncId = defaultFundingAccountSyncId,
        modifier = modifier,
        onCategorizationRuleRequested = onCategorizationRuleRequested,
        onAiSuggest = onAiSuggest,
        onCommand = onCommand,
        onUndo = onUndo,
        onDismissUndo = onDismissUndo,
        onNavigateHome = onNavigateHome
    )
}

@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun ReviewQueueScreen(
    state: ReviewQueueUiState,
    targetLedgerBookId: String? = DEFAULT_LEDGER_ID,
    targetLedgerName: String = "默认账本",
    categories: List<LedgerCategoryUiModel> = emptyList(),
    fallbackCategories: List<LedgerCategoryUiModel> = emptyList(),
    fundingAccounts: List<FundingAccountUiModel> = emptyList(),
    defaultFundingAccountSyncId: String? = null,
    modifier: Modifier = Modifier,
    onCategorizationRuleRequested: suspend (ReviewCategoryRuleRequest) -> Unit = {},
    onAiSuggest: suspend (ReviewEntryUiModel, List<String>) -> ReviewAiSuggestionResult = { _, _ ->
        ReviewAiSuggestionResult(message = "登录后才能使用云端 AI 分类")
    },
    onCommand: (ReviewPersistenceCommand) -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissUndo: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    requestedEntryId: String? = null,
    onRequestedEntryHandled: () -> Unit = {},
    backfillContent: @Composable (SnackbarHostState) -> Unit = {}
) {
    var editingEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingEntry = remember(state.pendingEntries, editingEntryId) {
        state.pendingEntries.firstOrNull { it.id == editingEntryId }
    }
    var pendingRuleSave by remember { mutableStateOf<PendingCategoryRuleSave?>(null) }
    var showIgnoredList by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(requestedEntryId, state.isLoaded, state.pendingEntries) {
        val requested = requestedEntryId ?: return@LaunchedEffect
        if (!state.isLoaded) return@LaunchedEffect
        val exists = state.pendingEntries.any { it.id == requested }
        if (exists) {
            editingEntryId = requested
            pendingRuleSave = null
        }
        onRequestedEntryHandled()
        if (!exists) coroutineScope.launch { snackbarHostState.showSnackbar("该记录已处理或不存在") }
    }

    fun confirmEdit(entry: ReviewEntryUiModel, edit: PendingReviewEdit) {
        onCommand(
            ReviewPersistenceCommand.Confirm(
                stableId = entry.id,
                targetLedgerBookId = targetLedgerBookId,
                edit = edit.toEntryEdit()
            )
        )
        pendingRuleSave = null
        editingEntryId = null
    }

    fun applyEdit(pending: PendingCategoryRuleSave) {
        confirmEdit(pending.entry, pending.edit)
    }

    val undoEvent = state.undoEvent
    LaunchedEffect(undoEvent?.eventId) {
        val activeUndoEvent = undoEvent ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = activeUndoEvent.message,
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismissUndo()
    }

    val activeEdit = editingEntry
    if (activeEdit == null) {
        Scaffold(
            modifier = modifier,
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            ReviewQueueListContent(
                state = state,
                backfillContent = { backfillContent(snackbarHostState) },
                targetLedgerName = targetLedgerName,
                actions = ReviewQueueListActions(
                    onConfirm = { entryId ->
                        onCommand(
                            ReviewPersistenceCommand.Confirm(
                                stableId = entryId,
                                targetLedgerBookId = targetLedgerBookId
                            )
                        )
                    },
                    onIgnore = { entryId ->
                        onCommand(ReviewPersistenceCommand.Ignore(entryId))
                    },
                    onEdit = { editingEntryId = it.id },
                    onShowIgnoredList = { showIgnoredList = true },
                    onNavigateHome = onNavigateHome
                ),
                modifier = Modifier.padding(innerPadding)
            )
        }
    } else {
        val availableCategories = remember(categories) {
            categories.ifEmpty {
                fallbackCategories
            }
        }
        ReviewPendingEntryEditor(
            entry = activeEdit,
            availableCategories = availableCategories,
            fundingAccounts = fundingAccounts,
            defaultFundingAccountSyncId = defaultFundingAccountSyncId,
            config = ReviewPendingEntryEditorConfig(
                modifier = modifier,
                snackbarHostState = snackbarHostState,
                onExit = { editingEntryId = null },
                onAiSuggest = onAiSuggest,
                onConfirm = { edit ->
                    if (activeEdit.hasCategoryCorrection(edit.category)) {
                        pendingRuleSave = PendingCategoryRuleSave(activeEdit, edit)
                    } else {
                        confirmEdit(activeEdit, edit)
                    }
                }
            )
        )
    }

    pendingRuleSave?.let { pending ->
        AlertDialog(
            onDismissRequest = { applyEdit(pending) },
            title = { Text("保存为分类规则？") },
            text = {
                Text("以后遇到相同商户时，自动建议“${pending.edit.category.trim()}”。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            onCategorizationRuleRequested(pending.toRuleRequest())
                            applyEdit(pending)
                        }
                    }
                ) {
                    Text("保存规则")
                }
            },
            dismissButton = {
                TextButton(onClick = { applyEdit(pending) }) {
                    Text("这次不保存")
                }
            }
        )
    }

    if (showIgnoredList) {
        IgnoredEntriesDialog(
            ignoredEntries = state.recoverableIgnoredEntries,
            onDismiss = { showIgnoredList = false },
            onRecover = {
                state.ignoredEntries.firstOrNull { ignored -> ignored.id == it }?.let { ignored ->
                    onCommand(
                        ReviewPersistenceCommand.Recover(
                            ignoredEntryId = ignored.id,
                            stableId = ignored.originalPendingId
                        )
                    )
                }
                showIgnoredList = false
            }
        )
    }

}
