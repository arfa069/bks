package com.bks.feature.diagnostics

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogFormatterTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUpTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun captureEventCodecRoundTripsOptionalFieldsOnOnePhysicalLine() {
        val event = captureEvent()

        val encoded = DiagnosticEventCodec.encode(event)
        val decoded = DiagnosticEventCodec.decode(encoded)

        assertFalse(encoded.contains('\n'))
        assertTrue(encoded.contains("\"time\":\"2026-08-26 08:32:23\""))
        assertEquals(event, decoded)
    }

    @Test
    fun formatterUsesFixedFieldsAndEscapesEmbeddedLineBreaks() {
        val line = formatDiagnosticLogLine(captureEvent())

        assertEquals(
            "2026-08-26 08:32:23 | event=WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6 | " +
                "nodes=512+ | text=\"支付成功\\n￥10.90\\n商户：某店\" | result=成功:inserted=1",
            line
        )
        assertFalse(line.contains('\n'))
    }

    private fun captureEvent() = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 1_787_704_343_000L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.AutomaticBookkeeping,
            event = BILL_SYNC_CAPTURE_BATCH_EVENT,
            traceId = "trace-format",
            source = DiagnosticSource.Accessibility,
            outcome = "inserted",
            triggeredEvents = "WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6",
            nodeCount = 512,
            nodeLimitReached = true,
            insertedCount = 1
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.PageText to "支付成功\n￥10.90\n商户：某店")
        )
    )
}
