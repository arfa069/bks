package com.bks.feature.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.feature.ledger.LedgerCategoryUiModel
import java.time.ZoneId

@Composable
internal fun TestReviewQueueScreen(
    initialState: ReviewQueueState = ReviewQueueState(),
    categories: List<LedgerCategoryUiModel> = emptyList()
) {
    var state by remember { mutableStateOf(initialState) }
    var undo by remember { mutableStateOf<TestUndo?>(null) }
    var eventSequence by remember { mutableLongStateOf(0L) }
    ReviewQueueScreen(
        state = state,
        targetLedgerBookId = DEFAULT_LEDGER_BOOK_ID,
        categories = categories,
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

private data class TestStateChange(
    val state: ReviewQueueState,
    val undo: TestUndo? = null
)

private sealed interface TestUndo {
    val entry: ReviewQueueEntry
    val message: String
    fun restore(state: ReviewQueueState): ReviewQueueState

    data class Confirmed(override val entry: ReviewQueueEntry) : TestUndo {
        override val message: String = "已确认 ${entry.title}"
        override fun restore(state: ReviewQueueState): ReviewQueueState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries,
            undoEvent = null
        )
    }

    data class Ignored(
        override val entry: ReviewQueueEntry,
        val ignoredEntry: ReviewQueueIgnoredEntry
    ) : TestUndo {
        override val message: String = "已忽略 ${entry.title}"
        override fun restore(state: ReviewQueueState): ReviewQueueState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries,
            ignoredEntries = state.ignoredEntries.filterNot { it.id == ignoredEntry.id },
            undoEvent = null
        )
    }
}

private fun ReviewQueueState.applyForScreenTest(
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
            val ignored = ReviewQueueIgnoredEntry.fromPending(
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

private fun ReviewQueueEntry.applyForScreenTest(edit: ReviewEntryEdit): ReviewQueueEntry = copy(
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
