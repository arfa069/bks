package com.bks.viewmodel.review

import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.categorization.suggestCategory
import com.bks.model.domain.review.ReviewEntryEdit
import com.bks.model.domain.review.ReviewPersistenceCommand
import com.bks.model.domain.review.ReviewPersistenceResult
import com.bks.model.domain.review.ReviewPersistenceUndo
import com.bks.model.domain.review.ReviewQueueRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.model.application.account.AccountCloudSettingsState
import com.bks.model.domain.account.AccountSession
import com.bks.model.application.account.AccountSessionCoordinatorState
import com.bks.model.application.account.effectiveAiCategorizationSettings
import com.bks.model.domain.categorization.AiCategorizationClient
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.model.domain.categorization.AiCategorizationResult
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.AiCategorizationSkipReason
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReviewAiDependencies(
    val client: AiCategorizationClient? = null,
    val accountSessionState: StateFlow<AccountSessionCoordinatorState>? = null,
    val cloudSettingsState: StateFlow<AccountCloudSettingsState>? = null
)

internal class ReviewQueueViewModel(
    private val persistence: ReviewQueueRepository,
    private val localDataReplacementInFlight: StateFlow<Boolean>,
    private val aiDependencies: ReviewAiDependencies = ReviewAiDependencies(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val stateLoader: suspend () -> ReviewQueueUiState = {
        persistence.loadSnapshot().toUiState(zoneId)
    }
) : ViewModel() {
    private val initialState = emptyReviewQueueUiState(clock(), zoneId)
    private val persistedState = MutableStateFlow(initialState)
    private val presentationState = MutableStateFlow(ReviewQueuePresentationState())
    private val operationMutex = Mutex()
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    private var undoMetadata: ReviewPersistenceUndo? = null
    private var undoEventSequence = 0L
    private var localDataReplacementGeneration = 0L

    val notices = noticeChannel.receiveAsFlow()
    val uiState: StateFlow<ReviewQueueUiState> = combine(
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
        initialValue = initialState
    )

    init {
        viewModelScope.launch {
            persistence.observeSnapshot().map { it.toUiState(zoneId) }
                .collect { persistedState.value = it }
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

    suspend fun suggestCategory(
        entry: ReviewEntryUiModel,
        categoryCandidates: List<String>
    ): ReviewAiSuggestionResult {
        val client = aiDependencies.client
            ?: return ReviewAiSuggestionResult(message = "登录后才能使用云端 AI 分类")
        val accountState = aiDependencies.accountSessionState?.value
        val signedIn = accountState?.session as? AccountSession.SignedIn
        val cloudState = aiDependencies.cloudSettingsState?.value
        val settings = if (accountState != null && cloudState != null) {
            effectiveAiCategorizationSettings(accountState, cloudState)
        } else {
            AiCategorizationSettings()
        }
        return client.suggestCategory(
            token = signedIn?.token,
            payload = entry.toAiCategorizationPayload(
                categoryCandidates = categoryCandidates,
                enhancedContextGranted = settings.enhancedContextGranted
            ),
            settings = settings
        ).toReviewAiSuggestionResult()
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
                        is ReviewPersistenceUndo.Confirmed -> "已确认 ${undo.entry.merchantTitle}"
                        is ReviewPersistenceUndo.Ignored -> "已忽略 ${undo.entry.merchantTitle}"
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

private fun AiCategorizationResult.toReviewAiSuggestionResult(): ReviewAiSuggestionResult {
    val category = suggestion?.category
    return ReviewAiSuggestionResult(
        category = category,
        message = category?.let { "AI 建议：$it" }
            ?: skipReason?.toReviewMessage()
            ?: failureReason?.toReviewMessage()
            ?: "暂时没有 AI 分类建议"
    )
}

private fun AiCategorizationSkipReason.toReviewMessage(): String = when (this) {
    AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT ->
        "登录后才能使用云端 AI 分类"
    AiCategorizationSkipReason.REQUIRES_AI_CONSENT ->
        "开启云端 AI 后才能获取分类建议"
}

private fun AiCategorizationFailureReason.toReviewMessage(): String = when (this) {
    AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED -> "云端 AI 后端地址尚未配置"
    AiCategorizationFailureReason.INVALID_SESSION -> "登录状态已失效，请重新登录"
    AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING -> "账号注销冷静期内，云端 AI 已暂停"
    AiCategorizationFailureReason.AI_CONSENT_REQUIRED -> "请先开启云端 AI 分类"
    AiCategorizationFailureReason.ENHANCED_CONTEXT_NOT_AUTHORIZED ->
        "增强上下文授权已失效，请重新确认设置"
    AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED ->
        "暂无可用分类，请先创建或启用分类"
    AiCategorizationFailureReason.RATE_LIMITED -> "AI 请求过于频繁，请稍后重试"
    AiCategorizationFailureReason.SERVICE_UNAVAILABLE -> "云端 AI 暂时不可用，请稍后重试"
    AiCategorizationFailureReason.NETWORK_FAILURE -> "网络连接失败，请检查网络后重试"
    AiCategorizationFailureReason.INVALID_RESPONSE -> "AI 响应无法解析，请稍后重试"
}

private data class ReviewQueuePresentationState(
    val operation: ReviewQueueOperation = ReviewQueueOperation.Idle,
    val optimisticChange: ReviewQueueOptimisticChange? = null,
    val undoEvent: ReviewQueueUndoEvent? = null
)

private sealed interface ReviewQueueOptimisticChange {
    fun apply(state: ReviewQueueUiState): ReviewQueueUiState

    data class EditPending(
        val stableId: String,
        val edit: ReviewEntryEdit
    ) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueUiState): ReviewQueueUiState = state.copy(
            pendingEntries = state.pendingEntries.map { entry ->
                if (entry.id == stableId) entry.apply(edit) else entry
            }
        )
    }

    data class RemovePending(val stableId: String) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueUiState): ReviewQueueUiState = state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == stableId }
        )
    }

    data class RestorePending(
        val entry: ReviewEntryUiModel,
        val ignoredEntryId: String? = null
    ) : ReviewQueueOptimisticChange {
        override fun apply(state: ReviewQueueUiState): ReviewQueueUiState = state.copy(
            pendingEntries = listOf(entry) + state.pendingEntries.filterNot { it.id == entry.id },
            ignoredEntries = ignoredEntryId?.let { id ->
                state.ignoredEntries.filterNot { it.id == id }
            } ?: state.ignoredEntries
        )
    }

    companion object {
        fun from(
            command: ReviewPersistenceCommand,
            state: ReviewQueueUiState,
            undo: ReviewPersistenceUndo?
        ): ReviewQueueOptimisticChange? = when (command) {
            is ReviewPersistenceCommand.Edit -> EditPending(command.stableId, command.edit)
            is ReviewPersistenceCommand.Confirm -> RemovePending(command.stableId)
            is ReviewPersistenceCommand.Ignore -> RemovePending(command.stableId)
            is ReviewPersistenceCommand.Recover -> state.ignoredEntries
                .firstOrNull { it.id == command.ignoredEntryId }
                ?.let { RestorePending(it.entry, it.id) }
            is ReviewPersistenceCommand.UndoConfirm -> (undo as? ReviewPersistenceUndo.Confirmed)
                ?.let { RestorePending(it.entry.toUiModel(ZoneId.systemDefault())) }
            is ReviewPersistenceCommand.UndoIgnore -> (undo as? ReviewPersistenceUndo.Ignored)
                ?.let { RestorePending(it.entry.toUiModel(ZoneId.systemDefault()), it.ignoredEntryId) }
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

private fun ReviewEntryUiModel.apply(edit: ReviewEntryEdit): ReviewEntryUiModel = copy(
    title = edit.title.trim().ifBlank { title },
    amountMinor = edit.amountMinor,
    transactionTimeText = formatReviewDateTime(
        edit.transactionTimeEpochMillis,
        ZoneId.systemDefault()
    ),
    kindLabel = edit.transactionKind.reviewLabel(),
    categoryId = edit.categoryId,
    category = edit.category.trim().ifBlank { category },
    fundingAccountId = edit.fundingAccountId,
    fundingAccountLabel = edit.fundingAccountLabel.trim(),
    note = edit.note?.trim()?.ifBlank { null }
)
