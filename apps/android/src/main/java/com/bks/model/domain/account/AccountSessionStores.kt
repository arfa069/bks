package com.bks.model.domain.account

sealed interface AccountSessionRestoreResult {
    data object Empty : AccountSessionRestoreResult
    data class Restored(val credentials: AccountCredentials) : AccountSessionRestoreResult
    data object Corrupted : AccountSessionRestoreResult
}

interface AccountSessionStore {
    fun save(credentials: AccountCredentials): Boolean
    fun restore(): AccountSessionRestoreResult
    fun clear(): Boolean
}

interface LocalModeStore {
    fun restoreSession(): AccountSession?
    fun confirmLocalMode(): Boolean
}

fun AccountCredentials.toSignedInSession() = AccountSession.SignedIn(
    accountId = accountId,
    accountUuid = accountUuid,
    primaryIdentifier = primaryIdentifier,
    identifiers = identifiers,
    rawPhone = rawPhone,
    token = token,
    wechatLinked = wechatLinked,
    nickname = nickname,
    avatarUrl = avatarUrl
)
