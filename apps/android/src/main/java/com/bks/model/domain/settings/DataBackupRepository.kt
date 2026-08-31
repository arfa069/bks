package com.bks.model.domain.settings

interface DataBackupRepository {
    suspend fun exportEncryptedBackup(passphrase: String): String
    suspend fun importEncryptedBackup(backupText: String, passphrase: String)
    suspend fun validateEncryptedBackup(backupText: String, passphrase: String)
}
