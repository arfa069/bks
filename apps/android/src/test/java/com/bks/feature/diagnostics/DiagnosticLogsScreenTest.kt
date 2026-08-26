package com.bks.feature.diagnostics

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLogsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun plaintextPayloadIsDisplayedWithoutRevealConfirmation() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }

        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("application_failure", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("测试异常秘密", substring = true).assertIsDisplayed()

        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithText("显示敏感内容").assertDoesNotExist()
        composeRule.onNodeWithText("遮罩内容").assertDoesNotExist()
    }

    @Test
    fun releaseEnableRequiresExplicitConfirmation() {
        val repository = FakeDiagnosticRepository(initialEnabled = false)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = false,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }

        composeRule.onNodeWithTag("diagnostic-enabled-switch").performClick()
        composeRule.onNodeWithText("开启敏感诊断日志？").assertIsDisplayed()
        assertEquals(false, repository.enabled.value)

        composeRule.onNodeWithText("理解并开启").performClick()
        assertEquals(true, repository.enabled.value)
        assertTrue(repository.lastUserConfirmed)
    }

    @Test
    fun backgroundingKeepsPlaintextVisibleAfterReturning() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }
        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("测试异常秘密", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("测试异常秘密", substring = true).assertIsDisplayed()
    }

    @Test
    fun queryAndFiltersSurviveRestoration() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }

        composeRule.onNodeWithText("筛选事件、原因、traceId / sessionId")
            .performScrollTo()
            .performTextInput("parsed")
        composeRule.onNodeWithText("Info").performScrollTo().performClick()
        composeRule.onNodeWithTag("diagnostic-event-list").performScrollToIndex(COMPONENT_FILTER_INDEX)
        composeRule.onNodeWithText("Application").performScrollTo().performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("筛选事件、原因、traceId / sessionId")
            .performScrollTo()
            .assertTextContains("parsed")
        composeRule.onNodeWithText("Info").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("diagnostic-event-list").performScrollToIndex(COMPONENT_FILTER_INDEX)
        composeRule.onNodeWithText("Application").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("application_failure", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun exportShowsProgressAndPreventsDuplicateStart() {
        val repository = FakeDiagnosticRepository(initialEnabled = true).apply {
            blockExport = true
        }
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }

        startExport(repository)

        composeRule.onNodeWithTag("diagnostic-export").assertIsNotEnabled()
        composeRule.onNodeWithText("正在导出…").assertIsDisplayed()
    }

    @Test
    fun backgroundingCancelsExportAndAllowsFreshDirectExport() {
        val repository = FakeDiagnosticRepository(initialEnabled = true).apply {
            blockExport = true
        }
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository) { "fresh-diagnostics.log" }
            )
        }
        startExport(repository)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.onNodeWithText("导出已取消").assertDoesNotExist()
        repository.blockExport = false
        startExport(repository, expectedExportCalls = 2)
        composeRule.onNodeWithText("导出完成").assertIsDisplayed()
    }

    @Test
    fun delayedOldWriterCannotClearNewWriterCancellationHandle() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        val firstWriterStarted = CompletableDeferred<Unit>()
        val releaseFirstWriter = CompletableDeferred<Unit>()
        val firstWriterReleased = CompletableDeferred<Unit>()
        val secondWriterStarted = CompletableDeferred<Unit>()
        val secondWriterCancelled = CompletableDeferred<Unit>()
        val writerCalls = AtomicInteger()
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository) {
                    if (writerCalls.incrementAndGet() == 1) {
                        firstWriterStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { releaseFirstWriter.await() }
                            firstWriterReleased.complete(Unit)
                        }
                    } else {
                        secondWriterStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            secondWriterCancelled.complete(Unit)
                        }
                    }
                }
            )
        }

        startExport(repository, expectedExportCalls = 1)
        composeRule.waitUntil(timeoutMillis = 5_000L) { firstWriterStarted.isCompleted }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        startExport(repository, expectedExportCalls = 2)
        composeRule.waitUntil(timeoutMillis = 5_000L) { secondWriterStarted.isCompleted }
        releaseFirstWriter.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000L) { firstWriterReleased.isCompleted }
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil(timeoutMillis = 5_000L) { secondWriterCancelled.isCompleted }
    }

    @Test
    fun completedExportShowsFileNameInVisibleDialog() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository) { "test-diagnostics.log" }
            )
        }

        startExport(repository)

        composeRule.onNodeWithText("导出完成").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-result-message")
            .assertIsDisplayed()
    }

    @Test
    fun failedDirectExportKeepsResultVisible() {
        val repository = FakeDiagnosticRepository(initialEnabled = true).apply {
            failExport = true
        }
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                overrides = diagnosticScreenOverrides(repository)
            )
        }

        startExport(repository)

        composeRule.onNodeWithText("导出失败").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-result-message")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-passphrase").assertDoesNotExist()
    }

    private fun startExport(repository: FakeDiagnosticRepository, expectedExportCalls: Int = 1) {
        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithTag("diagnostic-export").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            repository.exportCallCount == expectedExportCalls
        }
    }

    private companion object {
        const val ACTIONS_ITEM_INDEX = 4
        const val COMPONENT_FILTER_INDEX = 7
        const val EVENT_ITEM_INDEX = 8
    }
}

private fun diagnosticViewModelFactory(
    repository: DiagnosticLogRepository
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DiagnosticLogsViewModel(repository) as T
}

private fun diagnosticScreenOverrides(
    repository: DiagnosticLogRepository,
    exportWriter: (suspend (String) -> String)? = null
): DiagnosticLogsScreenOverrides = DiagnosticLogsScreenOverrides(
    viewModelFactory = diagnosticViewModelFactory(repository),
    applySecureWindowFlag = false,
    exportWriter = exportWriter
)

private class FakeDiagnosticRepository(initialEnabled: Boolean) : DiagnosticLogRepository {
    private val mutableEnabled = MutableStateFlow(initialEnabled)
    private val mutableEvents = MutableStateFlow(
        listOf(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    timestampEpochMillis = 1L,
                    level = DiagnosticLevel.Info,
                    component = DiagnosticComponent.Application,
                    event = "application_failure",
                    traceId = "trace-ui",
                    source = DiagnosticSource.System,
                    outcome = "success",
                    reason = "parsed"
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(DiagnosticSensitiveField.ExceptionDetails to "测试异常秘密")
                )
            )
        )
    )
    private val mutableStats = MutableStateFlow(DiagnosticLogStats(eventCount = 1))
    var lastUserConfirmed = false
    @Volatile
    var blockExport = false
    @Volatile
    var failExport = false
    @Volatile
    var exportCallCount = 0

    override val enabled: StateFlow<Boolean> = mutableEnabled
    override val events: StateFlow<List<DiagnosticEvent>> = mutableEvents
    override val stats: StateFlow<DiagnosticLogStats> = mutableStats

    override fun setEnabled(enabled: Boolean, userConfirmed: Boolean): Boolean {
        lastUserConfirmed = userConfirmed
        mutableEnabled.value = enabled
        return true
    }

    override fun record(event: DiagnosticEvent) = Unit
    override suspend fun refresh(limit: Int) = Unit
    override suspend fun clear(keepEnabledPreference: Boolean) {
        mutableEvents.value = emptyList()
        mutableStats.value = DiagnosticLogStats()
    }

    override suspend fun exportPlainText(): String {
        exportCallCount += 1
        if (blockExport) awaitCancellation()
        if (failExport) error("export failed")
        return "plaintext"
    }
}
