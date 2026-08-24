package com.bks.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.api.AccountIdentifierParser
import com.bks.api.AccountIdentifierTypeContract
import com.bks.api.IdentifierLinkPrepareResponseContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AccountManagementOperationState(
    val accountOperationInProgress: Boolean = false,
    val accountError: String? = null,
    val identityOperationInProgress: Boolean = false,
    val identityError: String? = null,
    val identityPage: AccountIdentityPage = AccountIdentityPage.Idle,
    val phoneTicket: String? = null,
    val mergeTicket: String? = null,
    val sourceIdentifiers: List<com.bks.api.AccountIdentifierContract> = emptyList(),
    val sourceNickname: String? = null,
    val sourceWechatLinked: Boolean = false,
    val avatarError: String? = null,
    val draftResetGeneration: Long = 0L
)

@Suppress("LongParameterList", "TooManyFunctions")
internal class AccountManagementViewModel(
    private val accountRepository: AccountRepository,
    private val commitManagementCredentials:
        (String, AccountCredentials) -> AccountSessionMutationResult,
    private val commitVerifiedCredentials:
        (String, AccountCredentials) -> AccountSessionMutationResult,
    private val invalidateSession: () -> Boolean,
    private val updateDeletionState:
        (String, AccountDeletionUiState) -> AccountSessionMutationResult,
    wechatAuthGateway: WechatAuthGateway?
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountManagementOperationState())
    private val authCoordinator = wechatAuthGateway?.let(::WechatAuthCoordinator)
    private var accountOperationJob: Job? = null
    private var identityOperationJob: Job? = null
    private var managementGeneration = 0L
    private var accountOperationGeneration = 0L
    private var identityOperationGeneration = 0L
    private var activeSessionToken: String? = null

    val state: StateFlow<AccountManagementOperationState> = mutableState.asStateFlow()
    val wechatAvailable: Boolean = authCoordinator != null

    fun activate(session: AccountSession) {
        val token = (session as? AccountSession.SignedIn)?.token
        if (activeSessionToken == token) return
        if (activeSessionToken == null) {
            activeSessionToken = token
            return
        }
        closeManagement()
        activeSessionToken = token
    }

    fun retryVerification(session: AccountSession.SignedIn, deletionState: AccountDeletionUiState) {
        launchAccountOperation(session.token) {
            when (val result = accountRepository.verifySession(session.toCredentials(deletionState))) {
                is AccountRepositoryResult.Success -> {
                    if (!isCurrent()) return@launchAccountOperation
                    when (commitVerifiedCredentials(session.token, result.value)) {
                        AccountSessionMutationResult.Applied -> mutableState.update {
                            it.copy(accountOperationInProgress = false, accountError = null)
                        }
                        AccountSessionMutationResult.PersistenceFailed -> mutableState.update {
                            it.copy(
                                accountOperationInProgress = false,
                                accountError = "账号资料已验证，但无法保存到本机，请重试"
                            )
                        }
                        AccountSessionMutationResult.StaleSession -> finishStaleOperation()
                    }
                }
                is AccountRepositoryResult.Failure -> handleAccountFailure(result)
            }
        }
    }

    fun signOut(session: AccountSession.SignedIn) {
        launchAccountOperation(session.token) {
            when (val result = accountRepository.signOut(session.token)) {
                is AccountRepositoryResult.Success -> {
                    if (!isCurrent()) return@launchAccountOperation
                    if (invalidateSession()) {
                        finishAndInvalidate()
                    } else {
                        mutableState.update {
                            it.copy(
                                accountOperationInProgress = false,
                                accountError = "服务端会话已撤销，但本机登录状态清理失败，请重启应用"
                            )
                        }
                    }
                }
                is AccountRepositoryResult.Failure -> handleAccountFailure(result)
            }
        }
    }

    fun requestDeletion(session: AccountSession.SignedIn) {
        launchAccountOperation(session.token) {
            when (val result = accountRepository.requestDeletion(session.token)) {
                is AccountRepositoryResult.Success -> if (isCurrent()) {
                    handleDeletionStateMutation(session.token, result.value)
                }
                is AccountRepositoryResult.Failure -> handleAccountFailure(result)
            }
        }
    }

    fun cancelDeletion(session: AccountSession.SignedIn) {
        launchAccountOperation(session.token) {
            when (val result = accountRepository.cancelDeletion(session.token)) {
                is AccountRepositoryResult.Success -> if (isCurrent()) {
                    handleDeletionStateMutation(session.token, AccountDeletionUiState())
                }
                is AccountRepositoryResult.Failure -> handleAccountFailure(result)
            }
        }
    }

    fun openIdentityPage(page: AccountIdentityPage) {
        invalidateIdentityOperation()
        mutableState.update {
            it.copy(
                identityPage = page,
                identityError = null,
                phoneTicket = null,
                mergeTicket = null,
                sourceIdentifiers = emptyList(),
                sourceNickname = null,
                sourceWechatLinked = false
            )
        }
    }

    fun closeIdentityPage() {
        invalidateIdentityOperation()
        resetIdentityState()
    }

    fun identityContextChanged(clearTicket: Boolean = true) {
        invalidateIdentityOperation()
        mutableState.update {
            it.copy(
                identityOperationInProgress = false,
                identityError = null,
                phoneTicket = if (clearTicket) null else it.phoneTicket
            )
        }
    }

    fun startWechatLink(session: AccountSession.SignedIn) {
        if (mutableState.value.anyOperationInProgress) return
        val coordinator = authCoordinator ?: return
        when (
            coordinator.startAuthorization(
                agreementAccepted = true,
                purpose = WechatAuthPurpose.LinkCurrentAccount,
                sessionFingerprint = wechatSessionFingerprint(session.token)
            )
        ) {
            WechatAuthLaunchResult.Started -> mutableState.update {
                it.copy(identityOperationInProgress = true, identityError = null)
            }
            WechatAuthLaunchResult.NotInstalled -> failIdentity("未检测到微信，请先安装微信")
            WechatAuthLaunchResult.VersionUnsupported -> failIdentity("当前微信版本过低，请升级后重试")
            WechatAuthLaunchResult.NotConfigured -> failIdentity("微信登录暂未配置")
            WechatAuthLaunchResult.SendFailed -> failIdentity("无法启动微信授权，请稍后重试")
            WechatAuthLaunchResult.AgreementRequired -> failIdentity("请先同意用户协议和隐私政策")
        }
    }

    fun handleWechatCallback(session: AccountSession.SignedIn, callback: WechatAuthCallback) {
        if (callback.managementPurpose() != WechatAuthPurpose.LinkCurrentAccount) return
        if (!callback.matchesSession(session.token)) {
            failIdentity("登录状态已变化，请重新发起微信授权")
            return
        }
        when (callback) {
            is WechatAuthCallback.Authorized -> launchIdentityOperation(session.token, allowAlreadyStarted = true) {
                when (val result = accountRepository.exchangeWechatCode(callback.code, session.token)) {
                    is AccountRepositoryResult.Failure -> handleIdentityFailure(result)
                    is AccountRepositoryResult.Success -> when (val auth = result.value) {
                        is AccountWechatAuthResult.SignedIn -> commitCredentials(auth.credentials)
                        is AccountWechatAuthResult.MergeRequired -> if (isCurrent()) {
                            mutableState.update {
                                it.copy(
                                    identityOperationInProgress = false,
                                    identityError = null,
                                    identityPage = AccountIdentityPage.Merge,
                                    mergeTicket = auth.mergeTicket,
                                    sourceIdentifiers = auth.sourceIdentifiers,
                                    sourceNickname = auth.sourceNickname,
                                    sourceWechatLinked = true
                                )
                            }
                        }
                        is AccountWechatAuthResult.RegistrationRequired -> {
                            failIdentity("微信绑定状态异常，请重新授权")
                        }
                    }
                }
            }
            is WechatAuthCallback.Cancelled -> failIdentity("已取消微信授权")
            is WechatAuthCallback.Denied -> failIdentity("微信授权已拒绝")
            is WechatAuthCallback.Failed -> failIdentity("微信授权失败，请重试")
        }
    }

    fun updateNickname(
        session: AccountSession.SignedIn,
        deletionState: AccountDeletionUiState,
        nickname: String
    ) {
        launchIdentityOperation(session.token) {
            handleCredentials(accountRepository.updateNickname(session.toCredentials(deletionState), nickname))
        }
    }

    fun updateAvatar(
        session: AccountSession.SignedIn,
        deletionState: AccountDeletionUiState,
        avatarDataUrl: String
    ) {
        mutableState.update { it.copy(avatarError = null) }
        launchIdentityOperation(session.token, allowAlreadyStarted = true) {
            when (val result = accountRepository.updateAvatar(session.toCredentials(deletionState), avatarDataUrl)) {
                is AccountRepositoryResult.Success -> commitCredentials(result.value)
                is AccountRepositoryResult.Failure -> {
                    if (isCurrent()) mutableState.update { it.copy(avatarError = result.message) }
                    handleIdentityFailure(result)
                }
            }
        }
    }

    fun beginAvatarRead() {
        if (mutableState.value.anyOperationInProgress) return
        mutableState.update {
            it.copy(identityOperationInProgress = true, identityError = null, avatarError = null)
        }
    }

    fun cancelAvatarRead() {
        if (identityOperationJob == null) {
            mutableState.update { it.copy(identityOperationInProgress = false) }
        }
    }

    fun reportAvatarReadFailure(message: String) {
        mutableState.update {
            it.copy(identityOperationInProgress = false, identityError = message, avatarError = message)
        }
    }

    fun reportIdentityFailure(message: String) {
        failIdentity(message)
    }

    fun clearIdentityError() {
        mutableState.update { it.copy(identityError = null) }
    }

    fun prepareIdentifierLink(session: AccountSession.SignedIn, state: AccountIdentityUiState) {
        if (!state.hasValidIdentifier()) return failIdentity(state.expectedIdentifierError())
        val identifier = state.phone
        val submittedContext = identityOperationGeneration
        launchIdentityOperation(session.token) {
            when (
                val result = accountRepository.prepareIdentifierLink(
                    session.token,
                    identifier,
                    state.replaceExistingIdentifier
                )
            ) {
                is AccountRepositoryResult.Success -> if (isCurrent() && submittedContext + 1 == identityOperationGeneration) {
                    mutableState.update {
                        when (val prepared = result.value) {
                            IdentifierLinkPrepareResponseContract.AlreadyLinked -> identityReset(it)
                            is IdentifierLinkPrepareResponseContract.LinkTicketIssued -> it.copy(
                                identityOperationInProgress = false,
                                identityError = null,
                                phoneTicket = prepared.linkTicket
                            )
                            is IdentifierLinkPrepareResponseContract.MergeRequired -> it.copy(
                                identityOperationInProgress = false,
                                identityError = "该标识已属于其他账号，不能绑定或合并",
                                phoneTicket = null
                            )
                        }
                    }
                }
                is AccountRepositoryResult.Failure -> handleIdentityFailure(result)
            }
        }
    }

    fun confirmIdentifier(session: AccountSession.SignedIn, state: AccountIdentityUiState) {
        if (!state.hasValidIdentifier()) return failIdentity(state.expectedIdentifierError())
        if (state.identifierAttachMethod == IdentifierAttachMethod.PasswordMerge) {
            launchIdentityOperation(session.token) {
                when (
                    val result = accountRepository.prepareMergeWithIdentifierPassword(
                        session.token,
                        state.phone,
                        state.password
                    )
                ) {
                    is AccountRepositoryResult.Success -> if (isCurrent()) {
                        val merge = result.value.toMergeState()
                        mutableState.update {
                            it.copy(
                                identityOperationInProgress = false,
                                identityError = null,
                                identityPage = AccountIdentityPage.Merge,
                                mergeTicket = merge.mergeTicket,
                                sourceIdentifiers = merge.sourceIdentifiers,
                                sourceNickname = merge.sourceNickname,
                                sourceWechatLinked = merge.sourceWechatLinked
                            )
                        }
                    }
                    is AccountRepositoryResult.Failure -> handleIdentityFailure(result)
                }
            }
            return
        }

        val ticket = mutableState.value.phoneTicket ?: return failIdentity("请先获取验证码")
        if (session.identifiers.isEmpty() && session.wechatLinked) {
            mutableState.update {
                it.copy(
                    identityPage = AccountIdentityPage.SetPhonePassword,
                    identityOperationInProgress = false,
                    identityError = null
                )
            }
        } else {
            launchIdentityOperation(session.token) {
                handleCredentials(accountRepository.completeIdentifierLink(session.token, ticket, state.code))
            }
        }
    }

    fun confirmPhonePassword(session: AccountSession.SignedIn, state: AccountIdentityUiState) {
        val ticket = mutableState.value.phoneTicket ?: return failIdentity("绑定票据已失效")
        launchIdentityOperation(session.token) {
            handleCredentials(
                accountRepository.completeIdentifierLink(
                    token = session.token,
                    linkTicket = ticket,
                    code = state.code,
                    password = state.password
                )
            )
        }
    }

    fun confirmMerge(session: AccountSession.SignedIn, confirmText: String) {
        val ticket = mutableState.value.mergeTicket ?: return failIdentity("合并票据已失效")
        launchIdentityOperation(session.token) {
            handleCredentials(accountRepository.confirmMerge(session.token, ticket, confirmText))
        }
    }

    fun requestUnlinkCode(session: AccountSession.SignedIn, identifier: String) {
        if (identifier.isBlank()) return failIdentity("请选择手机号或邮箱")
        launchIdentityOperation(session.token) {
            when (
                val result = accountRepository.requestVerificationCode(
                    identifier = identifier,
                    purpose = AccountVerificationPurpose.WechatUnlink,
                    bearerToken = session.token
                )
            ) {
                is AccountRepositoryResult.Success -> if (isCurrent()) {
                    mutableState.update { it.copy(identityOperationInProgress = false, identityError = null) }
                }
                is AccountRepositoryResult.Failure -> handleIdentityFailure(result)
            }
        }
    }

    fun unlinkWechat(session: AccountSession.SignedIn, state: AccountIdentityUiState) {
        launchIdentityOperation(session.token) {
            val result = when (state.unlinkMethod) {
                UnlinkMethod.Password -> accountRepository.unlinkWechatWithPassword(session.token, state.password)
                UnlinkMethod.Code -> accountRepository.unlinkWechatWithCode(
                    session.token,
                    state.unlinkIdentifier,
                    state.code
                )
            }
            handleCredentials(result)
        }
    }

    fun closeManagement() {
        managementGeneration += 1
        accountOperationGeneration += 1
        identityOperationGeneration += 1
        accountOperationJob?.cancel()
        identityOperationJob?.cancel()
        accountOperationJob = null
        identityOperationJob = null
        authCoordinator?.cancelAuthorization(WechatAuthPurpose.LinkCurrentAccount)
        mutableState.value = AccountManagementOperationState(
            draftResetGeneration = mutableState.value.draftResetGeneration + 1
        )
        activeSessionToken = null
    }

    private fun launchAccountOperation(
        token: String,
        operation: suspend OperationContext.() -> Unit
    ) {
        if (mutableState.value.anyOperationInProgress || !acceptSession(token)) return
        val context = OperationContext(managementGeneration, ++accountOperationGeneration, true)
        mutableState.update { it.copy(accountOperationInProgress = true, accountError = null) }
        accountOperationJob = viewModelScope.launch {
            context.operation()
            if (context.isCurrent()) accountOperationJob = null
        }
    }

    private fun launchIdentityOperation(
        token: String,
        allowAlreadyStarted: Boolean = false,
        operation: suspend OperationContext.() -> Unit
    ) {
        val state = mutableState.value
        val identityLaneUnavailable = !allowAlreadyStarted && state.identityOperationInProgress
        if (state.accountOperationInProgress || identityLaneUnavailable || !acceptSession(token)) {
            return
        }
        val context = OperationContext(managementGeneration, ++identityOperationGeneration, false)
        mutableState.update { it.copy(identityOperationInProgress = true, identityError = null) }
        identityOperationJob = viewModelScope.launch {
            context.operation()
            if (context.isCurrent()) identityOperationJob = null
        }
    }

    private suspend fun OperationContext.handleCredentials(
        result: AccountRepositoryResult<AccountCredentials>
    ) {
        when (result) {
            is AccountRepositoryResult.Success -> commitCredentials(result.value)
            is AccountRepositoryResult.Failure -> handleIdentityFailure(result)
        }
    }

    private suspend fun OperationContext.commitCredentials(
        credentials: AccountCredentials
    ) {
        if (!isCurrent()) return
        when (commitManagementCredentials(activeSessionToken ?: return, credentials)) {
            AccountSessionMutationResult.Applied -> mutableState.update(::identityReset)
            AccountSessionMutationResult.PersistenceFailed -> {
                if (!invalidateSession()) {
                    mutableState.update {
                        identityReset(it).copy(
                            identityError = "无法安全保存新会话，且本机登录状态清理失败，请重启应用"
                        )
                    }
                    return
                }
                activeSessionToken = null
                mutableState.update {
                    identityReset(it).copy(identityError = "无法安全保存新会话，已切换到本地模式")
                }
                accountRepository.signOut(credentials.token)
            }
            AccountSessionMutationResult.StaleSession -> finishStaleOperation()
        }
    }

    private fun OperationContext.handleAccountFailure(failure: AccountRepositoryResult.Failure) {
        if (!isCurrent()) return
        if (failure.kind == AccountFailureKind.InvalidSession) {
            invalidateFailedSession(accountOperation = true)
        } else {
            mutableState.update {
                it.copy(accountOperationInProgress = false, accountError = failure.message)
            }
        }
    }

    private fun OperationContext.handleIdentityFailure(failure: AccountRepositoryResult.Failure) {
        if (!isCurrent()) return
        if (failure.kind == AccountFailureKind.InvalidSession) {
            invalidateFailedSession(accountOperation = false)
        } else {
            failIdentity(failure.message)
        }
    }

    private fun failIdentity(message: String) {
        mutableState.update {
            it.copy(identityOperationInProgress = false, identityError = message)
        }
    }

    private fun handleDeletionStateMutation(
        expectedSessionToken: String,
        deletionState: AccountDeletionUiState
    ) {
        when (updateDeletionState(expectedSessionToken, deletionState)) {
            AccountSessionMutationResult.Applied -> mutableState.update {
                it.copy(accountOperationInProgress = false, accountError = null)
            }
            AccountSessionMutationResult.PersistenceFailed -> mutableState.update {
                it.copy(
                    accountOperationInProgress = false,
                    accountError = "服务器状态已更新，但无法保存到本机，请重新验证账号状态"
                )
            }
            AccountSessionMutationResult.StaleSession -> finishStaleOperation()
        }
    }

    private fun invalidateFailedSession(accountOperation: Boolean) {
        if (invalidateSession()) {
            finishAndInvalidate()
            return
        }
        if (accountOperation) {
            mutableState.update {
                it.copy(
                    accountOperationInProgress = false,
                    accountError = "登录已失效，但本机登录状态清理失败，请重启应用"
                )
            }
        } else {
            mutableState.update {
                identityReset(it).copy(
                    identityError = "登录已失效，但本机登录状态清理失败，请重启应用"
                )
            }
        }
    }

    private fun finishStaleOperation() {
        mutableState.update {
            it.copy(accountOperationInProgress = false, identityOperationInProgress = false)
        }
    }

    private fun resetIdentityState() {
        mutableState.update(::identityReset)
    }

    private fun identityReset(state: AccountManagementOperationState): AccountManagementOperationState =
        state.copy(
            identityOperationInProgress = false,
            identityError = null,
            identityPage = AccountIdentityPage.Idle,
            phoneTicket = null,
            mergeTicket = null,
            sourceIdentifiers = emptyList(),
            sourceNickname = null,
            sourceWechatLinked = false,
            avatarError = null,
            draftResetGeneration = state.draftResetGeneration + 1
        )

    private fun invalidateIdentityOperation() {
        identityOperationGeneration += 1
        identityOperationJob?.cancel()
        identityOperationJob = null
    }

    private fun finishAndInvalidate() {
        managementGeneration += 1
        accountOperationGeneration += 1
        identityOperationGeneration += 1
        accountOperationJob?.cancel()
        identityOperationJob?.cancel()
        accountOperationJob = null
        identityOperationJob = null
        activeSessionToken = null
        mutableState.update {
            it.copy(accountOperationInProgress = false, identityOperationInProgress = false)
        }
    }

    private fun acceptSession(token: String): Boolean {
        if (activeSessionToken == null) activeSessionToken = token
        return activeSessionToken == token
    }

    private inner class OperationContext(
        private val submittedManagementGeneration: Long,
        private val submittedOperationGeneration: Long,
        private val accountOperation: Boolean
    ) {
        fun isCurrent(): Boolean =
            submittedManagementGeneration == managementGeneration &&
                submittedOperationGeneration == if (accountOperation) {
                    accountOperationGeneration
                } else {
                    identityOperationGeneration
                }
    }
}

private fun AccountIdentityUiState.hasValidIdentifier(): Boolean {
    val parsedType = runCatching { AccountIdentifierParser.parse(phone).type }.getOrNull() ?: return false
    if (parsedType == AccountIdentifierTypeContract.USERNAME) return false
    return targetIdentifierType?.let { it == parsedType } ?: true
}

private val AccountManagementOperationState.anyOperationInProgress: Boolean
    get() = accountOperationInProgress || identityOperationInProgress
