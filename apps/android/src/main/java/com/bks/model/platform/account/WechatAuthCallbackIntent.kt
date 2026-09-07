package com.bks.model.platform.account

import android.content.Context
import android.content.Intent
import com.bks.model.domain.account.WechatAuthCallback

internal enum class WechatSdkAuthError {
    None,
    Cancelled,
    Denied,
    Other
}

internal data class WechatSdkAuthResponse(
    val code: String?,
    val state: String?,
    val error: WechatSdkAuthError
)

internal class WechatAuthCallbackProcessor(
    private val stateStore: WechatAuthStateStore
) {
    fun process(response: WechatSdkAuthResponse): WechatAuthCallbackDelivery? {
        val request = stateStore.consumeSdkResponse(response.state) ?: return null
        val callback = when (response.error) {
            WechatSdkAuthError.None -> response.code
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, request.purpose, request.sessionFingerprint) }
                ?: WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Cancelled -> WechatAuthCallback.Cancelled(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Denied -> WechatAuthCallback.Denied(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Other -> WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
        }
        return WechatAuthCallbackDelivery(callback, request.relayToken)
    }
}

internal data class WechatAuthCallbackDelivery(
    val callback: WechatAuthCallback,
    val relayToken: String
)

object WechatAuthCallbackIntent {
    const val ACTION = "com.bks.action.WECHAT_AUTH_CALLBACK"
    private const val EXTRA_STATUS = "com.bks.extra.WECHAT_STATUS"
    private const val EXTRA_CODE = "com.bks.extra.WECHAT_CODE"
    private const val EXTRA_RELAY_TOKEN = "com.bks.extra.WECHAT_RELAY_TOKEN"

    internal fun create(context: Context, delivery: WechatAuthCallbackDelivery): Intent {
        val (status, code) = when (val callback = delivery.callback) {
            is WechatAuthCallback.Authorized -> "authorized" to callback.code
            is WechatAuthCallback.Cancelled -> "cancelled" to null
            is WechatAuthCallback.Denied -> "denied" to null
            is WechatAuthCallback.Failed -> "failed" to null
        }
        return Intent(ACTION).setPackage(context.packageName).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_RELAY_TOKEN, delivery.relayToken)
            code?.let { putExtra(EXTRA_CODE, it) }
        }
    }

    fun consume(context: Context, intent: Intent?): WechatAuthCallback? {
        if (intent?.action != ACTION) return null
        val request = WechatAuthStateStore(context.applicationContext)
            .consumeRelay(intent.getStringExtra(EXTRA_RELAY_TOKEN))
            ?: return intent.scrubWechatCallback().let { null }
        val callback = when (intent.getStringExtra(EXTRA_STATUS)) {
            "authorized" -> intent.getStringExtra(EXTRA_CODE)
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, request.purpose, request.sessionFingerprint) }
            "cancelled" -> WechatAuthCallback.Cancelled(request.purpose, request.sessionFingerprint)
            "denied" -> WechatAuthCallback.Denied(request.purpose, request.sessionFingerprint)
            "failed" -> WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
            else -> null
        }
        intent.scrubWechatCallback()
        return callback
    }

    private fun Intent.scrubWechatCallback(): Intent = apply {
        removeExtra(EXTRA_CODE)
        removeExtra(EXTRA_STATUS)
        removeExtra(EXTRA_RELAY_TOKEN)
        action = null
    }
}
