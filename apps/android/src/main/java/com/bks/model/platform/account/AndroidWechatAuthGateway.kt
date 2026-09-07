package com.bks.model.platform.account

import android.content.Context
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose
import com.tencent.mm.opensdk.constants.Build
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.WXAPIFactory

class AndroidWechatAuthGateway(
    context: Context,
    private val appId: String,
    private val stateStore: WechatAuthStateStore = WechatAuthStateStore(context.applicationContext)
) : WechatAuthGateway {
    private val applicationContext = context.applicationContext

    override fun startAuthorization(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String?
    ): WechatAuthLaunchResult {
        if (appId.isBlank()) return WechatAuthLaunchResult.NotConfigured

        // OpenSDK is created and registered only for an explicit, agreement-approved request.
        val api = WXAPIFactory.createWXAPI(applicationContext, appId, false)
        if (!api.registerApp(appId)) return WechatAuthLaunchResult.SendFailed
        if (!api.isWXAppInstalled) return WechatAuthLaunchResult.NotInstalled
        if (api.wxAppSupportAPI < Build.OPENID_SUPPORTED_SDK_INT) {
            return WechatAuthLaunchResult.VersionUnsupported
        }

        val requestState = stateStore.begin(purpose, sessionFingerprint)
        val request = SendAuth.Req().apply {
            scope = WECHAT_AUTH_SCOPE
            state = requestState
        }
        if (!api.sendReq(request)) {
            stateStore.clear(requestState)
            return WechatAuthLaunchResult.SendFailed
        }
        return WechatAuthLaunchResult.Started
    }

    override fun cancelAuthorization(purpose: WechatAuthPurpose) {
        stateStore.clear(purpose)
    }

    private companion object {
        const val WECHAT_AUTH_SCOPE = "snsapi_userinfo"
    }
}
