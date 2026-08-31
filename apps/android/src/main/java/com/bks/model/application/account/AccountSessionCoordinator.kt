package com.bks.model.application.account

import com.bks.model.domain.account.AccountSessionRestoreResult
import com.bks.model.domain.account.AccountSessionStore
import com.bks.model.domain.account.LocalModeStore
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountRepository
import com.bks.model.domain.account.AccountRuntimeState
import com.bks.model.domain.account.AccountRuntimeStatus
import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.AccountSessionVerificationDecision
import com.bks.model.domain.account.resolveAccountSessionVerification
import com.bks.model.domain.account.toSignedInSession
import com.bks.model.domain.account.AccountDeletionState
import com.bks.model.domain.account.toCredentials
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
    val deletionState: AccountDeletionState = AccountDeletionState()
)

internal enum class AccountSessionNotice {
    RefreshedCredentialsNotPersisted,
    InvalidSessionMovedToLocalMode
}

internal enum class AccountSessionMutationResult {
    Applied,
    PersistenceFailed,
    StaleSession
}

@Suppress("LongParameterList", "TooManyFunctions")
internal class AccountSessionCoordinator(
    private val accountRepository: AccountRepository,
    private val secureSessionStore: AccountSessionStore,
    private val localModeSessionStore: LocalModeStore,
    private val applicationScope: CoroutineScope,
    private val persistSession: (AccountCredentials) -> Boolean = secureSessionStore::save,
    private val confirmLocalMode: () -> Boolean = localModeSessionStore::confirmLocalMode,
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

    fun commitManagementCredentials(
        expectedSessionToken: String,
        credentials: AccountCredentials
    ): AccountSessionMutationResult = synchronized(stateLock) {
        val current = mutableState.value
        val currentSession = current.session as? AccountSession.SignedIn
            ?: return@synchronized AccountSessionMutationResult.StaleSession
        if (currentSession.token != expectedSessionToken) {
            return@synchronized AccountSessionMutationResult.StaleSession
        }
        val credentialsWithCurrentDeletionState = credentials.copy(
            deletionState = current.deletionState
        )
        if (!persistSession(credentialsWithCurrentDeletionState)) {
            return@synchronized AccountSessionMutationResult.PersistenceFailed
        }
        publishVerified(credentialsWithCurrentDeletionState)
        AccountSessionMutationResult.Applied
    }

    fun commitVerifiedCredentials(
        expectedSessionToken: String,
        credentials: AccountCredentials
    ): AccountSessionMutationResult = synchronized(stateLock) {
        val currentSession = mutableState.value.session as? AccountSession.SignedIn
            ?: return@synchronized AccountSessionMutationResult.StaleSession
        if (currentSession.token != expectedSessionToken) {
            return@synchronized AccountSessionMutationResult.StaleSession
        }
        if (!persistSession(credentials)) {
            return@synchronized AccountSessionMutationResult.PersistenceFailed
        }
        publishVerified(credentials)
        AccountSessionMutationResult.Applied
    }

    fun enterLocalMode() {
        synchronized(stateLock) {
            if (confirmLocalMode()) publishLocalMode()
        }
    }

    fun moveToLocalMode() {
        invalidateSession()
    }

    fun invalidateSession(): Boolean = synchronized(stateLock) {
        if (!confirmLocalMode()) return@synchronized false
        if (!secureSessionStore.clear()) return@synchronized false
        onMoveToLocalMode()
        publishLocalMode()
        true
    }

    fun clearPersistedSession(): Boolean = synchronized(stateLock) {
        secureSessionStore.clear().also { cleared ->
            if (cleared) sessionGeneration += 1
        }
    }

    fun updateDeletionState(
        expectedSessionToken: String,
        deletionState: AccountDeletionState
    ): AccountSessionMutationResult = synchronized(stateLock) {
        val current = mutableState.value
        val currentSession = current.session as? AccountSession.SignedIn
            ?: return@synchronized AccountSessionMutationResult.StaleSession
        if (currentSession.token != expectedSessionToken) {
            return@synchronized AccountSessionMutationResult.StaleSession
        }
        val credentials = currentSession.toCredentials(deletionState)
        if (!persistSession(credentials)) {
            return@synchronized AccountSessionMutationResult.PersistenceFailed
        }
        publishVerified(credentials)
        AccountSessionMutationResult.Applied
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
                    if (!confirmLocalMode()) {
                        mutableState.value = mutableState.value.copy(
                            runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
                        )
                        return@synchronized false
                    }
                    if (!secureSessionStore.clear()) {
                        mutableState.value = mutableState.value.copy(
                            runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
                        )
                        return@synchronized false
                    }
                    onMoveToLocalMode()
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
