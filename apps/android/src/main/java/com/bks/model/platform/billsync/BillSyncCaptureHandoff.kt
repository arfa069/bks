package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncResult
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.CancellationException

internal class BillSyncCaptureOperations(
    val recognize: (String, PaymentSource) -> BillSyncResult,
    val persist: suspend (BillSyncResult) -> BillSyncResult
)

internal data class SanitizedBillSyncCapture(
    val packageName: String,
    val windowId: Int,
    val pageText: String
)

@Suppress("LongParameterList")
internal fun handoffSanitizedCapture(
    capture: SanitizedBillSyncCapture,
    windowTracker: CaptureWindowTracker,
    recognize: (String, PaymentSource) -> BillSyncResult,
    persist: suspend (BillSyncResult) -> BillSyncResult,
    launch: (suspend () -> Unit) -> Unit,
    onOutcome: (BillSyncCaptureOutcome) -> Unit = {}
) {
    val recognized = recognize(capture.pageText, paymentSourceForPackage(capture.packageName))
    if (!recognized.recognized) {
        windowTracker.release(capture.packageName, capture.windowId)
        BillSyncCaptureLog.processed(recognized = false, insertedCount = 0)
        notifyCaptureOutcome(
            onOutcome,
            BillSyncCaptureOutcome.Failed(
                recognized.failureReason?.code ?: "page_not_recognized"
            )
        )
        return
    }
    if (!windowTracker.acceptIfNew(capture.packageName, capture.windowId)) {
        BillSyncCaptureLog.ignored(
            "already_captured_window",
            "package=${capture.packageName} window=${capture.windowId}"
        )
        notifyCaptureOutcome(
            onOutcome,
            BillSyncCaptureOutcome.NotInserted("already_captured_window")
        )
        return
    }
    launch {
        try {
            val result = persist(recognized)
            windowTracker.persistenceSucceeded(capture.packageName, capture.windowId)
            BillSyncCaptureLog.processed(recognized = true, insertedCount = result.insertedCount)
            notifyCaptureOutcome(
                onOutcome,
                if (result.insertedCount > 0) {
                    BillSyncCaptureOutcome.Inserted(result.insertedCount)
                } else {
                    BillSyncCaptureOutcome.NotInserted("duplicate_not_inserted")
                }
            )
        } catch (error: CancellationException) {
            windowTracker.persistenceCancelled(capture.packageName, capture.windowId)
            notifyCaptureOutcome(onOutcome, BillSyncCaptureOutcome.Failed("session_ended"))
            throw error
        } catch (error: Throwable) {
            windowTracker.persistenceFailed(capture.packageName, capture.windowId)
            BillSyncCaptureLog.failed(capture.packageName, capture.windowId, error)
            notifyCaptureOutcome(onOutcome, BillSyncCaptureOutcome.Failed("persistence_failed"))
        }
    }
}

internal fun paymentSourceForPackage(packageName: String): PaymentSource = when (packageName) {
    ALIPAY_PACKAGE_NAME -> PaymentSource.ALIPAY
    WECHAT_PACKAGE_NAME -> PaymentSource.WECHAT
    else -> PaymentSource.OTHER
}

private fun notifyCaptureOutcome(
    onOutcome: (BillSyncCaptureOutcome) -> Unit,
    outcome: BillSyncCaptureOutcome
) {
    runCatching { onOutcome(outcome) }
}
