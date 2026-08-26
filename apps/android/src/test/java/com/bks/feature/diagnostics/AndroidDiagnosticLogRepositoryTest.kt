package com.bks.feature.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDiagnosticLogRepositoryTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostic_log_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun releaseDefaultsOffAndRequiresExplicitConfirmation() {
        val repository = fixture(isDebugBuild = false).repository

        assertFalse(repository.enabled.value)
        assertFalse(repository.setEnabled(true, userConfirmed = false))
        assertFalse(repository.enabled.value)
        assertTrue(repository.setEnabled(true, userConfirmed = true))
        assertTrue(repository.enabled.value)
    }

    @Test
    fun historyLoadsOnlyOnExplicitRefresh() = runBlocking {
        val directory = Files.createTempDirectory("diagnostic-load").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})
        store.append(event("existing"))
        val repository = AndroidDiagnosticLogRepository(
            context = context,
            store = store,
            isDebugBuild = true,
            buildDefaultEnabled = true
        )

        assertTrue(repository.events.value.isEmpty())

        repository.refresh(limit = 1)

        assertEquals(1, repository.events.value.size)
        assertEquals("existing", repository.events.value.single().metadata.reason)
    }

    @Test
    fun disablingKeepsHistoryAndClearCanResetReleasePreference() = runBlocking {
        val repository = fixture(isDebugBuild = false).repository
        repository.setEnabled(true, userConfirmed = true)
        repository.recordNow(event("first"))
        repository.setEnabled(false)
        repository.recordNow(event("ignored"))
        repository.refresh()

        assertEquals(1, repository.events.value.size)

        repository.setEnabled(true, userConfirmed = true)
        repository.clear(keepEnabledPreference = true)
        assertTrue(repository.enabled.value)
        assertEquals(0, repository.stats.value.eventCount)

        repository.clear(keepEnabledPreference = false)
        assertFalse(repository.enabled.value)
    }

    @Test
    fun repeatedNonCaptureReasonKeepsExistingFiveSecondSummary() = runBlocking {
        val repository = fixture(isDebugBuild = true).repository
        repository.recordNow(event("repeat"))
        repository.recordNow(event("repeat"))
        repository.refresh()

        val summary = repository.events.value.first { it.metadata.suppressedCount == 1 }
        assertTrue(summary.sensitivePayload.fields.isEmpty())
        assertEquals(2, repository.stats.value.eventCount)
    }

    @Test
    fun expiredCoalesceWindowFlushesSummaryBeforeNextWindow() = runBlocking {
        var now = 1_000L
        val repository = fixture(isDebugBuild = true, clock = { now }).repository
        repository.recordNow(event("repeat"))
        repository.recordNow(event("repeat"))

        now += 5_001L
        repository.recordNow(event("repeat"))
        repository.refresh()

        assertEquals(3, repository.events.value.size)
        assertEquals(1, repository.events.value.count { it.metadata.suppressedCount == 1 })
    }

    @Test
    fun captureBatchesBypassCoalescingAndKeepEveryPlaintextPayload() = runBlocking {
        val repository = fixture(isDebugBuild = true).repository

        repository.recordNow(captureBatch("支付成功\n￥10.90\n订单 1"))
        repository.recordNow(captureBatch("支付成功\n￥20.00\n订单 2"))
        repository.refresh()

        assertEquals(2, repository.events.value.size)
        assertTrue(repository.events.value.all { it.metadata.suppressedCount == 0 })
        assertEquals(
            listOf("支付成功\n￥20.00\n订单 2", "支付成功\n￥10.90\n订单 1"),
            repository.events.value.map {
                it.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
            }
        )
    }

    @Test
    fun plaintextExportIsUtf8OneLinePerEventAndRedactsAuthenticationSecrets() = runBlocking {
        val fixture = fixture(isDebugBuild = true)
        fixture.repository.recordNow(
            captureBatch(
                "支付成功\n￥10.90\n商户：测试店\nAuthorization: Bearer top-secret"
            )
        )

        val exported = fixture.repository.exportPlainText()
        val lines = exported.trimEnd('\n').lines()
        val diskText = fixture.directory.listFiles().orEmpty().single().readText(Charsets.UTF_8)

        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith(formatDiagnosticTimestamp(CAPTURE_TIME)))
        assertTrue(lines.single().contains("event=WINDOW_CONTENT_CHANGED×3"))
        assertTrue(lines.single().contains("nodes=84"))
        assertTrue(lines.single().contains("支付成功\\n￥10.90\\n商户：测试店"))
        assertTrue(lines.single().contains("result=失败:context_missing"))
        assertFalse(exported.contains("top-secret"))
        assertTrue(exported.contains("[REDACTED]"))
        assertTrue(diskText.contains("支付成功"))
        assertFalse(diskText.contains("top-secret"))
    }

    @Test
    fun emptyPlaintextExportIsEmpty() = runBlocking {
        assertEquals("", fixture(isDebugBuild = true).repository.exportPlainText())
    }

    private fun fixture(
        isDebugBuild: Boolean,
        clock: () -> Long = { 1_000L }
    ): RepositoryFixture {
        val directory = Files.createTempDirectory("diagnostic-repository").toFile()
        val repository = AndroidDiagnosticLogRepository(
            context = context,
            store = DiagnosticPlainTextStore(directory, clearLegacyKey = {}),
            isDebugBuild = isDebugBuild,
            buildDefaultEnabled = isDebugBuild,
            clock = clock
        )
        return RepositoryFixture(repository, directory)
    }

    private fun event(reason: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 1_000L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.Application,
            event = "application_event",
            traceId = "trace-$reason",
            source = DiagnosticSource.System,
            reason = reason
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.ExceptionDetails to "application context")
        )
    )

    private fun captureBatch(pageText: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = CAPTURE_TIME,
            level = DiagnosticLevel.Warning,
            component = DiagnosticComponent.AutomaticBookkeeping,
            event = BILL_SYNC_CAPTURE_BATCH_EVENT,
            traceId = "capture-${pageText.hashCode()}",
            source = DiagnosticSource.Accessibility,
            outcome = "failed",
            reason = "context_missing",
            triggeredEvents = "WINDOW_CONTENT_CHANGED×3",
            nodeCount = 84
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.PageText to pageText)
        )
    )

    private data class RepositoryFixture(
        val repository: AndroidDiagnosticLogRepository,
        val directory: java.io.File
    )

    private companion object {
        const val CAPTURE_TIME = 1_777_777_777_000L
    }
}
