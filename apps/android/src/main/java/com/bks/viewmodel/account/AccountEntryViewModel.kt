package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.viewmodel.account.isNetworkAction
import com.bks.viewmodel.account.isReadyFor
import com.bks.model.application.account.WechatAuthCoordinator
import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthPurpose
import com.bks.viewmodel.account.WechatBindMethod
import com.bks.viewmodel.account.WechatLoginController
import com.bks.viewmodel.account.WechatLoginUiState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AccountEntryOperationState(
    val operationInProgress: Boolean = false,
    val smsCountdownSeconds: Int = 0,
    val wechat: WechatLoginUiState = WechatLoginUiState()
)

internal sealed interface AccountEntryOperationResult {
    data object SmsCodeSent : AccountEntryOperationResult
    data object AuthenticationCommitted : AccountEntryOperationResult
    data object Invalidated : AccountEntryOperationResult
    data class Failure(val message: String) : AccountEntryOperationResult
}

internal sealed interface AccountEntryWechatAction {
    data class Start(val agreementAccepted: Boolean) : AccountEntryWechatAction
    data class HandleCallback(val callback: WechatAuthCallback) : AccountEntryWechatAction
    data object ShowBinding : AccountEntryWechatAction
    data class SelectBindMethod(val method: WechatBindMethod) : AccountEntryWechatAction
    data object CreateAccount : AccountEntryWechatAction
    data class RequestBindingSms(val identifier: String) : AccountEntryWechatAction
    data class BindExistingAccount(
        val identifier: String,
        val password: String,
        val code: String
    ) : AccountEntryWechatAction
    data object Back : AccountEntryWechatAction
}

internal class AccountEntryViewModel(
    private val accountRepository: AccountRepository,
    private val commitSession: (AccountCredentials) -> Boolean,
    private val clearPersistedSession: () -> Boolean,
    private val enterLocalMode: () -> Unit,
    wechatAuthGateway: WechatAuthGateway?
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountEntryOperationState())
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    private var smsCountdownJob: Job? = null
    private var accountOperationJob: Job? = null
    private var wechatOperationJob: Job? = null
    private var wechatCallbackJob: Job? = null
    private var entryGeneration = 0L
    private var accountOperationGeneration = 0L
    private var smsContextGeneration = 0L
    private var activeAccountAction: AccountAction? = null
    private var wechatCallbackInFlight = false
    private val wechatController = wechatAuthGateway?.let { gateway ->
        WechatLoginController(
            accountRepository = accountRepository,
            authCoordinator = WechatAuthCoordinator(gateway),
            commitSession = commitSession,
            clearPersistedSession = clearPersistedSession
        )
    }

    val state: StateFlow<AccountEntryOperationState> = mutableState.asStateFlow()
    val notices = noticeChannel.receiveAsFlow()
    val wechatAvailable: Boolean = wechatController != null

    fun submit(action: AccountAction, form: AccountFormState) {
        if (!action.isNetworkAction() || mutableState.value.operationInProgress || !form.isReadyFor(action)) return
        val submittedEntryGeneration = entryGeneration
        val submittedOperationGeneration = ++accountOperationGeneration
        val submittedSmsContextGeneration = smsContextGeneration
        val operationIsCurrent = {
            submittedEntryGeneration == entryGeneration &&
                submittedOperationGeneration == accountOperationGeneration
        }
        activeAccountAction = action
        mutableState.update { it.copy(operationInProgress = true) }
        accountOperationJob = viewModelScope.launch {
            when (
                val result = form.runNetworkAction(
                    action = action,
                    accountRepository = accountRepository,
                    commitSession = commitSession,
                    clearPersistedSession = clearPersistedSession,
                    canCommitSession = operationIsCurrent
                )
            ) {
                AccountEntryOperationResult.Invalidated -> Unit
                AccountEntryOperationResult.AuthenticationCommitted -> {
                    if (!operationIsCurrent()) return@launch
                    smsCountdownJob?.cancel()
                    mutableState.value = AccountEntryOperationState()
                }
                AccountEntryOperationResult.SmsCodeSent -> {
                    if (
                        !operationIsCurrent() ||
                        submittedSmsContextGeneration != smsContextGeneration
                    ) {
                        return@launch
                    }
                    startSmsCountdown()
                }
                is AccountEntryOperationResult.Failure -> {
                    if (!operationIsCurrent()) return@launch
                    mutableState.update { it.copy(operationInProgress = false) }
                    noticeChannel.send(result.message)
                }
            }
            if (submittedOperationGeneration == accountOperationGeneration) {
                activeAccountAction = null
                accountOperationJob = null
            }
        }
    }

    fun enterLocalMode() {
        if (!mutableState.value.operationInProgress) {
            closeEntry()
            enterLocalMode.invoke()
        }
    }

    fun invalidateSmsContext() {
        smsContextGeneration += 1
        if (activeAccountAction == AccountAction.RequestSmsCode) {
            accountOperationGeneration += 1
            accountOperationJob?.cancel()
            accountOperationJob = null
            activeAccountAction = null
            mutableState.update { it.copy(operationInProgress = false) }
        }
        smsCountdownJob?.cancel()
        smsCountdownJob = null
        mutableState.update { it.copy(smsCountdownSeconds = 0) }
    }

    fun closeEntry() {
        entryGeneration += 1
        accountOperationGeneration += 1
        smsContextGeneration += 1
        accountOperationJob?.cancel()
        smsCountdownJob?.cancel()
        wechatOperationJob?.cancel()
        wechatCallbackJob?.cancel()
        accountOperationJob = null
        smsCountdownJob = null
        wechatOperationJob = null
        wechatCallbackJob = null
        activeAccountAction = null
        wechatCallbackInFlight = false
        wechatController?.cancelAuthorization()
        mutableState.value = AccountEntryOperationState()
        while (noticeChannel.tryReceive().isSuccess) {
            // Discard notices from the closed entry before it can be reopened.
        }
    }

    fun onWechatAction(action: AccountEntryWechatAction) {
        when (action) {
            is AccountEntryWechatAction.Start -> updateWechat { controller, state ->
                controller.start(state, action.agreementAccepted)
            }
            is AccountEntryWechatAction.HandleCallback -> handleWechatCallback(action.callback)
            AccountEntryWechatAction.ShowBinding -> updateWechat(WechatLoginController::showBinding)
            is AccountEntryWechatAction.SelectBindMethod -> updateWechat { controller, state ->
                controller.selectBindMethod(state, action.method)
            }
            AccountEntryWechatAction.CreateAccount -> launchWechatOperation { controller, state, canCommit ->
                controller.createWechatAccount(state, canCommit)
            }
            is AccountEntryWechatAction.RequestBindingSms -> launchWechatOperation { controller, state, _ ->
                controller.requestBindingSms(state, action.identifier)
            }
            is AccountEntryWechatAction.BindExistingAccount -> launchWechatOperation { controller, state, canCommit ->
                controller.bindExistingAccount(state, action.identifier, action.password, action.code, canCommit)
            }
            AccountEntryWechatAction.Back -> updateWechat(WechatLoginController::back)
        }
    }

    private fun startSmsCountdown() {
        smsCountdownJob?.cancel()
        mutableState.update { it.copy(operationInProgress = false, smsCountdownSeconds = 60) }
        smsCountdownJob = viewModelScope.launch {
            while (mutableState.value.smsCountdownSeconds > 0) {
                delay(1_000)
                mutableState.update {
                    it.copy(smsCountdownSeconds = (it.smsCountdownSeconds - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun updateWechat(
        operation: (WechatLoginController, WechatLoginUiState) -> WechatLoginUiState
    ) {
        val controller = wechatController ?: return
        publishWechat(operation(controller, mutableState.value.wechat))
    }

    private fun handleWechatCallback(callback: WechatAuthCallback) {
        val controller = wechatController ?: return
        if (!callback.isAccountEntryPurpose() || wechatCallbackInFlight) return
        val submittedEntryGeneration = entryGeneration
        wechatCallbackInFlight = true
        wechatCallbackJob = viewModelScope.launch {
            try {
                val next = controller.handleCallback(mutableState.value.wechat, callback) {
                    submittedEntryGeneration == entryGeneration
                }
                if (submittedEntryGeneration == entryGeneration) publishWechat(next)
            } finally {
                if (submittedEntryGeneration == entryGeneration) {
                    wechatCallbackInFlight = false
                    wechatCallbackJob = null
                }
            }
        }
    }

    private fun launchWechatOperation(
        operation: suspend (
            WechatLoginController,
            WechatLoginUiState,
            () -> Boolean
        ) -> WechatLoginUiState
    ) {
        val controller = wechatController ?: return
        val current = mutableState.value.wechat
        if (current.operationInProgress) return
        mutableState.update {
            it.copy(wechat = current.copy(operationInProgress = true, errorMessage = null))
        }
        val submittedEntryGeneration = entryGeneration
        wechatOperationJob = viewModelScope.launch {
            val next = operation(controller, current) { submittedEntryGeneration == entryGeneration }
            if (submittedEntryGeneration == entryGeneration) {
                publishWechat(next)
                wechatOperationJob = null
            }
        }
    }

    private fun publishWechat(next: WechatLoginUiState) {
        val previousError = mutableState.value.wechat.errorMessage
        mutableState.update { it.copy(wechat = next) }
        next.errorMessage?.takeIf { it != previousError }?.let(noticeChannel::trySend)
    }
}

private fun WechatAuthCallback.isAccountEntryPurpose(): Boolean = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
} == WechatAuthPurpose.SignInOrRegister
