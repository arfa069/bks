package com.bks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bks.feature.account.AccountEntryViewModel
import com.bks.feature.account.AccountManagementViewModel
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.AccountSessionCoordinator
import com.bks.feature.account.WechatAuthGateway

internal fun accountEntryFactory(
    accountRepository: AccountRepository,
    wechatAuthGateway: WechatAuthGateway?,
    sessionCoordinator: AccountSessionCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountEntryViewModel(
        accountRepository = accountRepository,
        commitSession = sessionCoordinator::commit,
        clearPersistedSession = sessionCoordinator::clearPersistedSession,
        enterLocalMode = sessionCoordinator::enterLocalMode,
        wechatAuthGateway = wechatAuthGateway
    ) as T
}

internal fun accountManagementFactory(
    accountRepository: AccountRepository,
    wechatAuthGateway: WechatAuthGateway?,
    sessionCoordinator: AccountSessionCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountManagementViewModel(
        accountRepository = accountRepository,
        commitManagementCredentials = sessionCoordinator::commitManagementCredentials,
        commitVerifiedCredentials = sessionCoordinator::commitVerifiedCredentials,
        invalidateSession = sessionCoordinator::invalidateSession,
        updateDeletionState = sessionCoordinator::updateDeletionState,
        wechatAuthGateway = wechatAuthGateway
    ) as T
}
