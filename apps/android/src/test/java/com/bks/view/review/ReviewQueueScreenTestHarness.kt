package com.bks.view.review

import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.IgnoredEntry
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.review.ReviewEntryEdit
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.viewmodel.review.formatReviewDateTime
import com.bks.viewmodel.review.ReviewEntryUiModel
import com.bks.viewmodel.review.IgnoredReviewEntryUiModel
import com.bks.viewmodel.review.ReviewQueueUiState
import com.bks.viewmodel.review.ReviewQueueUndoEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.model.data.local.database.DEFAULT_LEDGER_BOOK_ID
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import java.time.ZoneId

@Composable
internal fun TestReviewQueueScreen(
    initialState: ReviewQueueUiState = testReviewQueueUiState(),
    categories: List<LedgerCategoryUiModel> = emptyList()
) {
    var state by remember { mutableStateOf(initialState) }
    var undo by remember { mutableStateOf<TestUndo?>(null) }
    var eventSequence by remember { mutableLongStateOf(0L) }
    ReviewQueueScreen(
        state = state,
        targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID,
        categories = categories,
        fallbackCategories = testDefaultCategories(),
        onCommand = { command ->
            state.applyForScreenTest(command)?.let { change ->
                state = change.state
                undo = change.undo
                if (change.undo != null) {
                    eventSequence += 1
                    state = state.copy(
                        undoEvent = ReviewQueueUndoEvent(
                            eventId = "test-review-undo-$eventSequence",
                            message = change.undo.message
                        )
                    )
                }
            }
        },
        onUndo = {
            undo?.let { activeUndo -> state = activeUndo.restore(state) }
            undo = null
        },
        onDismissUndo = {
            undo = null
            state = state.copy(undoEvent = null)
        }
    )
}

internal fun testReviewQueueUiState(
    pendingEntries: List<ReviewEntryUiModel> = emptyList(),
    todayStartEpochMillis: Long = TEST_TODAY_START_EPOCH_MILLIS
): ReviewQueueUiState = ReviewQueueUiState(
    pendingEntries = pendingEntries,
    nowEpochMillis = TEST_NOW_EPOCH_MILLIS,
    todayStartEpochMillis = todayStartEpochMillis
)

private const val TEST_NOW_EPOCH_MILLIS = 1_783_468_800_000L
private const val TEST_TODAY_START_EPOCH_MILLIS = 1_783_425_600_000L

private fun testDefaultCategories(): List<LedgerCategoryUiModel> =
    DefaultCategories.systemDefaults(0).map { category ->
        LedgerCategoryUiModel(
            id = category.id,
            name = category.name,
            kind = category.kind
        )
    }

private data class TestStateChange(
    val state: ReviewQueueUiState,
    val undo: TestUndo? = null
)

private sealed interface TestUndo {
    val entry: ReviewEntryUiModel
    val message: String
    fun restore(state: ReviewQueueUiState): ReviewQueueUiState

    data class Confirmed(override val entry: ReviewEntryUiModel) : TestUndo {
        override val message: String = "已确认 ${entry.title}"
        override fun restore(state: ReviewQueueUiState): ReviewQueueUiState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries,
            undoEvent = null
        )
    }

    data class Ignored(
        override val entry: ReviewEntryUiModel,
        val ignoredEntry: IgnoredReviewEntryUiModel
    ) : TestUndo {
        override val message: String = "已忽略 ${entry.title}"
        override fun restore(state: ReviewQueueUiState): ReviewQueueUiState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries,
            ignoredEntries = state.ignoredEntries.filterNot { it.id == ignoredEntry.id },
            undoEvent = null
        )
    }
}

private fun ReviewQueueUiState.applyForScreenTest(
    command: ReviewPersistenceCommand
): TestStateChange? = when (command) {
    is ReviewPersistenceCommand.Confirm -> pendingEntries
        .firstOrNull { it.id == command.stableId }
        ?.let { current ->
            val confirmed = command.edit?.let(current::applyForScreenTest) ?: current
            TestStateChange(
                state = copy(pendingEntries = pendingEntries.filterNot { it.id == current.id }),
                undo = TestUndo.Confirmed(confirmed)
            )
        }

    is ReviewPersistenceCommand.Ignore -> pendingEntries
        .firstOrNull { it.id == command.stableId }
        ?.let { current ->
            val ignored = IgnoredReviewEntryUiModel.fromPending(
                entry = current,
                ignoredAtEpochMillis = nowEpochMillis
            )
            TestStateChange(
                state = copy(
                    pendingEntries = pendingEntries.filterNot { it.id == current.id },
                    ignoredEntries = ignoredEntries + ignored
                ),
                undo = TestUndo.Ignored(current, ignored)
            )
        }

    is ReviewPersistenceCommand.Recover -> ignoredEntries
        .firstOrNull { it.id == command.ignoredEntryId }
        ?.let { ignored ->
            TestStateChange(
                copy(
                    pendingEntries = listOf(ignored.entry) + pendingEntries,
                    ignoredEntries = ignoredEntries.filterNot { it.id == ignored.id },
                    undoEvent = null
                )
            )
        }

    is ReviewPersistenceCommand.Edit -> pendingEntries
        .firstOrNull { it.id == command.stableId }
        ?.let { current ->
            TestStateChange(
                copy(
                    pendingEntries = pendingEntries.map { entry ->
                        if (entry.id == current.id) current.applyForScreenTest(command.edit) else entry
                    }
                )
            )
        }

    is ReviewPersistenceCommand.UndoConfirm,
    is ReviewPersistenceCommand.UndoIgnore -> null
}

private fun ReviewEntryUiModel.applyForScreenTest(edit: ReviewEntryEdit): ReviewEntryUiModel = copy(
    title = edit.title.trim().ifBlank { title },
    amountMinor = edit.amountMinor,
    transactionTimeText = formatReviewDateTime(edit.transactionTimeEpochMillis, ZoneId.systemDefault()),
    kindLabel = edit.transactionKind.toReviewLabel(),
    categoryId = edit.categoryId,
    category = edit.category,
    fundingAccountId = edit.fundingAccountId,
    fundingAccountLabel = edit.fundingAccountLabel,
    note = edit.note
)
