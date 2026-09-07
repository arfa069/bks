package com.bks.model.application.account

import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose
import org.junit.Assert.assertEquals
import org.junit.Test

class WechatAuthCoordinatorTest {
    @Test
    fun agreementIsRequiredBeforeGatewayLaunch() {
        var starts = 0
        val gateway = object : WechatAuthGateway {
            override fun startAuthorization(
                purpose: WechatAuthPurpose,
                sessionFingerprint: String?
            ): WechatAuthLaunchResult {
                starts += 1
                return WechatAuthLaunchResult.Started
            }
        }
        val coordinator = WechatAuthCoordinator(gateway)

        assertEquals(
            WechatAuthLaunchResult.AgreementRequired,
            coordinator.startAuthorization(false, WechatAuthPurpose.SignInOrRegister)
        )
        assertEquals(0, starts)
        assertEquals(
            WechatAuthLaunchResult.Started,
            coordinator.startAuthorization(true, WechatAuthPurpose.SignInOrRegister)
        )
        assertEquals(1, starts)
    }
}
