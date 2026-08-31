package com.bks.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class DataAndBackupOperation {
    Idle,
    Exporting,
    Validating,
    Importing,
    Deleting
}

internal data class DataAndBackupUiState(
    val operation: DataAndBackupOperation = DataAndBackupOperation.Idle,
    val validationResult: DataAndBackupValidationResult? = null,
    val notice: DataAndBackupNotice? = null
) {
    val operationInProgress: Boolean
        get() = operation != DataAndBackupOperation.Idle || notice != null
}

internal data class DataAndBackupValidationResult(
    val requestId: Long,
    val uiSessionId: Long,
    val valid: Boolean
)

internal data class DataAndBackupNotice(
    val id: Long,
    val operation: DataAndBackupOperation,
    val success: Boolean,
    val detail: String? = null,
    val requiresBackupReselection: Boolean = false
)

@Suppress("LongParameterList")
internal class DataAndBackupActions(
    val onExport: (
        passphrase: String,
        writer: suspend (String) -> String
    ) -> Unit,
    val onValidate: (Long, String, String) -> Long?,
    val onImport: (String, String) -> Unit,
    val onDelete: () -> Unit,
    val onAttachUi: () -> Long,
    val onDetachUi: (Long) -> Unit,
    val onAcknowledgeValidation: (Long, Long) -> Unit,
    val onEndValidatedContinuation: (Long) -> Unit,
    val onClearValidation: () -> Unit,
    val onAcknowledgeNotice: (Long) -> Unit
)

@Suppress("LongParameterList", "TooManyFunctions")
internal class DataAndBackupViewModel(
    private val exportEncryptedBackup: suspend (String) -> String,
    private val validateEncryptedBackup: suspend (String, String) -> Unit,
    private val importEncryptedBackup: suspend (String, String) -> Unit,
    private val serializeWithSync: suspend (suspend () -> Unit) -> Unit,
    private val runLocalDataReplacement: suspend (suspend () -> Unit) -> Unit,
    private val clearLocalData: suspend (suspend () -> Unit) -> Unit,
    private val awaitReviewIdle: suspend () -> Unit,
    private val clearAvatarCache: suspend () -> Unit,
    private val clearCloudSettingsLocalState: suspend () -> Unit,
    private val disableSync: suspend () -> Unit,
    private val clearDiagnostics: suspend () -> Unit
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DataAndBackupUiState())
    val uiState = mutableUiState.asStateFlow()
    private var nextId = 0L
    private var activeUiSessionId: Long? = null
    private var pendingValidatedUiSessionId: Long? = null
    private var validationJob: Job? = null
    private var activeValidationRequestId: Long? = null
    private var activeValidationUiSessionId: Long? = null
    private var ownerCleared = false

    fun export(
        passphrase: String,
        writeExport: suspend (String) -> String
    ) {
        launchOperation(DataAndBackupOperation.Exporting) {
            writeExport(exportEncryptedBackup(passphrase))
        }
    }

    fun validate(
        uiSessionId: Long,
        backup: String,
        passphrase: String
    ): Long? {
        if (mutableUiState.value.operationInProgress) return null
        val requestId = newId()
        pendingValidatedUiSessionId = null
        activeValidationRequestId = requestId
        activeValidationUiSessionId = uiSessionId
        mutableUiState.value = mutableUiState.value.copy(
            operation = DataAndBackupOperation.Validating,
            validationResult = null
        )
        validationJob = viewModelScope.launch {
            val valid = try {
                validateEncryptedBackup(backup, passphrase)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (activeValidationRequestId != requestId) return@launch
            activeValidationRequestId = null
            activeValidationUiSessionId = null
            validationJob = null
            mutableUiState.value = mutableUiState.value.copy(
                operation = DataAndBackupOperation.Idle
            )
            if (activeUiSessionId == uiSessionId) {
                mutableUiState.value = mutableUiState.value.copy(
                    validationResult = DataAndBackupValidationResult(
                        requestId = requestId,
                        uiSessionId = uiSessionId,
                        valid = valid
                    )
                )
            } else {
                publishNotice(
                    operation = DataAndBackupOperation.Validating,
                    success = false,
                    requiresBackupReselection = true
                )
            }
        }
        return requestId
    }

    fun import(
        backup: String,
        passphrase: String
    ) {
        launchOperation(DataAndBackupOperation.Importing) {
            serializeWithSync {
                runLocalDataReplacement {
                    awaitReviewIdle()
                    importEncryptedBackup(backup, passphrase)
                }
            }
            null
        }
    }

    fun delete() {
        launchOperation(DataAndBackupOperation.Deleting) {
            withContext(NonCancellable) {
                try {
                    clearLocalData {
                        awaitReviewIdle()
                        clearAvatarCache()
                        clearCloudSettingsLocalState()
                        disableSync()
                    }
                } finally {
                    clearDiagnostics()
                }
            }
            null
        }
    }

    fun attachUi(): Long = newId().also { activeUiSessionId = it }

    fun detachUi(uiSessionId: Long) {
        if (activeUiSessionId == uiSessionId) activeUiSessionId = null
        if (activeValidationUiSessionId == uiSessionId) {
            activeValidationRequestId = null
            activeValidationUiSessionId = null
            validationJob?.cancel()
            validationJob = null
            mutableUiState.value = mutableUiState.value.copy(
                operation = DataAndBackupOperation.Idle,
                validationResult = null
            )
            publishNotice(
                operation = DataAndBackupOperation.Validating,
                success = false,
                requiresBackupReselection = true
            )
            return
        }
        val validation = mutableUiState.value.validationResult
        val losesValidatedContinuation = pendingValidatedUiSessionId == uiSessionId
        if (validation?.uiSessionId == uiSessionId || losesValidatedContinuation) {
            pendingValidatedUiSessionId = null
            mutableUiState.value = mutableUiState.value.copy(validationResult = null)
            publishNotice(
                operation = DataAndBackupOperation.Validating,
                success = false,
                requiresBackupReselection = true
            )
        }
    }

    fun acknowledgeValidation(requestId: Long, uiSessionId: Long) {
        val result = mutableUiState.value.validationResult
        if (result?.requestId == requestId && result.uiSessionId == uiSessionId) {
            pendingValidatedUiSessionId = uiSessionId
            mutableUiState.value = mutableUiState.value.copy(validationResult = null)
        }
    }

    fun endValidatedContinuation(uiSessionId: Long) {
        if (pendingValidatedUiSessionId == uiSessionId) {
            pendingValidatedUiSessionId = null
        }
    }

    fun clearValidation() {
        mutableUiState.value = mutableUiState.value.copy(validationResult = null)
    }

    fun acknowledgeNotice(id: Long) {
        if (mutableUiState.value.notice?.id == id) {
            mutableUiState.value = mutableUiState.value.copy(notice = null)
        }
    }

    override fun onCleared() {
        ownerCleared = true
        activeUiSessionId = null
        pendingValidatedUiSessionId = null
        activeValidationRequestId = null
        activeValidationUiSessionId = null
        validationJob = null
        mutableUiState.value = mutableUiState.value.copy(
            operation = DataAndBackupOperation.Idle,
            validationResult = null
        )
        super.onCleared()
    }

    private fun launchOperation(
        operation: DataAndBackupOperation,
        block: suspend () -> String?
    ) {
        if (mutableUiState.value.operationInProgress) return
        mutableUiState.value = mutableUiState.value.copy(operation = operation)
        viewModelScope.launch {
            var succeeded = false
            var result: String? = null
            try {
                result = block()
                succeeded = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                succeeded = false
            } finally {
                mutableUiState.value = mutableUiState.value.copy(
                    operation = DataAndBackupOperation.Idle
                )
            }
            publishNotice(operation, succeeded, result)
        }
    }

    private fun publishNotice(
        operation: DataAndBackupOperation,
        success: Boolean,
        detail: String? = null,
        requiresBackupReselection: Boolean = false
    ) {
        if (ownerCleared) return
        mutableUiState.value = mutableUiState.value.copy(
            notice = DataAndBackupNotice(
                id = newId(),
                operation = operation,
                success = success,
                detail = detail,
                requiresBackupReselection = requiresBackupReselection
            )
        )
    }

    private fun newId(): Long = ++nextId
}
