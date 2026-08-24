package com.bks.feature.account

internal fun AccountAction.isNetworkAction(): Boolean = when (this) {
    AccountAction.RequestSmsCode,
    AccountAction.SubmitLogin,
    AccountAction.SubmitRegister,
    AccountAction.SubmitRecovery -> true

    else -> false
}

internal fun AccountUiState.isReadyFor(action: AccountAction): Boolean = when (action) {
    AccountAction.RequestSmsCode -> phoneError == null
    AccountAction.SubmitLogin -> phoneError == null && errorMessage == null
    AccountAction.SubmitRegister,
    AccountAction.SubmitRecovery -> hasNoFieldErrors && errorMessage == null

    else -> true
}

internal suspend fun AccountUiState.runNetworkAction(
    action: AccountAction,
    accountRepository: AccountRepository,
    commitSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean,
    canCommitSession: () -> Boolean
): AccountEntryOperationResult = when (action) {
    AccountAction.RequestSmsCode -> submitSmsCodeRequest(accountRepository)
    AccountAction.SubmitLogin -> completeAuthentication(
        result = accountRepository.login(phone, password),
        accountRepository = accountRepository,
        commitSession = commitSession,
        clearPersistedSession = clearPersistedSession,
        canCommitSession = canCommitSession
    )
    AccountAction.SubmitRegister -> completeAuthentication(
        result = accountRepository.register(phone, verificationCode, password),
        accountRepository = accountRepository,
        commitSession = commitSession,
        clearPersistedSession = clearPersistedSession,
        canCommitSession = canCommitSession
    )
    AccountAction.SubmitRecovery -> completeAuthentication(
        result = accountRepository.recoverPassword(phone, verificationCode, password),
        accountRepository = accountRepository,
        commitSession = commitSession,
        clearPersistedSession = clearPersistedSession,
        canCommitSession = canCommitSession
    )
    else -> AccountEntryOperationResult.Failure("当前操作不可用")
}

private suspend fun AccountUiState.submitSmsCodeRequest(
    accountRepository: AccountRepository
): AccountEntryOperationResult {
    val purpose = when (flow) {
        AccountFlow.Register -> AccountVerificationPurpose.Register
        AccountFlow.Recovery -> AccountVerificationPurpose.Recovery
        else -> return AccountEntryOperationResult.Failure("当前页面不能获取验证码")
    }
    return when (val result = accountRepository.requestVerificationCode(phone, purpose)) {
        is AccountRepositoryResult.Success -> AccountEntryOperationResult.SmsCodeSent
        is AccountRepositoryResult.Failure -> AccountEntryOperationResult.Failure(result.message)
    }
}

private suspend fun completeAuthentication(
    result: AccountRepositoryResult<AccountCredentials>,
    accountRepository: AccountRepository,
    commitSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean,
    canCommitSession: () -> Boolean
): AccountEntryOperationResult = when (result) {
    is AccountRepositoryResult.Success -> {
        if (!canCommitSession()) {
            AccountEntryOperationResult.Invalidated
        } else if (
            persistAccountSessionOrRevoke(
                credentials = result.value,
                accountRepository = accountRepository,
                persistSession = commitSession,
                clearPersistedSession = clearPersistedSession
            )
        ) {
            AccountEntryOperationResult.AuthenticationCommitted
        } else {
            AccountEntryOperationResult.Failure("无法安全保存登录状态，已尝试撤销新会话，请重试")
        }
    }
    is AccountRepositoryResult.Failure -> AccountEntryOperationResult.Failure(result.message)
}
