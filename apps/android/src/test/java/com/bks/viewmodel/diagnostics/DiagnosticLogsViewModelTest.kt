package com.bks.viewmodel.diagnostics

import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticLogRepository
import com.bks.model.domain.diagnostics.DiagnosticLogStats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticLogsViewModelTest {
    @Test
    fun initialRefreshAndRepositoryFlowsPopulateUiState() = runTest {
        val repository = FakeDiagnosticLogRepository()
        withViewModel(repository) { viewModel, _ ->
            runCurrent()
            assertEquals(1, repository.refreshCalls)

            repository.mutableEnabled.value = true
            repository.mutableEvents.value = listOf(testEvent())
            repository.mutableStats.value = DiagnosticLogStats(eventCount = 1, segmentCount = 1)
            runCurrent()

            assertTrue(viewModel.uiState.value.enabled)
            assertEquals(1, viewModel.uiState.value.events.size)
            assertEquals(1, viewModel.uiState.value.stats.eventCount)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, repository.refreshCalls)
            assertFalse(viewModel.uiState.value.refreshing)
        }
    }

    @Test
    fun refreshAndClearFailuresReturnIdleWithNonSensitiveMessages() = runTest {
        val repository = FakeDiagnosticLogRepository().apply {
            failRefresh = true
            failClear = true
        }
        withViewModel(repository) { viewModel, _ ->
            advanceUntilIdle()
            assertEquals("刷新失败，请稍后重试。", viewModel.uiState.value.statusMessage)
            assertFalse(viewModel.uiState.value.refreshing)

            viewModel.clear()
            advanceUntilIdle()
            assertEquals("清空失败，请稍后重试。", viewModel.uiState.value.statusMessage)
            assertFalse(viewModel.uiState.value.clearing)
        }
    }

    @Test
    fun successfulRefreshClearsOnlyThePreviousRefreshFailure() = runTest {
        val repository = FakeDiagnosticLogRepository().apply { failRefresh = true }
        withViewModel(repository) { viewModel, _ ->
            advanceUntilIdle()
            assertEquals("刷新失败，请稍后重试。", viewModel.uiState.value.statusMessage)

            repository.failRefresh = false
            viewModel.refresh()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.statusMessage)
            assertFalse(viewModel.uiState.value.refreshing)
            assertEquals(2, repository.refreshCalls)
        }
    }

    @Test
    fun clearDelegatesToRepositoryAndPublishesItsFlowProjection() = runTest {
        val repository = FakeDiagnosticLogRepository().apply {
            mutableEvents.value = listOf(testEvent())
            mutableStats.value = DiagnosticLogStats(eventCount = 1)
        }
        withViewModel(repository) { viewModel, _ ->
            advanceUntilIdle()
            viewModel.clear()
            advanceUntilIdle()

            assertEquals(1, repository.clearCalls)
            assertTrue(viewModel.uiState.value.events.isEmpty())
            assertEquals(DiagnosticLogStats(), viewModel.uiState.value.stats)
            assertEquals("诊断日志已清空。", viewModel.uiState.value.statusMessage)
        }
    }

    @Test
    fun exportHandsPlaintextToWriterOnceAndPublishesCompletion() = runTest {
        val repository = FakeDiagnosticLogRepository()
        withViewModel(repository) { viewModel, _ ->
            val pendingRequest = async { viewModel.writeRequests.first() }
            viewModel.export()
            runCurrent()
            val request = pendingRequest.await()

            assertEquals("plaintext-sanitized-export", request.plainText)
            assertTrue(viewModel.beginWrite(request.requestId))
            assertFalse(viewModel.uiState.value.toString().contains("plaintext-sanitized-export"))

            viewModel.finishWrite(request.requestId, "diagnostics.log")
            assertFalse(viewModel.uiState.value.exporting)
            assertEquals("导出完成", viewModel.uiState.value.exportResult?.title)
            assertTrue(viewModel.uiState.value.exportResult?.message.orEmpty().contains("diagnostics.log"))
        }
    }

    @Test
    fun cancelStopsExportAndRejectsLateWrite() = runTest {
        val repository = FakeDiagnosticLogRepository().apply { blockExport = true }
        withViewModel(repository) { viewModel, _ ->
            viewModel.export()
            runCurrent()
            repository.exportStarted.await()

            viewModel.cancelExport()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.exporting)
            assertEquals("导出已取消", viewModel.uiState.value.exportResult?.title)
        }

        val completedRepository = FakeDiagnosticLogRepository()
        withViewModel(completedRepository) { viewModel, _ ->
            val pendingRequest = async { viewModel.writeRequests.first() }
            viewModel.export()
            runCurrent()
            val request = pendingRequest.await()
            viewModel.cancelExport()

            assertFalse(viewModel.beginWrite(request.requestId))
            viewModel.finishWrite(request.requestId, "must-not-exist.log")
            assertEquals("导出已取消", viewModel.uiState.value.exportResult?.title)
        }
    }

    @Test
    fun exportAndWriterFailuresRemainStableUntilPreciselyDismissed() = runTest {
        val repository = FakeDiagnosticLogRepository().apply { failExport = true }
        withViewModel(repository) { viewModel, _ ->
            viewModel.export()
            advanceUntilIdle()

            val generationFailure = requireNotNull(viewModel.uiState.value.exportResult)
            assertEquals("导出失败", generationFailure.title)
            viewModel.dismissExportResult(generationFailure.id + 1)
            assertEquals(generationFailure, viewModel.uiState.value.exportResult)
            viewModel.dismissExportResult(generationFailure.id)
            assertNull(viewModel.uiState.value.exportResult)
        }

        val completedRepository = FakeDiagnosticLogRepository()
        withViewModel(completedRepository) { viewModel, _ ->
            val pendingRequest = async { viewModel.writeRequests.first() }
            viewModel.export()
            runCurrent()
            val request = pendingRequest.await()
            viewModel.finishWrite(request.requestId, displayName = null)

            assertFalse(viewModel.uiState.value.exporting)
            assertEquals("导出失败", viewModel.uiState.value.exportResult?.title)
        }
    }

    @Test
    fun clearingOwnerCancelsExportAndDropsOperationState() = runTest {
        val repository = FakeDiagnosticLogRepository().apply { blockExport = true }
        withViewModel(repository) { viewModel, store ->
            viewModel.export()
            runCurrent()
            repository.exportStarted.await()

            store.clear()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.exporting)
            assertNull(viewModel.uiState.value.exportResult)
        }
    }

    private suspend fun TestScope.withViewModel(
        repository: DiagnosticLogRepository,
        block: suspend (DiagnosticLogsViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiagnosticLogsViewModel(repository) as T
            }
            block(ViewModelProvider(store, factory)[DiagnosticLogsViewModel::class.java], store)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun testEvent(): DiagnosticEvent = DiagnosticEvent(
        DiagnosticEventMetadata(
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.Application,
            event = "test_event"
        )
    )
}

private class FakeDiagnosticLogRepository : DiagnosticLogRepository {
    val mutableEnabled = MutableStateFlow(false)
    val mutableEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val mutableStats = MutableStateFlow(DiagnosticLogStats())
    val exportStarted = CompletableDeferred<Unit>()
    var refreshCalls = 0
    var clearCalls = 0
    var failRefresh = false
    var failClear = false
    var failExport = false
    var blockExport = false

    override val enabled: StateFlow<Boolean> = mutableEnabled
    override val events: StateFlow<List<DiagnosticEvent>> = mutableEvents
    override val stats: StateFlow<DiagnosticLogStats> = mutableStats

    override fun record(event: DiagnosticEvent) = Unit

    override fun setEnabled(enabled: Boolean, userConfirmed: Boolean): Boolean {
        mutableEnabled.value = enabled
        return true
    }

    override suspend fun refresh(limit: Int) {
        refreshCalls += 1
        if (failRefresh) error("refresh failed")
    }

    override suspend fun clear(keepEnabledPreference: Boolean) {
        clearCalls += 1
        if (failClear) error("clear failed")
        mutableEvents.value = emptyList()
        mutableStats.value = DiagnosticLogStats()
    }

    override suspend fun exportPlainText(): String {
        exportStarted.complete(Unit)
        if (blockExport) awaitCancellation()
        if (failExport) error("export failed")
        return "plaintext-sanitized-export"
    }
}
