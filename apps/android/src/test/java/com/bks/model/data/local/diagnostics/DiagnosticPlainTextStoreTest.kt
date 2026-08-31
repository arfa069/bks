package com.bks.model.data.local.diagnostics

import com.bks.model.data.local.diagnostics.DiagnosticPlainTextStore
import com.bks.model.domain.diagnostics.BILL_SYNC_CAPTURE_BATCH_EVENT
import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPlainTextStoreTest {
    @Test
    fun plaintextUtf8SegmentContainsReadablePayloadAndRoundTrips() {
        val directory = Files.createTempDirectory("diagnostic-store").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})
        val event = event("支付宝商户ABC\n订单号ORDER-42")

        store.append(event)

        val segment = directory.listFiles().orEmpty().single()
        val diskText = segment.readText(Charsets.UTF_8)
        assertEquals("log", segment.extension)
        assertTrue(diskText.contains("支付宝商户ABC"))
        assertTrue(diskText.contains("ORDER-42"))
        assertFalse(diskText.contains("AADLOG1:"))
        assertEquals(event, store.readAll().single())
    }

    @Test
    fun multilinePayloadOccupiesOnePhysicalLinePerEvent() {
        val directory = Files.createTempDirectory("diagnostic-lines").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})

        store.append(event("支付成功\n￥10.90\n订单 1"))
        store.append(event("支付成功\r\n￥20.00\n订单 2"))

        val lines = directory.listFiles().orEmpty().single().readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        assertFalse(lines.any { it.contains('\r') })
        assertEquals(
            listOf("支付成功\n￥10.90\n订单 1", "支付成功\r\n￥20.00\n订单 2"),
            store.readAll().map { it.sensitivePayload.fields.getValue(DiagnosticSensitiveField.PageText) }
        )
    }

    @Test
    fun batchAppendKeepsEveryEventInChronologicalOrder() {
        val directory = Files.createTempDirectory("diagnostic-batch").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})
        val events = (0 until 120).map { event("event-$it") }

        store.appendAll(events)

        assertEquals(120, directory.listFiles().orEmpty().sumOf { file -> file.readLines().size })
        assertEquals(
            (0 until 120).map { "event-$it" },
            store.readAll().map {
                it.sensitivePayload.fields[DiagnosticSensitiveField.PageText]
            }
        )
    }

    @Test
    fun corruptAndPartialLinesAreSkippedWithoutLosingValidEvents() {
        val directory = Files.createTempDirectory("diagnostic-corrupt").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})
        store.append(event("first valid event"))
        directory.listFiles().orEmpty().single().appendText("not-json\n{\"partial\"", Charsets.UTF_8)

        val restored = store.readAll()

        assertEquals(1, restored.size)
        assertEquals(1, store.eventCount())
        assertEquals(
            "first valid event",
            restored.single().sensitivePayload.fields[DiagnosticSensitiveField.PageText]
        )
    }

    @Test
    fun readLatestReturnsRequestedNewestEventsInChronologicalOrder() {
        val directory = Files.createTempDirectory("diagnostic-latest").toFile()
        val store = DiagnosticPlainTextStore(directory, clearLegacyKey = {})
        repeat(3) { store.append(event("event-$it")) }

        val latest = store.readLatest(2)

        assertEquals(
            listOf("event-1", "event-2"),
            latest.map { it.sensitivePayload.fields[DiagnosticSensitiveField.PageText] }
        )
    }

    @Test
    fun legacySegmentsAreIgnoredAndClearRemovesBothFormatsAndLegacyKey() {
        val directory = Files.createTempDirectory("diagnostic-legacy").toFile()
        var legacyKeyCleared = false
        val store = DiagnosticPlainTextStore(
            directory,
            clearLegacyKey = { legacyKeyCleared = true }
        )
        store.append(event("new log"))
        val legacy = directory.resolve("segment-old.aadlog").apply { writeText("AADLOG1:ciphertext") }

        assertEquals(1, store.eventCount())
        assertEquals(1, store.segmentCount())

        store.clear()

        assertTrue(legacyKeyCleared)
        assertFalse(legacy.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun totalLimitRotatesOldestPlaintextSegments() {
        val directory = Files.createTempDirectory("diagnostic-rotate").toFile()
        val store = DiagnosticPlainTextStore(
            directory = directory,
            maxSegmentBytes = 500,
            maxTotalBytes = 1_100,
            clock = generateSequence(1L) { it + 1 }.iterator()::next,
            clearLegacyKey = {}
        )

        repeat(12) { store.append(event("event-$it-" + "x".repeat(220))) }

        assertTrue(store.storedBytes() <= 1_100)
        assertFalse(store.readAll().any {
            it.sensitivePayload.fields[DiagnosticSensitiveField.PageText]?.startsWith("event-0-") == true
        })
    }

    private fun event(text: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 123L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.AutomaticBookkeeping,
            event = BILL_SYNC_CAPTURE_BATCH_EVENT,
            traceId = "trace-123",
            source = DiagnosticSource.Accessibility,
            outcome = "failed",
            reason = "context_missing"
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.PageText to text)
        )
    )
}
