package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.diagnostics.DiagnosticRecorder
import com.bks.model.platform.billsync.ALIPAY_PACKAGE_NAME
import com.bks.model.platform.billsync.BillSyncAccessibilityCallbackTiming
import com.bks.model.platform.billsync.BillSyncCaptureCoordinator
import com.bks.model.platform.billsync.BillSyncCaptureOperations
import com.bks.model.platform.billsync.BillSyncRuntimeProbeHandle
import com.bks.model.platform.billsync.CAPTURE_SETTLE_MILLIS
import com.bks.model.platform.billsync.DISPATCH_OUTCOME_PROCESS_PAUSED
import com.bks.model.platform.billsync.MAIN_PROBE_INTERVAL_MILLIS
import com.bks.model.platform.billsync.selectCaptureRoot
import com.bks.model.platform.billsync.WAVE_INTERVAL_MILLIS
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.diagnostics.BILL_SYNC_CAPTURE_BATCH_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_RUNTIME_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_SERVICE_EVENT
import com.bks.model.domain.diagnostics.BILL_SYNC_WAVE_EVENT
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.InMemoryDiagnosticRecorder
import com.ven.assists.AssistsCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class BillSyncCaptureCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun repeatedStartCreatesOneEnabledObserverAndStopCancelsIt() = runTest {
        val enabled = MutableStateFlow(false)
        var collections = 0
        val observedEnabled = flow {
            collections += 1
            emitAll(enabled)
        }
        val coordinator = coordinator(FakeCaptureProcessor(), observedEnabled)

        try {
            coordinator.start()
            coordinator.start()
            runCurrent()

            assertEquals(1, collections)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun captureDiagnosticPreservesEventDeliveryQueueAndRootSelectionTiming() = runTest {
        var wallClock = CAPTURE_TIME + 12L
        var uptimeClock = EVENT_UPTIME + 12L
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = FakeCaptureProcessor(),
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            clock = { wallClock },
            uptimeClock = { uptimeClock }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(
                event(
                    eventTime = EVENT_UPTIME - 125L,
                    source = root()
                ),
                BillSyncAccessibilityCallbackTiming(
                    callbackEnterAtEpochMillis = CAPTURE_TIME,
                    callbackEnterUptimeMillis = EVENT_UPTIME,
                    superReturnUptimeMillis = EVENT_UPTIME + 7L
                )
            )
            wallClock += 40L
            uptimeClock += 40L
            runCurrent()

            val capture = captureEvents(diagnostics).single()
            assertEquals("1", capture.metadata.sessionId)
            assertEquals("leading", capture.metadata.attributes["trigger"])
            assertEquals("137", capture.metadata.attributes["deliveryDelayMs"])
            assertEquals("125", capture.metadata.attributes["frameworkDeliveryMs"])
            assertEquals("7", capture.metadata.attributes["superDurationMs"])
            assertEquals("5", capture.metadata.attributes["coordinatorDelayMs"])
            assertEquals("40", capture.metadata.attributes["queueDelayMs"])
            assertEquals(PAYMENT_PACKAGE, capture.metadata.attributes["triggerPkg"])
            assertEquals(PAYMENT_PACKAGE, capture.metadata.attributes["rootPkg"])
            assertEquals("event", capture.metadata.attributes["rootBy"])
            assertEquals("0", capture.metadata.attributes["allRoots"])
            assertEquals("0", capture.metadata.attributes["activeRoots"])
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun serviceWaveAndDelayedMainDispatchAreRecordedWithoutPerCallbackWrites() = runTest {
        var wallClock = CAPTURE_TIME
        var uptimeClock = EVENT_UPTIME
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = FakeCaptureProcessor(),
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            clock = { wallClock },
            uptimeClock = { uptimeClock }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            val connected = diagnostics.events.filter {
                it.metadata.event == BILL_SYNC_SERVICE_EVENT
            }
            assertEquals(listOf("CONNECTED", "SESSION_STARTED"), connected.map {
                it.metadata.attributes["eventName"]
            })
            assertTrue(connected.all { it.metadata.sessionId == "1" })
            assertTrue(connected.all {
                it.metadata.attributes["processStartElapsedMs"] == "1234"
            })

            coordinator.onAccessibilityEvent(event())
            runCurrent()
            wallClock += CAPTURE_SETTLE_MILLIS
            uptimeClock += CAPTURE_SETTLE_MILLIS
            advanceTimeBy(CAPTURE_SETTLE_MILLIS)
            runCurrent()
            wallClock += WAVE_INTERVAL_MILLIS - CAPTURE_SETTLE_MILLIS
            uptimeClock += WAVE_INTERVAL_MILLIS - CAPTURE_SETTLE_MILLIS
            advanceTimeBy(WAVE_INTERVAL_MILLIS - CAPTURE_SETTLE_MILLIS)
            runCurrent()

            val wave = diagnostics.events.single { it.metadata.event == BILL_SYNC_WAVE_EVENT }
            assertEquals("1", wave.metadata.attributes["allCallbacks"])
            assertEquals("1", wave.metadata.attributes["eligibleCallbacks"])
            assertEquals("0", wave.metadata.attributes["filteredCallbacks"])
            assertEquals("2", wave.metadata.attributes["captures"])
            assertEquals("1", wave.metadata.attributes["leading"])
            assertEquals("1", wave.metadata.attributes["trailing"])

            wallClock += 5_000L
            uptimeClock += 5_000L
            advanceTimeBy(MAIN_PROBE_INTERVAL_MILLIS - WAVE_INTERVAL_MILLIS)
            runCurrent()

            val runtime = diagnostics.events.single { it.metadata.event == BILL_SYNC_RUNTIME_EVENT }
            assertEquals("DISPATCH_DELAY", runtime.metadata.attributes["eventName"])
            assertEquals("4000", runtime.metadata.attributes["workerDelayMs"])
            assertEquals("0", runtime.metadata.attributes["mainPostDelayMs"])
            assertEquals(DISPATCH_OUTCOME_PROCESS_PAUSED, runtime.metadata.outcome)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun disabledCaptureIsIgnoredAndSameWindowBurstRunsOnLeadingAndTrailingEdges() = runTest {
        val enabled = MutableStateFlow(false)
        val processor = FakeCaptureProcessor(recognized = { it == PAGE_TEXT })
        val diagnostics = InMemoryDiagnosticRecorder()
        var pageText = "处理中"
        var activeWindowLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = enabled,
            diagnosticRecorder = diagnostics,
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(root(pageText))
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            advanceTimeBy(CAPTURE_SETTLE_MILLIS)
            runCurrent()
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertEquals(0, activeWindowLookups)

            enabled.value = true
            runCurrent()
            coordinator.onAccessibilityEvent(event())

            assertEquals(0, activeWindowLookups)
            runCurrent()

            assertEquals(listOf("处理中"), processor.recognizedPageTexts)
            assertEquals(1, activeWindowLookups)
            assertTrue(processor.persistedResults.isEmpty())

            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 100)
            pageText = PAGE_TEXT
            repeat(9) { coordinator.onAccessibilityEvent(event()) }
            runCurrent()
            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 1)
            runCurrent()
            assertEquals(1, activeWindowLookups)

            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf("处理中", PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(1, processor.persistedResults.size)
            assertEquals(2, activeWindowLookups)
            val diagnostic = diagnostics.events.single { it.metadata.insertedCount == 1 }
            assertEquals("WINDOW_CONTENT_CHANGED×10", diagnostic.metadata.triggeredEvents)
            assertEquals(1, diagnostic.metadata.nodeCount)
            assertEquals(1, diagnostic.metadata.insertedCount)
            assertEquals(
                PAGE_TEXT,
                diagnostic.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
            )
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun unsupportedAndNonAlipayEventsDoNotQueryAnyWindowRoots() = runTest {
        var rootLookups = 0
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = {
                rootLookups += 1
                listOf(root())
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            val ignoredEvents = listOf(
                event(eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED),
                event(
                    eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    packageName = OTHER_PACKAGE
                ),
                event(
                    eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    packageName = null
                ),
                event(
                    eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    packageName = OTHER_PACKAGE
                )
            )
            ignoredEvents.forEach(coordinator::onAccessibilityEvent)

            advanceTimeBy(CAPTURE_SETTLE_MILLIS)
            runCurrent()
            advanceTimeBy(WAVE_INTERVAL_MILLIS - CAPTURE_SETTLE_MILLIS)
            runCurrent()

            assertEquals(0, rootLookups)
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertTrue(processor.persistedResults.isEmpty())
            val wave = diagnostics.events.single { it.metadata.event == BILL_SYNC_WAVE_EVENT }
            assertEquals("4", wave.metadata.attributes["allCallbacks"])
            assertEquals("0", wave.metadata.attributes["eligibleCallbacks"])
            assertEquals("4", wave.metadata.attributes["filteredCallbacks"])
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun destroyedSessionClosesDedicatedRuntimeProbe() = runTest {
        var closedProbes = 0
        val coordinator = coordinator(
            processor = FakeCaptureProcessor(),
            enabled = MutableStateFlow(true),
            runtimeProbeHandleFactory = {
                BillSyncRuntimeProbeHandle(
                    dispatcher = StandardTestDispatcher(testScheduler),
                    closeDispatcher = { closedProbes += 1 }
                )
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onServiceDestroyed()

            assertEquals(1, closedProbes)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun packageLessWindowsChangedUsesVerifiedAlipayActiveWindow() = runTest {
        var activeWindowLookups = 0
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(root())
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(
                event(
                    eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    packageName = null
                )
            )
            runCurrent()

            assertEquals(1, activeWindowLookups)
            assertEquals(listOf(PAGE_TEXT), processor.recognizedPageTexts)
            val leading = captureEvents(diagnostics).single()
            assertEquals("active", leading.metadata.attributes["rootBy"])
            assertEquals("0", leading.metadata.attributes["allRoots"])
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun differentWindowsKeepIndependentPendingCaptures() = runTest {
        val processor = FakeCaptureProcessor()
        var activeWindowLookups = 0
        val firstRoot = root(pageText = PAGE_TEXT, windowId = WINDOW_ID)
        val secondRoot = root(pageText = SECOND_PAGE_TEXT, windowId = SECOND_WINDOW_ID)
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(firstRoot, secondRoot)
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event(WINDOW_ID, source = firstRoot))
            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()
            coordinator.onAccessibilityEvent(event(SECOND_WINDOW_ID, source = secondRoot))
            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()

            assertEquals(
                listOf(PAGE_TEXT, SECOND_PAGE_TEXT, PAGE_TEXT),
                processor.recognizedPageTexts
            )
            assertEquals(2, processor.persistedResults.size)
            assertEquals(1, activeWindowLookups)

            advanceTimeBy(CAPTURE_SETTLE_MILLIS / 2)
            runCurrent()

            assertEquals(
                listOf(PAGE_TEXT, SECOND_PAGE_TEXT, PAGE_TEXT, SECOND_PAGE_TEXT),
                processor.recognizedPageTexts
            )
            assertEquals(2, processor.persistedResults.size)
            assertEquals(2, activeWindowLookups)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun processorFailureReleasesWindowForRetry() = runTest {
        val processor = FakeCaptureProcessor(persistFailure = IllegalStateException("expected"))
        val coordinator = coordinator(processor, MutableStateFlow(true))
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runSettledCapture()
            processor.persistFailure = null
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(3, processor.persistAttempts)
            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun serviceDestroyCancelsInFlightPersistenceAndNewSessionCanCapture() = runTest {
        val persistenceStarted = CompletableDeferred<Unit>()
        val processor = FakeCaptureProcessor(
            persistBlock = {
                persistenceStarted.complete(Unit)
                awaitCancellation()
            }
        )
        val coordinator = coordinator(processor, MutableStateFlow(true))
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runSettledCapture()
            assertTrue(persistenceStarted.isCompleted)

            coordinator.onServiceDestroyed()
            runCurrent()
            assertEquals(1, processor.cancelledPersistences)

            processor.persistBlock = null
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun serviceDestroyCancelsQueuedLeadingAndPendingTrailingCaptures() = runTest {
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        var activeWindowLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(root())
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            coordinator.onServiceDestroyed()
            runCurrent()

            assertEquals(0, activeWindowLookups)
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertTrue(processor.persistedResults.isEmpty())
            assertTrue(captureEvents(diagnostics).isEmpty())

            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runCurrent()
            advanceTimeBy(CAPTURE_SETTLE_MILLIS - 1)
            runCurrent()
            coordinator.onServiceDestroyed()
            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, activeWindowLookups)
            assertEquals(listOf(PAGE_TEXT), processor.recognizedPageTexts)
            assertEquals(1, processor.persistedResults.size)
            assertEquals(1, captureEvents(diagnostics).size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun missingRootProducesConciseFailuresForLeadingAndTrailingCaptures() = runTest {
        val diagnostics = InMemoryDiagnosticRecorder()
        val coordinator = coordinator(
            processor = FakeCaptureProcessor(),
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = { emptyList() }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event(eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            runSettledCapture()

            val captures = captureEvents(diagnostics)
            assertEquals(2, captures.size)
            captures.forEach { diagnostic ->
                assertEquals("WINDOWS_CHANGED×1", diagnostic.metadata.triggeredEvents)
                assertEquals("root_missing", diagnostic.metadata.reason)
                assertEquals(null, diagnostic.metadata.nodeCount)
            }
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun captureRootSelectionRejectsActiveWindowFromDifferentPackage() {
        val activeRoot = root(
            pageText = PAGE_TEXT,
            windowId = SECOND_WINDOW_ID,
            packageName = OTHER_PACKAGE
        )

        val selection = selectCaptureRoot(
            eventRoot = null,
            eventWindowId = WINDOW_ID,
            activeRootsProvider = { listOf(activeRoot) }
        )

        assertEquals(listOf(activeRoot), selection.activeRoots)
        assertNull(selection.root)
    }

    @Test
    fun packageLessWindowsChangedDoesNotCaptureDifferentActivePackage() = runTest {
        val processor = FakeCaptureProcessor()
        val diagnostics = InMemoryDiagnosticRecorder()
        var activeWindowLookups = 0
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            diagnosticRecorder = diagnostics,
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(
                            root(
                                pageText = PAGE_TEXT,
                                windowId = SECOND_WINDOW_ID,
                                packageName = OTHER_PACKAGE
                            )
                        )
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(
                event(
                    eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    packageName = null
                )
            )
            runSettledCapture()

            assertEquals(2, activeWindowLookups)
            assertTrue(processor.recognizedPageTexts.isEmpty())
            assertTrue(processor.persistedResults.isEmpty())
            assertTrue(captureEvents(diagnostics).all { it.metadata.reason == "root_missing" })
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun deferredLatestPageIsCheckedAfterPersistenceAndReleasesWindow() = runTest {
        val releasePersistence = CompletableDeferred<Unit>()
        var pageText = PAGE_TEXT
        var activeWindowLookups = 0
        val processor = FakeCaptureProcessor(
            recognized = { it == PAGE_TEXT },
            persistBlock = { releasePersistence.await() }
        )
        val coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        activeWindowLookups += 1
                        listOf(root(pageText))
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runSettledCapture()
            pageText = "处理中"
            repeat(10) { coordinator.onAccessibilityEvent(event()) }
            runSettledCapture()
            assertEquals(1, activeWindowLookups)

            releasePersistence.complete(Unit)
            runCurrent()
            assertEquals(listOf(PAGE_TEXT, "处理中"), processor.recognizedPageTexts)
            assertEquals(2, activeWindowLookups)

            processor.persistBlock = null
            pageText = PAGE_TEXT
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(2, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun destroyedSessionCannotWriteSuppressionStateAfterRootLookup() = runTest {
        val processor = FakeCaptureProcessor()
        var destroyDuringLookup = true
        lateinit var coordinator: BillSyncCaptureCoordinator
        coordinator = coordinator(
            processor = processor,
            enabled = MutableStateFlow(true),
            rootsProvider = { scope ->
                when (scope) {
                    AssistsCore.NodeLookupScope.ActiveWindow -> {
                        if (destroyDuringLookup) coordinator.onServiceDestroyed()
                        listOf(root())
                    }
                    AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
                }
            }
        )
        try {
            coordinator.start()
            coordinator.onServiceConnected()
            runCurrent()

            coordinator.onAccessibilityEvent(event())
            runSettledCapture()
            assertTrue(processor.recognizedPageTexts.isEmpty())

            destroyDuringLookup = false
            coordinator.onServiceConnected()
            coordinator.onAccessibilityEvent(event())
            runSettledCapture()

            assertEquals(1, processor.persistedResults.size)
        } finally {
            coordinator.stop()
        }
    }

    @Suppress("LongParameterList")
    private fun kotlinx.coroutines.test.TestScope.coordinator(
        processor: FakeCaptureProcessor,
        enabled: Flow<Boolean>,
        diagnosticRecorder: InMemoryDiagnosticRecorder = InMemoryDiagnosticRecorder(),
        rootsProvider: (AssistsCore.NodeLookupScope) -> List<AccessibilityNodeInfo> = { scope ->
            when (scope) {
                AssistsCore.NodeLookupScope.ActiveWindow -> listOf(root())
                AssistsCore.NodeLookupScope.AllWindows -> error("AllWindows must not be queried")
            }
        },
        clock: () -> Long = { CAPTURE_TIME + testScheduler.currentTime },
        uptimeClock: () -> Long = { EVENT_UPTIME + testScheduler.currentTime },
        elapsedRealtimeClock: () -> Long = uptimeClock,
        runtimeProbeHandleFactory: () -> BillSyncRuntimeProbeHandle = {
            BillSyncRuntimeProbeHandle(StandardTestDispatcher(testScheduler))
        }
    ): BillSyncCaptureCoordinator = BillSyncCaptureCoordinator(
        context = context,
        enabledIntent = enabled,
        processor = BillSyncCaptureOperations(
            recognize = processor::recognize,
            persist = processor::persist
        ),
        applicationScope = backgroundScope,
        diagnosticRecorder = diagnosticRecorder,
        clock = clock,
        uptimeClock = uptimeClock,
        elapsedRealtimeClock = elapsedRealtimeClock,
        pidProvider = { 42 },
        processStartElapsedProvider = { 1_234L },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        captureDispatcher = StandardTestDispatcher(testScheduler),
        runtimeProbeHandleFactory = runtimeProbeHandleFactory,
        rootsProvider = rootsProvider
    )

    private fun kotlinx.coroutines.test.TestScope.runSettledCapture() {
        advanceTimeBy(CAPTURE_SETTLE_MILLIS)
        runCurrent()
    }

    private fun captureEvents(recorder: InMemoryDiagnosticRecorder) = recorder.events.filter {
        it.metadata.event == BILL_SYNC_CAPTURE_BATCH_EVENT
    }

    @Suppress("DEPRECATION")
    private fun event(
        windowId: Int = WINDOW_ID,
        eventType: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        eventTime: Long = 0L,
        packageName: String? = PAYMENT_PACKAGE,
        source: AccessibilityNodeInfo? = null
    ): AccessibilityEvent = AccessibilityEvent.obtain().apply {
        this.packageName = packageName
        this.eventType = eventType
        this.eventTime = eventTime
        shadowOf(this).apply {
            setWindowId(windowId)
            source?.let(::setSourceNode)
        }
    }

    @Suppress("DEPRECATION")
    private fun root(
        pageText: String = PAGE_TEXT,
        windowId: Int = WINDOW_ID,
        packageName: String = PAYMENT_PACKAGE
    ): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            this.packageName = packageName
            text = pageText
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(windowId)
            shadowOf(this).setAccessibilityWindowInfo(window)
        }

    private class FakeCaptureProcessor(
        private val recognized: (String) -> Boolean = { true },
        var persistFailure: Throwable? = null,
        var persistBlock: (suspend () -> Unit)? = null
    ) {
        val recognizedPageTexts = mutableListOf<String>()
        val persistedResults = mutableListOf<BillSyncResult>()
        var persistAttempts = 0
        var cancelledPersistences = 0

        fun recognize(pageText: String): BillSyncResult {
            recognizedPageTexts += pageText
            return BillSyncResult(recognized = recognized(pageText), createdEntries = emptyList())
        }

        suspend fun persist(result: BillSyncResult): BillSyncResult {
            persistAttempts += 1
            try {
                persistBlock?.invoke()
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelledPersistences += 1
                throw error
            }
            persistFailure?.let { throw it }
            val persisted = result.copy(insertedCount = 1)
            persistedResults += persisted
            return persisted
        }
    }

    private companion object {
        const val PAYMENT_PACKAGE = ALIPAY_PACKAGE_NAME
        const val OTHER_PACKAGE = "com.example.other"
        const val WINDOW_ID = 7
        const val SECOND_WINDOW_ID = 8
        const val PAGE_TEXT = "支付成功\n￥4.99\n订单 1"
        const val SECOND_PAGE_TEXT = "支付成功\n￥8.88\n订单 2"
        const val CAPTURE_TIME = 1_787_704_343_000L
        const val EVENT_UPTIME = 10_000L
    }
}
