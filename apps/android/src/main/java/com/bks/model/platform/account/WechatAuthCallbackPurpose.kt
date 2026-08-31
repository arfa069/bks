package com.bks.model.platform.account

fun WechatAuthCallback.managementPurpose(): WechatAuthPurpose = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
}
