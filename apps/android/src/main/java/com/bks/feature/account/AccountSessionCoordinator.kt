package com.bks.feature.account

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class AccountSessionCoordinatorState(
    val session: AccountSession? = null,
    val isRestoring: Boolean = true,
    val runtimeState: AccountRuntimeState = AccountRuntimeState(),
    val deletionState: AccountDeletionUiState = AccountDeletionUiState()
)

internal enum class AccountSessionNotice {
    RefreshedCredentialsNotPersisted,
    InvalidSessionMovedToLocalMode
}

@Suppress("LongParameterList")
internal class AccountSessionCoordinator(
    private val accountRepository: AccountRepository,
    private val secureSessionStore: SecureAccountSessionStore,
    private val localModeSessionStore: LocalModeSessionStore,
    private val applicationScope: CoroutineScope,
    private val persistSession: (AccountCredentials) -> Boolean = secureSessionStore::save,
    private val onCredentialsAccepted: (AccountCredentials) -> Unit = {},
    private val onMoveToLocalMode: () -> Unit = {}
) {
    private val started = AtomicBoolean(false)
    private val stateLock = Any()
    private var sessionGeneration = 0L
    private val mutableState = MutableStateFlow(AccountSessionCoordinatorState())
    private val noticeChannel = Channel<AccountSessionNotice>(Channel.BUFFERED)

    val state: StateFlow<AccountSessionCoordinatorState> = mutableState.asStateFlow()
    val notices = noticeChannel.receiveAsFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch { restoreAndVerify() }
    }

    fun commit(credentials: AccountCredentials): Boolean = synchronized(stateLock) {
        if (!persistSession(credentials)) return@synchronized false
        publishVerified(credentials)
        true
    }

    fun enterLocalMode() {
        synchronized(stateLock) {
            localModeSessionStore.confirmLocalMode()
            publishLocalMode()
        }
    }

    fun moveToLocalMode() {
        synchronized(stateLock) {
            onMoveToLocalMode()
            secureSessionStore.clear()
            localModeSessionStore.confirmLocalMode()
            publishLocalMode()
        }
    }

    fun clearPersistedSession(): Boolean = synchronized(stateLock) {
        secureSessionStore.clear().also { cleared ->
            if (cleared) sessionGeneration += 1
        }
    }

    fun updateDeletionState(deletionState: AccountDeletionUiState) {
        synchronized(stateLock) {
            val current = mutableState.value
            if (current.session !is AccountSession.SignedIn) return
            sessionGeneration += 1
            mutableState.value = current.copy(
                deletionState = deletionState,
                runtimeState = AccountRuntimeState(
                    if (deletionState.isPending) {
                        AccountRuntimeStatus.DeletionCoolingOff
                    } else {
                        AccountRuntimeStatus.Verified
                    }
                )
            )
        }
    }

    private suspend fun restoreAndVerify() {
        when (val restored = secureSessionStore.restore()) {
            is AccountSessionRestoreResult.Restored -> {
                val verificationGeneration = synchronized(stateLock) {
                    sessionGeneration += 1
                    mutableState.value = AccountSessionCoordinatorState(
                        session = restored.credentials.toSignedInSession(),
                        isRestoring = false,
                        runtimeState = AccountRuntimeState(AccountRuntimeStatus.Validating),
                        deletionState = restored.credentials.deletionState
                    )
                    sessionGeneration
                }
                verifyRestoredSession(restored.credentials, verificationGeneration)
            }
            AccountSessionRestoreResult.Corrupted -> {
                localModeSessionStore.confirmLocalMode()
                synchronized(stateLock) { publishLocalMode(isRestoring = false) }
            }
            AccountSessionRestoreResult.Empty -> synchronized(stateLock) {
                sessionGeneration += 1
                mutableState.value = AccountSessionCoordinatorState(
                    session = localModeSessionStore.restoreSession(),
                    isRestoring = false
                )
            }
        }
    }

    private suspend fun verifyRestoredSession(
        credentials: AccountCredentials,
        verificationGeneration: Long
    ) {
        when (val decision = resolveAccountSessionVerification(accountRepository.verifySession(credentials))) {
            is AccountSessionVerificationDecision.Verified -> synchronized(stateLock) {
                if (sessionGeneration != verificationGeneration) return
                if (persistSession(decision.credentials)) {
                    publishVerified(decision.credentials)
                } else {
                    mutableState.value = mutableState.value.copy(
                        runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
                    )
                    noticeChannel.trySend(AccountSessionNotice.RefreshedCredentialsNotPersisted)
                }
            }
            AccountSessionVerificationDecision.ClearInvalidSession -> {
                val movedToLocalMode = synchronized(stateLock) {
                    if (sessionGeneration != verificationGeneration) return@synchronized false
                    onMoveToLocalMode()
                    secureSessionStore.clear()
                    localModeSessionStore.confirmLocalMode()
                    publishLocalMode()
                    true
                }
                if (movedToLocalMode) {
                    noticeChannel.trySend(AccountSessionNotice.InvalidSessionMovedToLocalMode)
                }
            }
            AccountSessionVerificationDecision.KeepOfflineSession -> synchronized(stateLock) {
                if (sessionGeneration != verificationGeneration) return
                mutableState.value = mutableState.value.copy(
                    runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
                )
            }
        }
    }

    private fun publishVerified(credentials: AccountCredentials) {
        onCredentialsAccepted(credentials)
        sessionGeneration += 1
        mutableState.value = AccountSessionCoordinatorState(
            session = credentials.toSignedInSession(),
            isRestoring = false,
            runtimeState = AccountRuntimeState(
                if (credentials.deletionState.isPending) {
                    AccountRuntimeStatus.DeletionCoolingOff
                } else {
                    AccountRuntimeStatus.Verified
                }
            ),
            deletionState = credentials.deletionState
        )
    }

    private fun publishLocalMode(isRestoring: Boolean = false) {
        sessionGeneration += 1
        mutableState.value = AccountSessionCoordinatorState(
            session = AccountSession.LocalMode,
            isRestoring = isRestoring
        )
    }
}
