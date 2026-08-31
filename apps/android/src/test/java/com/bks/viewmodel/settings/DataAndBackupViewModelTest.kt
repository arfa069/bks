package com.bks.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataAndBackupViewModelTest {
    @Test
    fun exportOwnsEncryptionAndWriterOperationStateWithoutRetainingPassphrase() = runTest {
        withViewModel(
            exportEncryptedBackup = { passphrase ->
                assertEquals("private-passphrase", passphrase)
                "encrypted-backup"
            }
        ) { viewModel, _ ->
            viewModel.export(
                passphrase = "private-passphrase",
                writeExport = { content ->
                    assertEquals("encrypted-backup", content)
                    "backup-file.bak"
                }
            )

            assertEquals(DataAndBackupOperation.Exporting, viewModel.uiState.value.operation)
            assertFalse(viewModel.uiState.value.toString().contains("private-passphrase"))
            advanceUntilIdle()

            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
            assertEquals(true, viewModel.uiState.value.notice?.success)
            assertEquals("backup-file.bak", viewModel.uiState.value.notice?.detail)
        }
    }

    @Test
    fun exportWriterFailureIsOwnedAsExportFailure() = runTest {
        withViewModel { viewModel, _ ->
            viewModel.export("private-passphrase") { error("write failed") }
            advanceUntilIdle()

            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
            assertEquals(false, viewModel.uiState.value.notice?.success)
            assertEquals(DataAndBackupOperation.Exporting, viewModel.uiState.value.notice?.operation)
        }
    }

    @Test
    fun validationFailureDoesNotInvokeAnyReplacementOrWriteBoundary() = runTest {
        var replacementCalls = 0
        var clearCalls = 0
        withViewModel(
            validateEncryptedBackup = { _, _ -> error("invalid backup") },
            runLocalDataReplacement = {
                replacementCalls += 1
                it()
            },
            clearLocalData = {
                clearCalls += 1
                it()
            }
        ) { viewModel, _ ->
            val uiSessionId = viewModel.attachUi()
            val requestId = viewModel.validate(uiSessionId, "encrypted", "wrong-passphrase")
            advanceUntilIdle()

            assertEquals(0, replacementCalls)
            assertEquals(0, clearCalls)
            assertEquals(requestId, viewModel.uiState.value.validationResult?.requestId)
            assertEquals(false, viewModel.uiState.value.validationResult?.valid)
            assertFalse(viewModel.uiState.value.toString().contains("wrong-passphrase"))
        }
    }

    @Test
    fun importWaitsForReviewAndPublishesTheRepositoryFlowProjection() = runTest {
        val events = mutableListOf<String>()
        val roomProjection = kotlinx.coroutines.flow.MutableStateFlow("old")
        withViewModel(
            importEncryptedBackup = { backup, passphrase ->
                assertEquals("encrypted", backup)
                assertEquals("private-passphrase", passphrase)
                events += "import"
                roomProjection.value = "restored"
            },
            runLocalDataReplacement = { replacement ->
                events += "replacement-start"
                replacement()
                events += "replacement-end"
            },
            serializeWithSync = { replacement ->
                events += "sync-boundary-start"
                replacement()
                events += "sync-boundary-end"
            },
            awaitReviewIdle = { events += "review-idle" }
        ) { viewModel, _ ->
            viewModel.import("encrypted", "private-passphrase")
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.notice?.success)
            assertEquals("restored", roomProjection.value)
            assertEquals(
                listOf(
                    "sync-boundary-start",
                    "replacement-start",
                    "review-idle",
                    "import",
                    "replacement-end",
                    "sync-boundary-end"
                ),
                events
            )
            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
        }
    }

    @Test
    fun importFailureLeavesTheOwnerIdleAndReportsTheOperation() = runTest {
        withViewModel(
            importEncryptedBackup = { _, _ -> error("restore failed") }
        ) { viewModel, _ ->
            viewModel.import("encrypted", "private-passphrase")
            advanceUntilIdle()

            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
            assertEquals(false, viewModel.uiState.value.notice?.success)
            assertEquals(DataAndBackupOperation.Importing, viewModel.uiState.value.notice?.operation)
        }
    }

    @Test
    fun deletePreservesTheExistingOrderAndFinallyCleanup() = runTest {
        val events = mutableListOf<String>()
        withViewModel(
            clearLocalData = { beforeClear ->
                events += "replacement-start"
                beforeClear()
                events += "room-clear"
                events += "replacement-end"
            },
            awaitReviewIdle = { events += "review-idle" },
            clearAvatarCache = { events += "avatar-clear" },
            clearCloudSettingsLocalState = { events += "cloud-state-clear" },
            disableSync = { events += "sync-disable" },
            clearDiagnostics = { events += "diagnostics-clear" }
        ) { viewModel, _ ->
            viewModel.delete()
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.notice?.success)
            assertEquals(
                listOf(
                    "replacement-start",
                    "review-idle",
                    "avatar-clear",
                    "cloud-state-clear",
                    "sync-disable",
                    "room-clear",
                    "replacement-end",
                    "diagnostics-clear"
                ),
                events
            )
            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
        }
    }

    @Test
    fun deleteFailureStillRunsDiagnosticsFinallyCleanup() = runTest {
        val events = mutableListOf<String>()
        withViewModel(
            clearLocalData = { beforeClear ->
                beforeClear()
                events += "room-clear-failed"
                error("clear failed")
            },
            clearDiagnostics = { events += "diagnostics-clear" }
        ) { viewModel, _ ->
            viewModel.delete()
            advanceUntilIdle()

            assertEquals(listOf("room-clear-failed", "diagnostics-clear"), events)
            assertEquals(false, viewModel.uiState.value.notice?.success)
            assertEquals(DataAndBackupOperation.Deleting, viewModel.uiState.value.notice?.operation)
        }
    }

    @Test
    fun cancellingValidationClearsOperationWithoutPublishingAResult() = runTest {
        val validationStarted = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        withViewModel(
            validateEncryptedBackup = { _, _ ->
                validationStarted.complete(Unit)
                releaseValidation.await()
            }
        ) { viewModel, store ->
            val uiSessionId = viewModel.attachUi()
            viewModel.validate(uiSessionId, "encrypted", "private-passphrase")
            runCurrent()
            validationStarted.await()

            store.clear()
            advanceUntilIdle()

            assertEquals(DataAndBackupUiState(), viewModel.uiState.value)
        }
    }

    @Test
    fun validationCompletionAfterUiRecreationRequiresBackupReselection() = runTest {
        val validationStarted = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        withViewModel(
            validateEncryptedBackup = { _, _ ->
                validationStarted.complete(Unit)
                releaseValidation.await()
            }
        ) { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            viewModel.validate(oldUiSession, "encrypted", "private-passphrase")
            runCurrent()
            validationStarted.await()

            viewModel.detachUi(oldUiSession)
            viewModel.attachUi()

            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
            assertNull(viewModel.uiState.value.validationResult)
            assertTrue(viewModel.uiState.value.notice?.requiresBackupReselection == true)
            assertFalse(viewModel.uiState.value.toString().contains("private-passphrase"))
            assertFalse(viewModel.uiState.value.toString().contains("encrypted"))

            val noticeId = requireNotNull(viewModel.uiState.value.notice).id
            viewModel.acknowledgeNotice(noticeId)
            assertTrue(viewModel.validate(viewModel.attachUi(), "new-backup", "new-passphrase") != null)
            releaseValidation.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun validatedConfirmationLostDuringUiRecreationRequiresBackupReselection() = runTest {
        withViewModel { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            val requestId = requireNotNull(
                viewModel.validate(oldUiSession, "encrypted", "private-passphrase")
            )
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.validationResult?.valid)

            viewModel.acknowledgeValidation(requestId, oldUiSession)
            viewModel.detachUi(oldUiSession)
            viewModel.attachUi()

            assertNull(viewModel.uiState.value.validationResult)
            assertTrue(viewModel.uiState.value.notice?.requiresBackupReselection == true)
            assertFalse(viewModel.uiState.value.toString().contains("private-passphrase"))
            assertFalse(viewModel.uiState.value.toString().contains("encrypted"))
        }
    }

    @Test
    fun invalidValidationContextLostDuringUiRecreationRequiresBackupReselection() = runTest {
        withViewModel(
            validateEncryptedBackup = { _, _ -> error("invalid") }
        ) { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            val requestId = requireNotNull(
                viewModel.validate(oldUiSession, "encrypted", "wrong-passphrase")
            )
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.validationResult?.valid)

            viewModel.acknowledgeValidation(requestId, oldUiSession)
            viewModel.detachUi(oldUiSession)
            viewModel.attachUi()

            assertNull(viewModel.uiState.value.validationResult)
            assertTrue(viewModel.uiState.value.notice?.requiresBackupReselection == true)
        }
    }

    @Test
    fun oldUiDetachInvalidatesItsContinuationAfterNewUiAlreadyAttached() = runTest {
        withViewModel { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            val requestId = requireNotNull(
                viewModel.validate(oldUiSession, "encrypted", "private-passphrase")
            )
            advanceUntilIdle()
            viewModel.acknowledgeValidation(requestId, oldUiSession)

            val newUiSession = viewModel.attachUi()
            viewModel.detachUi(oldUiSession)

            assertTrue(newUiSession != oldUiSession)
            assertTrue(viewModel.uiState.value.notice?.requiresBackupReselection == true)
        }
    }

    @Test
    fun exportCompletionCanBeConsumedByTheRecreatedUiWithoutRepeatingTheWriter() = runTest {
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        var writerCalls = 0
        withViewModel { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            viewModel.export("private-passphrase") {
                writerCalls += 1
                writerStarted.complete(Unit)
                releaseWriter.await()
                "backup-file.bak"
            }
            runCurrent()
            writerStarted.await()

            viewModel.detachUi(oldUiSession)
            viewModel.attachUi()
            releaseWriter.complete(Unit)
            advanceUntilIdle()

            val notice = requireNotNull(viewModel.uiState.value.notice)
            assertEquals(1, writerCalls)
            assertEquals("backup-file.bak", notice.detail)
            viewModel.acknowledgeNotice(notice.id + 1)
            assertEquals(notice, viewModel.uiState.value.notice)
            viewModel.acknowledgeNotice(notice.id)
            assertNull(viewModel.uiState.value.notice)
        }
    }

    @Test
    fun importCompletionAfterUiRecreationPublishesOneNonSensitiveNotice() = runTest {
        val importStarted = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        var importCalls = 0
        withViewModel(
            importEncryptedBackup = { _, _ ->
                importCalls += 1
                importStarted.complete(Unit)
                releaseImport.await()
            }
        ) { viewModel, _ ->
            val oldUiSession = viewModel.attachUi()
            viewModel.import("encrypted", "private-passphrase")
            runCurrent()
            importStarted.await()

            viewModel.detachUi(oldUiSession)
            viewModel.attachUi()
            releaseImport.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, importCalls)
            assertEquals(DataAndBackupOperation.Importing, viewModel.uiState.value.notice?.operation)
            assertEquals(true, viewModel.uiState.value.notice?.success)
            assertFalse(viewModel.uiState.value.toString().contains("private-passphrase"))
            assertFalse(viewModel.uiState.value.toString().contains("encrypted"))
        }
    }

    @Test
    fun deleteCompletesItsNonCancellableSequenceAfterOwnerClear() = runTest {
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        withViewModel(
            clearLocalData = { beforeClear ->
                beforeClear()
                clearStarted.complete(Unit)
                releaseClear.await()
                events += "room-clear"
            },
            clearDiagnostics = { events += "diagnostics-clear" }
        ) { viewModel, store ->
            viewModel.delete()
            runCurrent()
            clearStarted.await()

            store.clear()
            releaseClear.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("room-clear", "diagnostics-clear"), events)
            assertEquals(DataAndBackupUiState(), viewModel.uiState.value)
        }
    }

    @Test
    fun secondOperationIsIgnoredWhileTheFirstIsInProgress() = runTest {
        val releaseExport = CompletableDeferred<Unit>()
        var validationCalls = 0
        withViewModel(
            exportEncryptedBackup = {
                releaseExport.await()
                "encrypted"
            },
            validateEncryptedBackup = { _, _ -> validationCalls += 1 }
        ) { viewModel, _ ->
            viewModel.export("private-passphrase") { "file" }
            viewModel.validate(viewModel.attachUi(), "encrypted", "private-passphrase")
            runCurrent()

            assertEquals(0, validationCalls)
            assertEquals(DataAndBackupOperation.Exporting, viewModel.uiState.value.operation)

            releaseExport.complete(Unit)
            advanceUntilIdle()
            assertEquals(DataAndBackupOperation.Idle, viewModel.uiState.value.operation)
        }
    }

    @Test
    fun pendingNoticePreventsASecondOperationFromOverwritingTheFirstResult() = runTest {
        var writerCalls = 0
        withViewModel { viewModel, _ ->
            viewModel.import("encrypted", "private-passphrase")
            advanceUntilIdle()
            val importNotice = requireNotNull(viewModel.uiState.value.notice)

            viewModel.export("another-passphrase") {
                writerCalls += 1
                "second-file.bak"
            }
            advanceUntilIdle()

            assertEquals(0, writerCalls)
            assertEquals(importNotice, viewModel.uiState.value.notice)
            viewModel.acknowledgeNotice(importNotice.id)
            viewModel.export("another-passphrase") {
                writerCalls += 1
                "second-file.bak"
            }
            advanceUntilIdle()
            assertEquals(1, writerCalls)
            assertEquals(DataAndBackupOperation.Exporting, viewModel.uiState.value.notice?.operation)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun TestScope.withViewModel(
        exportEncryptedBackup: suspend (String) -> String = { "encrypted" },
        validateEncryptedBackup: suspend (String, String) -> Unit = { _, _ -> },
        importEncryptedBackup: suspend (String, String) -> Unit = { _, _ -> },
        serializeWithSync: suspend (suspend () -> Unit) -> Unit = { it() },
        runLocalDataReplacement: suspend (suspend () -> Unit) -> Unit = { it() },
        clearLocalData: suspend (suspend () -> Unit) -> Unit = { it() },
        awaitReviewIdle: suspend () -> Unit = {},
        clearAvatarCache: suspend () -> Unit = {},
        clearCloudSettingsLocalState: suspend () -> Unit = {},
        disableSync: suspend () -> Unit = {},
        clearDiagnostics: suspend () -> Unit = {},
        block: suspend (DataAndBackupViewModel, ViewModelStore) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = DataAndBackupViewModel(
                    exportEncryptedBackup = exportEncryptedBackup,
                    validateEncryptedBackup = validateEncryptedBackup,
                    importEncryptedBackup = importEncryptedBackup,
                    serializeWithSync = serializeWithSync,
                    runLocalDataReplacement = runLocalDataReplacement,
                    clearLocalData = clearLocalData,
                    awaitReviewIdle = awaitReviewIdle,
                    clearAvatarCache = clearAvatarCache,
                    clearCloudSettingsLocalState = clearCloudSettingsLocalState,
                    disableSync = disableSync,
                    clearDiagnostics = clearDiagnostics
                ) as T
            }
            block(ViewModelProvider(store, factory)[DataAndBackupViewModel::class.java], store)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
