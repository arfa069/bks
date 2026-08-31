package com.bks.model.domain.account

sealed interface AccountSession {
    data object LocalMode : AccountSession
    data class SignedIn(
        val accountId: Long? = null,
        val primaryIdentifier: AccountIdentifier? = null,
        val identifiers: List<AccountIdentifier> = emptyList(),
        val rawPhone: String? = null,
        val token: String = "",
        val accountUuid: String? = null,
        val wechatLinked: Boolean = false,
        val nickname: String? = null,
        val avatarUrl: String? = null
    ) : AccountSession {
        constructor(
            phone: String?,
            token: String = "",
            wechatLinked: Boolean = false,
            nickname: String? = null,
            avatarUrl: String? = null
        ) : this(
            accountId = null,
            accountUuid = null,
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
}

fun AccountSession.SignedIn.toCredentials(deletionState: AccountDeletionState): AccountCredentials =
    AccountCredentials(
        accountId = accountId,
        accountUuid = accountUuid,
        primaryIdentifier = primaryIdentifier,
        identifiers = identifiers,
        rawPhone = rawPhone,
        token = token,
        deletionState = deletionState,
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl
    )

internal fun AccountSession.SignedIn.cloudConfigAccountKey(): String? =
    accountUuid?.takeIf { it.isNotBlank() } ?: accountId?.toString()

enum class AccountRuntimeStatus {
    LocalMode,
    Validating,
    Verified,
    OfflineUnverified,
    DeletionCoolingOff
}

data class AccountRuntimeState(
    val status: AccountRuntimeStatus = AccountRuntimeStatus.LocalMode
) {
    val cloudWritesAllowed: Boolean
        get() = status == AccountRuntimeStatus.Verified

    val accountOperationsAllowed: Boolean
        get() = status == AccountRuntimeStatus.Verified ||
            status == AccountRuntimeStatus.DeletionCoolingOff
}

sealed interface AccountSessionVerificationDecision {
    data class Verified(val credentials: AccountCredentials) : AccountSessionVerificationDecision
    data object KeepOfflineSession : AccountSessionVerificationDecision
    data object ClearInvalidSession : AccountSessionVerificationDecision
}

fun resolveAccountSessionVerification(
    result: AccountRepositoryResult<AccountCredentials>
): AccountSessionVerificationDecision = when (result) {
    is AccountRepositoryResult.Success ->
        AccountSessionVerificationDecision.Verified(result.value)
    is AccountRepositoryResult.Failure ->
        if (result.kind == AccountFailureKind.InvalidSession) {
            AccountSessionVerificationDecision.ClearInvalidSession
        } else {
            AccountSessionVerificationDecision.KeepOfflineSession
        }
}
