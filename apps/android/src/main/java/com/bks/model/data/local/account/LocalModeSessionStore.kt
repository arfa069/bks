package com.bks.model.data.local.account

import com.bks.model.domain.account.AccountSession
import com.bks.model.domain.account.LocalModeStore
import android.content.Context

internal const val LOCAL_MODE_SESSION_PREFERENCES = "local_mode_session"

internal class LocalModeSessionStore(context: Context) : LocalModeStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        LOCAL_MODE_SESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )

    override fun restoreSession(): AccountSession? =
        AccountSession.LocalMode.takeIf {
            preferences.getBoolean(LOCAL_MODE_CONFIRMED_KEY, false)
        }

    override fun confirmLocalMode(): Boolean = preferences.edit()
        .putBoolean(LOCAL_MODE_CONFIRMED_KEY, true)
        .commit()

    private companion object {
        const val LOCAL_MODE_CONFIRMED_KEY = "confirmed"
    }
}

internal fun signOutToLocalMode(sessionStore: LocalModeSessionStore): AccountSession {
    sessionStore.confirmLocalMode()
    return AccountSession.LocalMode
}
