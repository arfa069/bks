package com.bks.model.platform.account

import android.content.Context
import com.bks.model.domain.account.WechatAuthPurpose
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

class WechatAuthStateStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val relayTokenGenerator: () -> String = ::secureWechatState,
    private val stateGenerator: () -> String = ::secureWechatState
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun begin(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): String {
        val state = stateGenerator()
        val relayToken = relayTokenGenerator()
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_PURPOSE, purpose.name)
            .putString(KEY_RELAY_TOKEN, relayToken)
            .putString(KEY_SESSION_FINGERPRINT, sessionFingerprint)
            .putLong(KEY_EXPIRES_AT, clock.millis() + REQUEST_TTL_MILLIS)
            .apply()
        return state
    }

    internal fun consumeSdkResponse(responseState: String?): WechatAuthRequestContext? {
        val expectedState = preferences.getString(KEY_STATE, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (clock.millis() > expiresAt) {
            clear(expectedState)
            return null
        }
        val suppliedState = responseState?.takeIf(String::isNotBlank) ?: return null
        if (!MessageDigest.isEqual(expectedState.toByteArray(), suppliedState.toByteArray())) {
            return null
        }
        val purpose = preferences.getString(KEY_PURPOSE, null)
            ?.let { runCatching { WechatAuthPurpose.valueOf(it) }.getOrNull() }
            ?: return null
        val relayToken = preferences.getString(KEY_RELAY_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val sessionFingerprint = preferences.getString(KEY_SESSION_FINGERPRINT, null)
        preferences.edit().remove(KEY_STATE).apply()
        return WechatAuthRequestContext(purpose, sessionFingerprint, relayToken)
    }

    internal fun consumeRelay(relayToken: String?): WechatAuthRequestContext? {
        val expectedRelayToken = preferences.getString(KEY_RELAY_TOKEN, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (clock.millis() > expiresAt) {
            clearAll()
            return null
        }
        val suppliedRelayToken = relayToken?.takeIf(String::isNotBlank) ?: return null
        if (!MessageDigest.isEqual(expectedRelayToken.toByteArray(), suppliedRelayToken.toByteArray())) {
            return null
        }
        val purpose = preferences.getString(KEY_PURPOSE, null)
            ?.let { runCatching { WechatAuthPurpose.valueOf(it) }.getOrNull() }
            ?: return null
        val context = WechatAuthRequestContext(
            purpose = purpose,
            sessionFingerprint = preferences.getString(KEY_SESSION_FINGERPRINT, null),
            relayToken = expectedRelayToken
        )
        clearAll()
        return context
    }

    fun clear(expectedState: String) {
        val storedState = preferences.getString(KEY_STATE, null) ?: return
        if (MessageDigest.isEqual(storedState.toByteArray(), expectedState.toByteArray())) {
            clearAll()
        }
    }

    fun clear(purpose: WechatAuthPurpose) {
        if (preferences.getString(KEY_PURPOSE, null) == purpose.name) clearAll()
    }

    private fun clearAll() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "wechat_auth_request"
        const val KEY_STATE = "state"
        const val KEY_PURPOSE = "purpose"
        const val KEY_RELAY_TOKEN = "relay_token"
        const val KEY_SESSION_FINGERPRINT = "session_fingerprint"
        const val KEY_EXPIRES_AT = "expires_at"
        const val REQUEST_TTL_MILLIS = 5 * 60_000L
    }
}

internal data class WechatAuthRequestContext(
    val purpose: WechatAuthPurpose,
    val sessionFingerprint: String?,
    val relayToken: String
)

private fun secureWechatState(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
