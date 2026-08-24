package com.bks.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ReviewQueueViewModel(
    private val persistence: ReviewQueuePersistence,
    private val localDataReplacementInFlight: StateFlow<Boolean>,
    private val stateLoader: suspend () -> ReviewQueueState = persistence::loadState
) : ViewModel() {
    private val persistedState = MutableStateFlow(ReviewQueueState())
    private val presentationState = MutableStateFlow(ReviewQueuePresentationState())
    private val operationMutex = Mutex()
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    private var undoMetadata: ReviewPersistenceUndo? = null
    private var undoEventSequence = 0L
    private var localDataReplacementGeneration = 0L

    val notices = noticeChannel.receiveAsFlow()
    val uiState: StateFlow<ReviewQueueState> = combine(
        persistedState,
        presentationState
    ) { persisted, presentation ->
        val projected = presentation.optimisticChange?.apply(persisted) ?: persisted
        projected.copy(
            operation = presentation.operation,
            undoEvent = presentation.undoEvent
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ReviewQueueState()
    )

    init {
        viewModelScope.launch {
            persistence.observeState().collect { persistedState.value = it }
        }
        viewModelScope.launch {
            localDataReplacementInFlight.collect { inFlight ->
                if (inFlight) resetTransientState()
            }
        }
    }

    fun execute(command: ReviewPersistenceCommand) {
        if (localDataReplacementInFlight.value) {
            noticeChannel.trySend("本机数据正在删除，请稍后重试")
            return
        }
        if (command is ReviewPersistenceCommand.Confirm && command.targetLedgerBookId == null) {
            noticeChannel.trySend("当前账本尚未加载，请稍后重试")
            return
        }
        if (!operationMutex.tryLock()) {
            noticeChannel.trySend("上一项操作正在保存，请稍后重试")
            return
        }
        if (localDataReplacementInFlight.value) {
            operationMutex.unlock()
            noticeChannel.trySend("本机数据正在删除，请稍后重试")
            return
        }
        viewModelScope.launch {
            try {
                executeLocked(command)
            } finally {
                operationMutex.unlock()
            }
        }
    }

    fun undoLastAction() {
        val command = when (val undo = undoMetadata) {
            is ReviewPersistenceUndo.Confirmed -> ReviewPersistenceCommand.UndoConfirm(undo.receipt)
            is ReviewPersistenceUndo.Ignored -> ReviewPersistenceCommand.UndoIgnore(
                ignoredEntryId = undo.ignoredEntryId,
                stableId = undo.entry.id
            )
            null -> return
        }
        execute(command)
    }

    fun dismissUndo() {
        undoMetadata = null
        presentationState.value = presentationState.value.copy(undoEvent = null)
    }

    private fun resetTransientState() {
        localDataReplacementGeneration += 1
        undoMetadata = null
        presentationState.value = ReviewQueuePresentationState()
    }

    suspend fun awaitIdleAndResetForLocalDataReplacement() {
        operationMutex.withLock {
            resetTransientState()
        }
    }

    private suspend fun executeLocked(command: ReviewPersistenceCommand) {
        val replacementGenerationAtStart = localDataReplacementGeneration
        presentationState.value = presentationState.value.copy(
            operation = ReviewQueueOperation.InFlight(command.stableId, command.operationKind()),
            optimisticChange = ReviewQueueOptimisticChange.from(command, uiState.value, undoMetadata)
        )
        val result = try {
            persistence.execute(command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            reloadAfterFailure()
            noticeChannel.send("保存待确认操作失败，请重试")
            return
        }

        if (
            localDataReplacementInFlight.value ||
            localDataReplacementGeneration != replacementGenerationAtStart
        ) {
            resetTransientState()
            runCatching { stateLoader() }
                .onSuccess { persistedState.value = it }
            return
        }

        val reloadedState = runCatching { stateLoader() }.getOrNull()
        if (
            localDataReplacementInFlight.value ||
            localDataReplacementGeneration != replacementGenerationAtStart
        ) {
            resetTransientState()
            reloadedState?.let { persistedState.value = it }
            return
        }
        reloadedState?.let { persistedState.value = it }
        when (result) {
            is ReviewPersistenceResult.Applied -> applySuccess(command, result.undo)
            ReviewPersistenceResult.NotFound -> applyReload("目标记录已不存在，待确认列表已刷新")
            ReviewPersistenceResult.AlreadyApplied -> applyReload("该操作已完成，待确认列表已刷新")
            ReviewPersistenceResult.Conflict -> applyReload("记录已发生变化，待确认列表已刷新")
        }
    }

    private fun applySuccess(
        command: ReviewPersistenceCommand,
        undo: ReviewPersistenceUndo?
    ) {
        val clearsUndo = command is ReviewPersistenceCommand.Recover ||
            command is ReviewPersistenceCommand.UndoConfirm ||
            command is ReviewPersistenceCommand.UndoIgnore
        if (undo != null) {
            undoMetadata = undo
            undoEventSequence += 1
        } else if (clearsUndo) {
            undoMetadata = null
        }
        presentationState.value = ReviewQueuePresentationState(
            undoEvent = when {
                undo != null -> ReviewQueueUndoEvent(
                    eventId = "review-undo-$undoEventSequence",
                    message = when (undo) {
                        is ReviewPersistenceUndo.Confirmed -> "已确认 ${undo.entry.title}"
                        is ReviewPersistenceUndo.Ignored -> "已忽略 ${undo.entry.title}"
                    }
                )
                clearsUndo -> null
                else -> presentationState.value.undoEvent
            }
        )
    }

    private suspend fun reloadAfterFailure() {
        runCatching { stateLoader() }
            .onSuccess { persistedState.value = it }
        presentationState.value = presentationState.value.copy(
            operation = ReviewQueueOperation.Idle,
            optimisticChange = null
        )
    }

    private suspend fun applyReload(message: String) {
        runCatching { stateLoader() }
            .onSuccess { persistedState.value = it }
        undoMetadata = null
        presentationState.value = ReviewQueuePresentationState()
        noticeChannel.send(message)
    }
}

private data class ReviewQueuePresentationState(
    val operation: ReviewQueueOperation = ReviewQueueOperation.Idle,
    val optimisticChange: ReviewQueueOptimisticChange? = null,
    val undoEvent: ReviewQueueUndoEvent? = null
)

private sealed interface ReviewQueueOptimisticChange {
    fun apply(state: ReviewQueueState): ReviewQueueState

    data class EditPending(
        val stableId: String,
        val edit: ReviewEntryEdit
    ) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueState): ReviewQueueState = state.copy(
            pendingEntries = state.pendingEntries.map { entry ->
                if (entry.id == stableId) entry.apply(edit) else entry
            }
        )
    }

    data class RemovePending(val stableId: String) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueState): ReviewQueueState = state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == stableId }
        )
    }

    data class RestorePending(
        val entry: ReviewQueueEntry,
        val ignoredEntryId: String? = null
    ) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueState): ReviewQueueState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries.filterNot { it.id == entry.id },
            ignoredEntries = ignoredEntryId?.let { id ->
                state.ignoredEntries.filterNot { it.id == id }
            } ?: state.ignoredEntries
        )
    }

    companion object {
        fun from(
            command: ReviewPersistenceCommand,
            state: ReviewQueueState,
            undo: ReviewPersistenceUndo?
        ): ReviewQueueOptimisticChange? = when (command) {
            is ReviewPersistenceCommand.Edit -> EditPending(command.stableId, command.edit)
            is ReviewPersistenceCommand.Confirm -> RemovePending(command.stableId)
            is ReviewPersistenceCommand.Ignore -> RemovePending(command.stableId)
            is ReviewPersistenceCommand.Recover -> state.ignoredEntries
                .firstOrNull { it.id == command.ignoredEntryId }
                ?.let { RestorePending(it.entry, it.id) }
            is ReviewPersistenceCommand.UndoConfirm -> (undo as? ReviewPersistenceUndo.Confirmed)
                ?.let { RestorePending(it.entry) }
            is ReviewPersistenceCommand.UndoIgnore -> (undo as? ReviewPersistenceUndo.Ignored)
                ?.let { RestorePending(it.entry, it.ignoredEntryId) }
        }
    }
}

private fun ReviewPersistenceCommand.operationKind(): ReviewQueueOperationKind = when (this) {
    is ReviewPersistenceCommand.Edit -> ReviewQueueOperationKind.EDIT
    is ReviewPersistenceCommand.Confirm -> ReviewQueueOperationKind.CONFIRM
    is ReviewPersistenceCommand.Ignore -> ReviewQueueOperationKind.IGNORE
    is ReviewPersistenceCommand.Recover -> ReviewQueueOperationKind.RECOVER
    is ReviewPersistenceCommand.UndoConfirm -> ReviewQueueOperationKind.UNDO_CONFIRM
    is ReviewPersistenceCommand.UndoIgnore -> ReviewQueueOperationKind.UNDO_IGNORE
}

private fun ReviewQueueEntry.apply(edit: ReviewEntryEdit): ReviewQueueEntry = copy(
    title = edit.title.trim().ifBlank { title },
    amountMinor = edit.amountMinor,
    transactionTimeText = formatReviewDateTime(
        edit.transactionTimeEpochMillis,
        ZoneId.systemDefault()
    ),
    kindLabel = edit.transactionKind.toReviewLabel(),
    categoryId = edit.categoryId,
    category = edit.category.trim().ifBlank { category },
    fundingAccountId = edit.fundingAccountId,
    fundingAccountLabel = edit.fundingAccountLabel.trim(),
    note = edit.note?.trim()?.ifBlank { null }
)
