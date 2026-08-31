package com.bks.model.domain.diagnostics

import com.bks.model.data.local.diagnostics.DiagnosticEventCodec
import com.bks.model.domain.diagnostics.BILL_SYNC_CAPTURE_BATCH_EVENT
import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
import com.bks.model.domain.diagnostics.formatDiagnosticLogLine
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
    fun codecReadsSchemaOneLinesWithoutDiagnosticAttributes() {
        val legacy = """{"schemaVersion":1,"timestampEpochMillis":1787704343000,"level":"Info","component":"AutomaticBookkeeping","event":"bill_sync_capture_batch","traceId":"legacy","source":"Accessibility","suppressedCount":0,"nodeLimitReached":false,"sensitivePayload":{},"truncatedFields":[]}"""

        val decoded = DiagnosticEventCodec.decode(legacy)

        assertEquals(BILL_SYNC_CAPTURE_BATCH_EVENT, decoded.metadata.event)
        assertTrue(decoded.metadata.attributes.isEmpty())
    }

    @Test
    fun formatterUsesFixedFieldsAndEscapesEmbeddedLineBreaks() {
        val line = formatDiagnosticLogLine(captureEvent())

        assertEquals(
            "2026-08-26 08:32:23 | stage=capture | session=4 | " +
                "event=WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6 | trigger=leading | " +
                "eventAtFirst=2026-08-26 08:32:22 | eventAtLast=2026-08-26 08:32:23 | " +
                "callbackEnterAtFirst=2026-08-26 08:32:23 | " +
                "callbackEnterAtLast=2026-08-26 08:32:23 | " +
                "receivedAtFirst=2026-08-26 08:32:23 | receivedAtLast=2026-08-26 08:32:23 | " +
                "deliveryDelayMs=3 | frameworkDeliveryMs=1 | superDurationMs=1 | " +
                "coordinatorDelayMs=1 | queueDelayMs=2 | " +
                "triggerPkg=com.eg.android.AlipayGphone | " +
                "triggerWin=82 | rootPkg=com.eg.android.AlipayGphone | rootWin=82 | rootBy=event | " +
                "allRoots=0 | activeRoots=0 | lookupMs=6 | collectMs=3 | recognizeMs=2 | " +
                "persistMs=3 | captureAt=2026-08-26 08:32:23 | " +
                "captureEndAt=2026-08-26 08:32:23 | captureMs=14 | nodes=512+ | " +
                "text=\"支付成功\\n￥10.90\\n商户：某店\" | result=成功:inserted=1",
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
            sessionId = "4",
            source = DiagnosticSource.Accessibility,
            outcome = "inserted",
            triggeredEvents = "WINDOW_STATE_CHANGED×1,WINDOW_CONTENT_CHANGED×6",
            nodeCount = 512,
            nodeLimitReached = true,
            insertedCount = 1,
            attributes = linkedMapOf(
                "stage" to "capture",
                "trigger" to "leading",
                "eventAtFirst" to "1787704342000",
                "eventAtLast" to "1787704343000",
                "callbackEnterAtFirst" to "1787704343000",
                "callbackEnterAtLast" to "1787704343000",
                "receivedAtFirst" to "1787704343000",
                "receivedAtLast" to "1787704343000",
                "deliveryDelayMs" to "3",
                "frameworkDeliveryMs" to "1",
                "superDurationMs" to "1",
                "coordinatorDelayMs" to "1",
                "queueDelayMs" to "2",
                "triggerPkg" to "com.eg.android.AlipayGphone",
                "triggerWin" to "82",
                "rootPkg" to "com.eg.android.AlipayGphone",
                "rootWin" to "82",
                "rootBy" to "event",
                "allRoots" to "0",
                "activeRoots" to "0",
                "lookupMs" to "6",
                "collectMs" to "3",
                "recognizeMs" to "2",
                "persistMs" to "3",
                "captureAt" to "1787704343000",
                "captureEndAt" to "1787704343014",
                "captureMs" to "14"
            )
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.PageText to "支付成功\n￥10.90\n商户：某店")
        )
    )
}
