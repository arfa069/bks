package com.bks.model.platform.billsync

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.bks.model.platform.app.bksAppContainer
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class BillSyncAccessibilityService : AssistsService() {
    private val captureCoordinator by lazy { bksAppContainer.billSyncCaptureCoordinator }
    private val backfillScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backfillOcr by lazy { BillBackfillOcr(this) }
    private val backfillRuntime by lazy {
        BillBackfillCaptureRuntime(
            coordinator = bksAppContainer.billBackfillCoordinator,
            scope = backfillScope,
            readPage = {
                val session = bksAppContainer.billBackfillCoordinator.state.value
                rootInActiveWindow?.takeIf {
                    session.isRunning && backfillOcr.screenReady() &&
                        it.packageName?.toString() == session.source.backfillPackageName()
                }?.let { root ->
                    BackfillPageSnapshot(
                        packageName = root.packageName?.toString().orEmpty(),
                        windowId = root.windowId,
                        text = root.collectReadableText(),
                        safeForOcr = !root.hasSensitiveBackfillInput()
                    )
                }
            },
            recognizeScreenshot = backfillOcr::recognize
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        captureCoordinator.onServiceConnected()
        backfillRuntime.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val callbackEnterAtEpochMillis = System.currentTimeMillis()
        val callbackEnterUptimeMillis = SystemClock.uptimeMillis()
        super.onAccessibilityEvent(event)
        backfillRuntime.pageChanged()
        val superReturnUptimeMillis = SystemClock.uptimeMillis()
        captureCoordinator.onAccessibilityEvent(
            event = event,
            callbackTiming = BillSyncAccessibilityCallbackTiming(
                callbackEnterAtEpochMillis = callbackEnterAtEpochMillis,
                callbackEnterUptimeMillis = callbackEnterUptimeMillis,
                superReturnUptimeMillis = superReturnUptimeMillis
            )
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        backfillRuntime.stop()
        captureCoordinator.onUnbind()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        backfillRuntime.stop()
        backfillScope.cancel()
        captureCoordinator.onServiceDestroyed()
        super.onDestroy()
    }
}
