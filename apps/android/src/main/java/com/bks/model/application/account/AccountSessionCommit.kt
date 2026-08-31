package com.bks.model.application.account

import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
internal suspend fun persistAccountSessionOrRevoke(
    credentials: AccountCredentials,
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): Boolean {
    if (persistSession(credentials)) return true

    clearPersistedSession()
    accountRepository.signOut(credentials.token)
    return false
}

internal fun persistRefreshedAccountSession(
    credentials: AccountCredentials,
    persistSession: (AccountCredentials) -> Boolean,
    onSessionVerified: (AccountCredentials) -> Unit
): Boolean {
    if (!persistSession(credentials)) return false

    onSessionVerified(credentials)
    return true
}
