package com.bks.model.domain.account

internal class FakeAccountRepository : AccountRepository {
    private var deletionState = AccountDeletionState()

    override suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)

    override suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(deletionState = deletionState)
    )

    override suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(deletionState = deletionState, nickname = nickname)
    )

    override suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(deletionState = deletionState, avatarUrl = avatarDataUrl)
    )

    override suspend fun signOut(token: String): AccountRepositoryResult<Unit> =
        AccountRepositoryResult.Success(Unit)

    override suspend fun getDeletionStatus(
        token: String
    ): AccountRepositoryResult<AccountDeletionState> = AccountRepositoryResult.Success(deletionState)

    override suspend fun requestDeletion(
        token: String
    ): AccountRepositoryResult<AccountDeletionState> {
        deletionState = AccountDeletionState(
            requestedAtEpochMillis = 1_000,
            finalDeletionAtEpochMillis = 604_801_000
        )
        return AccountRepositoryResult.Success(deletionState)
    }

    override suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit> {
        deletionState = AccountDeletionState()
        return AccountRepositoryResult.Success(Unit)
    }

    override suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String?
    ): AccountRepositoryResult<AccountWechatAuthResult> = AccountRepositoryResult.Success(
        AccountWechatAuthResult.RegistrationRequired(
            wechatTicket = "mock-wechat-ticket",
            nickname = "微信用户",
            avatarUrl = null,
            ticketExpiresAtMillis = 300_000L
        )
    )

    override suspend fun registerWithWechat(
        wechatTicket: String
    ): AccountRepositoryResult<AccountCredentials> = wechatSuccess()

    override suspend fun linkWechatWithPassword(
        wechatTicket: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier, wechatLinked = true)

    override suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier, wechatLinked = true)

    override suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean
    ): AccountRepositoryResult<IdentifierLinkPreparation> = AccountRepositoryResult.Success(
        IdentifierLinkPreparation.LinkTicketIssued("mock-link-ticket", 300_000L)
    )

    override suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String?
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000", wechatLinked = true)

    override suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountMergePreview> = AccountRepositoryResult.Success(
        AccountMergePreview(
            mergeTicket = "mock-merge-ticket",
            ticketExpiresAtMillis = 300_000L,
            currentWechatLinked = true,
            currentNickname = "微信用户",
            sourceIdentifiers = listOf(AccountIdentifierParser.parse(identifier).toIdentifier()),
            sourceWechatLinked = false,
            sourceNickname = null
        )
    )

    override suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000", wechatLinked = true)

    override suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000")

    override suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000")

    private fun success(
        phone: String,
        wechatLinked: Boolean = false
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        AccountCredentials(
            phone = phone,
            token = "mock-token",
            deletionState = deletionState,
            wechatLinked = wechatLinked,
            nickname = "微信用户".takeIf { wechatLinked }
        )
    )

    private fun wechatSuccess(): AccountRepositoryResult<AccountCredentials> =
        AccountRepositoryResult.Success(
            AccountCredentials(
                phone = null,
                token = "mock-token",
                deletionState = deletionState,
                wechatLinked = true,
                nickname = "微信用户"
            )
        )
}
