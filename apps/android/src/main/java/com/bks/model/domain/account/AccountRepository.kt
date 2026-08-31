package com.bks.model.domain.account

interface AccountRepository {
    suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountRepositoryResult<Unit>

    suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun signOut(token: String): AccountRepositoryResult<Unit>

    suspend fun getDeletionStatus(token: String): AccountRepositoryResult<AccountDeletionState>

    suspend fun requestDeletion(token: String): AccountRepositoryResult<AccountDeletionState>

    suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit>

    suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String? = null
    ): AccountRepositoryResult<AccountWechatAuthResult>

    suspend fun registerWithWechat(wechatTicket: String): AccountRepositoryResult<AccountCredentials>

    suspend fun linkWechatWithPassword(
        wechatTicket: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean = false
    ): AccountRepositoryResult<IdentifierLinkPreparation>

    suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String? = null
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountMergePreview>

    suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>
}

data class AccountCredentials(
    val accountId: Long? = null,
    val primaryIdentifier: AccountIdentifier? = null,
    val identifiers: List<AccountIdentifier> = emptyList(),
    val rawPhone: String? = null,
    val token: String,
    val accountUuid: String? = null,
    val deletionState: AccountDeletionState = AccountDeletionState(),
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null
) {
    constructor(
        phone: String?,
        token: String,
        deletionState: AccountDeletionState = AccountDeletionState(),
        wechatLinked: Boolean = false,
        nickname: String? = null,
        avatarUrl: String? = null
    ) : this(
        primaryIdentifier = phone?.let {
            AccountIdentifier(
                type = AccountIdentifierType.PHONE,
                value = it
            )
        },
        identifiers = phone?.let {
            listOf(
                AccountIdentifier(
                    type = AccountIdentifierType.PHONE,
                    value = it
                )
            )
        } ?: emptyList(),
        rawPhone = phone,
        token = token,
        deletionState = deletionState,
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl
    )

    val phone: String?
        get() = identifiers.find { it.type == AccountIdentifierType.PHONE }?.value ?: rawPhone

    val email: String?
        get() = identifiers.find { it.type == AccountIdentifierType.EMAIL }?.value

    val username: String?
        get() = identifiers.find { it.type == AccountIdentifierType.USERNAME }?.value
}

enum class AccountVerificationPurpose(val wireValue: String) {
    Register("REGISTER"),
    Recovery("RECOVERY"),
    WechatLink("WECHAT_LINK"),
    IdentifierLink("IDENTIFIER_LINK"),
    WechatUnlink("WECHAT_UNLINK")
}

sealed interface AccountWechatAuthResult {
    data class SignedIn(val credentials: AccountCredentials) : AccountWechatAuthResult

    data class RegistrationRequired(
        val wechatTicket: String,
        val nickname: String?,
        val avatarUrl: String?,
        val ticketExpiresAtMillis: Long
    ) : AccountWechatAuthResult

    data class MergeRequired(
        val mergeTicket: String,
        val sourceNickname: String?,
        val sourceIdentifiers: List<AccountIdentifier>,
        val ticketExpiresAtMillis: Long
    ) : AccountWechatAuthResult
}

enum class AccountFailureKind {
    ConfigurationMissing,
    Network,
    InvalidSession,
    RateLimited,
    Service,
    InvalidResponse
}

sealed interface AccountRepositoryResult<out T> {
    data class Success<T>(val value: T) : AccountRepositoryResult<T>

    data class Failure(
        val kind: AccountFailureKind,
        val message: String,
        val code: String? = null
    ) : AccountRepositoryResult<Nothing>
}
