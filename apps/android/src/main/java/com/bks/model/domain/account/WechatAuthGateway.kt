package com.bks.model.domain.account

import java.security.MessageDigest
import java.util.Base64

enum class WechatAuthPurpose {
    SignInOrRegister,
    LinkCurrentAccount
}

sealed interface WechatAuthLaunchResult {
    data object Started : WechatAuthLaunchResult
    data object AgreementRequired : WechatAuthLaunchResult
    data object NotConfigured : WechatAuthLaunchResult
    data object NotInstalled : WechatAuthLaunchResult
    data object VersionUnsupported : WechatAuthLaunchResult
    data object SendFailed : WechatAuthLaunchResult
}

sealed interface WechatAuthCallback {
    val purpose: WechatAuthPurpose
    val sessionFingerprint: String?

    data class Authorized(
        val code: String,
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Cancelled(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Denied(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Failed(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback
}

interface WechatAuthGateway {
    fun startAuthorization(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): WechatAuthLaunchResult

    fun cancelAuthorization(purpose: WechatAuthPurpose) = Unit
}

internal fun wechatSessionFingerprint(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun WechatAuthCallback.matchesSession(token: String): Boolean {
    val expected = sessionFingerprint ?: return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        wechatSessionFingerprint(token).toByteArray(Charsets.UTF_8)
    )
}
