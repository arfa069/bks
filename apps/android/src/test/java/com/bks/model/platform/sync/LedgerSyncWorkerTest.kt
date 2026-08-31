package com.bks.model.platform.sync

import com.bks.model.application.sync.LedgerSyncOperationResult
import com.bks.model.platform.sync.LedgerSyncWorkerOutcome
import com.bks.model.platform.sync.runLedgerSyncWorkerInvocation
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.account.AccountSessionRestoreResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerSyncWorkerTest {
    @Test
    fun eachInvocationRestoresItsOwnTokenWithoutCachingEarlierCredentials() = runTest {
        val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
        val synchronizedTokens = mutableListOf<String>()
        val invoke = suspend {
            runLedgerSyncWorkerInvocation(
                restoreSession = {
                    AccountSessionRestoreResult.Restored(AccountCredentials(token = tokens.removeFirst()))
                },
                syncEnabled = { true },
                synchronize = { token ->
                    synchronizedTokens += token
                    LedgerSyncOperationResult.Success(Unit)
                }
            )
        }

        assertEquals(LedgerSyncWorkerOutcome.Success, invoke())
        assertEquals(LedgerSyncWorkerOutcome.Success, invoke())

        assertEquals(listOf("stale-token", "fresh-token"), synchronizedTokens)
    }

    @Test
    fun missingSessionOrDisabledSyncStopsBeforeSynchronization() = runTest {
        var synchronizationCalls = 0

        val missingSession = runLedgerSyncWorkerInvocation(
            restoreSession = { AccountSessionRestoreResult.Empty },
            syncEnabled = { error("enabled check must not run without a session") },
            synchronize = { error("synchronization must not run") }
        )
        val disabled = runLedgerSyncWorkerInvocation(
            restoreSession = {
                AccountSessionRestoreResult.Restored(AccountCredentials(token = "token"))
            },
            syncEnabled = { false },
            synchronize = {
                synchronizationCalls += 1
                LedgerSyncOperationResult.Success(Unit)
            }
        )

        assertEquals(LedgerSyncWorkerOutcome.Success, missingSession)
        assertEquals(LedgerSyncWorkerOutcome.Success, disabled)
        assertEquals(0, synchronizationCalls)
    }

    @Test
    fun retryableAndTerminalFailuresMapToDistinctWorkerOutcomes() = runTest {
        suspend fun invoke(retryable: Boolean) = runLedgerSyncWorkerInvocation(
            restoreSession = {
                AccountSessionRestoreResult.Restored(AccountCredentials(token = "token"))
            },
            syncEnabled = { true },
            synchronize = { LedgerSyncOperationResult.Failure(null, "failed", retryable) }
        )

        assertEquals(LedgerSyncWorkerOutcome.Retry, invoke(retryable = true))
        assertEquals(LedgerSyncWorkerOutcome.Failure, invoke(retryable = false))
    }
}
