package com.bks.model.application.account

import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose

class WechatAuthCoordinator(
    private val gateway: WechatAuthGateway
) {
    fun startAuthorization(
        agreementAccepted: Boolean,
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): WechatAuthLaunchResult {
        if (!agreementAccepted) return WechatAuthLaunchResult.AgreementRequired
        return gateway.startAuthorization(purpose, sessionFingerprint)
    }

    fun cancelAuthorization(purpose: WechatAuthPurpose) {
        gateway.cancelAuthorization(purpose)
    }
}
