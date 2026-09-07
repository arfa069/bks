package com.bks.viewmodel.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataDeletionConfirmationTest {
    @Test
    fun localDeletionRequiresReminderAndTypedPhrase() {
        val reminderOnly = reduceLocalDataDeletionState(
            LocalDataDeletionState(),
            LocalDataDeletionAction.SetBackupReminderAccepted(true)
        )
        val wrongPhrase = reduceLocalDataDeletionState(
            reminderOnly,
            LocalDataDeletionAction.UpdateConfirmationText("delete data")
        )
        val exactPhrase = reduceLocalDataDeletionState(
            wrongPhrase,
            LocalDataDeletionAction.UpdateConfirmationText(DELETE_LOCAL_DATA_PHRASE)
        )

        assertFalse(wrongPhrase.canDelete)
        assertTrue(exactPhrase.canDelete)
    }
}
