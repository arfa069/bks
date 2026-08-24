package com.bks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bks.feature.account.AccountEntryViewModel
import com.bks.feature.account.AccountManagementViewModel
import com.bks.feature.account.AccountSessionCoordinator

internal fun accountEntryFactory(
    dependencies: BksAppDependencies,
    sessionCoordinator: AccountSessionCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountEntryViewModel(
        accountRepository = dependencies.account.accountRepository,
        commitSession = sessionCoordinator::commit,
        clearPersistedSession = sessionCoordinator::clearPersistedSession,
        enterLocalMode = sessionCoordinator::enterLocalMode,
        wechatAuthGateway = dependencies.account.wechatAuthGateway
    ) as T
}

internal fun accountManagementFactory(
    dependencies: BksAppDependencies,
    sessionCoordinator: AccountSessionCoordinator
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountManagementViewModel(
        accountRepository = dependencies.account.accountRepository,
        commitManagementCredentials = sessionCoordinator::commitManagementCredentials,
        commitVerifiedCredentials = sessionCoordinator::commitVerifiedCredentials,
        invalidateSession = sessionCoordinator::invalidateSession,
        updateDeletionState = sessionCoordinator::updateDeletionState,
        wechatAuthGateway = dependencies.account.wechatAuthGateway
    ) as T
}
