package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountIdentifierParser
import com.bks.model.domain.account.AccountIdentifierType

import com.bks.model.application.account.persistAccountSessionOrRevoke
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRepositoryResult
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountVerificationPurpose
import com.bks.model.domain.account.AccountWechatAuthResult
import com.bks.model.application.account.WechatAuthCoordinator
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose
enum class WechatLoginPage {
    Idle,
    Preview,
    BindExisting
}

enum class WechatBindMethod {
    Password,
    Sms
}

data class WechatLoginUiState(
    val page: WechatLoginPage = WechatLoginPage.Idle,
    val wechatTicket: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val bindMethod: WechatBindMethod = WechatBindMethod.Password,
    val operationInProgress: Boolean = false,
    val smsRequested: Boolean = false,
    val errorMessage: String? = null
)

internal class WechatLoginController(
    private val accountRepository: AccountRepository,
    private val authCoordinator: WechatAuthCoordinator,
    private val commitSession: (AccountCredentials) -> Boolean,
    private val clearPersistedSession: () -> Boolean
) {
    fun cancelAuthorization() {
        authCoordinator.cancelAuthorization(WechatAuthPurpose.SignInOrRegister)
    }

    fun start(state: WechatLoginUiState, agreementAccepted: Boolean): WechatLoginUiState {
        if (state.operationInProgress) return state
        return when (
            authCoordinator.startAuthorization(
                agreementAccepted = agreementAccepted,
                purpose = WechatAuthPurpose.SignInOrRegister
            )
        ) {
            WechatAuthLaunchResult.Started -> state.copy(operationInProgress = true, errorMessage = null)
            WechatAuthLaunchResult.AgreementRequired -> state.fail("请先阅读并同意用户协议和隐私政策")
            WechatAuthLaunchResult.NotConfigured -> state.fail("微信登录暂未配置")
            WechatAuthLaunchResult.NotInstalled -> state.fail("未检测到微信，请先安装微信")
            WechatAuthLaunchResult.VersionUnsupported -> state.fail("当前微信版本过低，请升级后重试")
            WechatAuthLaunchResult.SendFailed -> state.fail("无法启动微信授权，请稍后重试")
        }
    }

    suspend fun handleCallback(
        state: WechatLoginUiState,
        callback: WechatAuthCallback,
        canCommitSession: () -> Boolean = { true }
    ): WechatLoginUiState {
        if (callback.purpose() != WechatAuthPurpose.SignInOrRegister) return state
        return when (callback) {
            is WechatAuthCallback.Authorized -> exchange(state, callback.code, canCommitSession)
            is WechatAuthCallback.Cancelled -> state.fail("已取消微信授权")
            is WechatAuthCallback.Denied -> state.fail("微信授权已拒绝")
            is WechatAuthCallback.Failed -> state.fail("微信授权失败，请重试")
        }
    }

    fun showBinding(state: WechatLoginUiState): WechatLoginUiState =
        if (state.wechatTicket != null) state.copy(page = WechatLoginPage.BindExisting, errorMessage = null) else state

    fun selectBindMethod(
        state: WechatLoginUiState,
        method: WechatBindMethod
    ): WechatLoginUiState = if (state.operationInProgress) state else state.copy(
        bindMethod = method,
        errorMessage = null
    )

    suspend fun createWechatAccount(
        state: WechatLoginUiState,
        canCommitSession: () -> Boolean = { true }
    ): WechatLoginUiState {
        val ticket = state.wechatTicket ?: return state.fail("微信授权已失效，请重新授权")
        if (state.operationInProgress) return state
        return finishCredentials(
            state.copy(operationInProgress = true, errorMessage = null),
            accountRepository.registerWithWechat(ticket),
            canCommitSession
        )
    }

    suspend fun requestBindingSms(
        state: WechatLoginUiState,
        identifier: String
    ): WechatLoginUiState {
        val ticket = state.wechatTicket ?: return state.fail("微信授权已失效，请重新授权")
        if (state.operationInProgress) return state
        validateIdentifier(identifier, requireContact = true)?.let { return state.fail(it) }
        return when (
            val result = accountRepository.requestVerificationCode(
                identifier = identifier,
                purpose = AccountVerificationPurpose.WechatLink,
                contextKey = ticket
            )
        ) {
            is AccountRepositoryResult.Success -> state.copy(
                operationInProgress = false,
                smsRequested = true,
                errorMessage = null
            )
            is AccountRepositoryResult.Failure -> state.fail(result.message)
        }
    }

    suspend fun bindExistingAccount(
        state: WechatLoginUiState,
        identifier: String,
        password: String,
        code: String,
        canCommitSession: () -> Boolean = { true }
    ): WechatLoginUiState {
        val ticket = state.wechatTicket ?: return state.fail("微信授权已失效，请重新授权")
        if (state.operationInProgress) return state
        validateIdentifier(identifier, requireContact = state.bindMethod == WechatBindMethod.Sms)?.let {
            return state.fail(it)
        }
        if (state.bindMethod == WechatBindMethod.Password && password.isBlank()) {
            return state.fail("请输入当前密码")
        }
        if (state.bindMethod == WechatBindMethod.Sms && code.length != 6) {
            return state.fail("请输入 6 位验证码")
        }
        val result = when (state.bindMethod) {
            WechatBindMethod.Password -> accountRepository.linkWechatWithPassword(ticket, identifier, password)
            WechatBindMethod.Sms -> accountRepository.linkWechatWithCode(ticket, identifier, code)
        }
        return finishCredentials(
            state.copy(operationInProgress = true, errorMessage = null),
            result,
            canCommitSession
        )
    }

    fun back(state: WechatLoginUiState): WechatLoginUiState {
        if (state.operationInProgress) return state
        return when (state.page) {
            WechatLoginPage.BindExisting -> state.copy(page = WechatLoginPage.Preview, errorMessage = null)
            WechatLoginPage.Preview -> WechatLoginUiState()
            WechatLoginPage.Idle -> state
        }
    }

    private suspend fun exchange(
        state: WechatLoginUiState,
        code: String,
        canCommitSession: () -> Boolean
    ): WechatLoginUiState {
        return when (val result = accountRepository.exchangeWechatCode(code)) {
            is AccountRepositoryResult.Failure -> state.fail(result.message)
            is AccountRepositoryResult.Success -> when (val auth = result.value) {
                is AccountWechatAuthResult.SignedIn -> finishCredentials(
                    state.copy(operationInProgress = true, errorMessage = null),
                    AccountRepositoryResult.Success(auth.credentials),
                    canCommitSession
                )
                is AccountWechatAuthResult.RegistrationRequired -> WechatLoginUiState(
                    page = WechatLoginPage.Preview,
                    wechatTicket = auth.wechatTicket,
                    nickname = auth.nickname ?: "微信用户",
                    avatarUrl = auth.avatarUrl
                )
                is AccountWechatAuthResult.MergeRequired -> state.fail("该微信已绑定其他账号，请先登录后再合并")
            }
        }
    }

    private suspend fun finishCredentials(
        state: WechatLoginUiState,
        result: AccountRepositoryResult<AccountCredentials>,
        canCommitSession: () -> Boolean
    ): WechatLoginUiState = when (result) {
        is AccountRepositoryResult.Failure -> state.fail(result.message)
        is AccountRepositoryResult.Success -> {
            if (!canCommitSession()) return state.copy(operationInProgress = false)
            if (
                persistAccountSessionOrRevoke(
                    credentials = result.value,
                    accountRepository = accountRepository,
                    persistSession = commitSession,
                    clearPersistedSession = clearPersistedSession
                )
            ) {
                WechatLoginUiState()
            } else {
                state.fail("无法安全保存登录状态，已撤销新会话并切换到本地模式")
            }
        }
    }

    private fun validateIdentifier(identifier: String, requireContact: Boolean): String? {
        val parsed = runCatching { AccountIdentifierParser.parse(identifier) }.getOrNull()
        if (parsed != null && (!requireContact || parsed.type != AccountIdentifierType.USERNAME)) {
            return null
        }
        return if (requireContact) "请输入有效的手机号或邮箱" else "请输入有效的用户名、邮箱或手机号"
    }
}

private fun WechatLoginUiState.fail(message: String): WechatLoginUiState = copy(
    operationInProgress = false,
    errorMessage = message
)

private fun WechatAuthCallback.purpose(): WechatAuthPurpose = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
}
