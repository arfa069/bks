package com.bks.view.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.bks.model.platform.app.bksAppContainer
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthCallbackIntent
import com.bks.view.common.platform.BillSyncStateCoordinator
import com.bks.model.platform.notifications.ReviewNotificationIntents
class MainActivity : ComponentActivity() {
    private val coordinator by lazy {
        BillSyncStateCoordinator(
            activity = this,
            diagnosticRecorder = bksAppContainer.diagnosticLogs,
            healthSnapshot = bksAppContainer.billSyncCaptureCoordinator::healthSnapshot
        )
    }
    private val pendingWechatAuthCallback = mutableStateOf<WechatAuthCallback?>(null)
    private val requestedReviewEntryId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedReviewEntryId.value = savedInstanceState?.getString("notification_review_entry")
        handleNavigationIntent(intent)
        coordinator.onCreate()
        setContent {
            BksApp(
                bindings = BksAppBindings(
                    billSyncAccessibilityAccessGranted = coordinator.billSyncAccessibilityAccessGranted.value,
                    billSyncAccessibilityServiceConnected = coordinator.billSyncAccessibilityServiceConnected.value,
                    billSyncBatteryOptimizationIgnored =
                        coordinator.billSyncBatteryOptimizationIgnored.value,
                    onOpenBillSyncAccessibilitySettings = coordinator::openBillSyncAccessibilitySettings,
                    onOpenBillSyncAutoStartSettings = coordinator::openBillSyncAutoStartSettings,
                    onOpenBillSyncPowerPolicySettings = coordinator::openBillSyncPowerPolicySettings,
                    wechatAuthCallback = pendingWechatAuthCallback.value,
                    onWechatAuthCallbackConsumed = { pendingWechatAuthCallback.value = null },
                    requestedReviewEntryId = requestedReviewEntryId.value,
                    onReviewNotificationConsumed = { requestedReviewEntryId.value = null }
                )
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        requestedReviewEntryId.value?.let { outState.putString("notification_review_entry", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        coordinator.onResume()
    }

    override fun onStop() {
        coordinator.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        coordinator.onDestroy()
        super.onDestroy()
    }

    private fun handleNavigationIntent(intent: Intent?) {
        ReviewNotificationIntents.consume(this, intent)?.let { requestedReviewEntryId.value = it }
        WechatAuthCallbackIntent.consume(this, intent)?.let { callback ->
            pendingWechatAuthCallback.value = callback
        }
    }
}
