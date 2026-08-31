package com.bks.model.domain.settings

const val MIN_BACKUP_PASSPHRASE_LENGTH = 9

fun isEncryptedLocalDataBackup(backupText: String): Boolean =
    backupText.startsWith("AUTO_ACCOUNTING_BACKUP_V2:") ||
        backupText.startsWith("AUTO_ACCOUNTING_BACKUP_V3:") ||
        backupText.startsWith("AUTO_ACCOUNTING_BACKUP_V4:") ||
        backupText.startsWith("AUTO_ACCOUNTING_BACKUP_V5:")
