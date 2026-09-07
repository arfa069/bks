package com.bks.viewmodel.settings

data class LocalDataDeletionState(
    val backupReminderAccepted: Boolean = false,
    val confirmationText: String = ""
) {
    val canDelete: Boolean
        get() = backupReminderAccepted && confirmationText == DELETE_LOCAL_DATA_PHRASE
}

sealed interface LocalDataDeletionAction {
    data class SetBackupReminderAccepted(val accepted: Boolean) : LocalDataDeletionAction
    data class UpdateConfirmationText(val text: String) : LocalDataDeletionAction
}

fun reduceLocalDataDeletionState(
    state: LocalDataDeletionState,
    action: LocalDataDeletionAction
): LocalDataDeletionState = when (action) {
    is LocalDataDeletionAction.SetBackupReminderAccepted -> state.copy(
        backupReminderAccepted = action.accepted
    )
    is LocalDataDeletionAction.UpdateConfirmationText -> state.copy(
        confirmationText = action.text
    )
}

const val DELETE_LOCAL_DATA_PHRASE = "删除本机数据"
