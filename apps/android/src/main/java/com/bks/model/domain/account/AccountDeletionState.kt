package com.bks.model.domain.account

data class AccountDeletionState(
    val requestedAtEpochMillis: Long? = null,
    val finalDeletionAtEpochMillis: Long? = null
) {
    val isPending: Boolean
        get() = requestedAtEpochMillis != null && finalDeletionAtEpochMillis != null

    val cloudWritesAllowed: Boolean
        get() = !isPending
}
