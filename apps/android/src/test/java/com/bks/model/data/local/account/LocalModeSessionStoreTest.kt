package com.bks.model.data.local.account

import com.bks.model.data.local.account.LOCAL_MODE_SESSION_PREFERENCES
import com.bks.model.data.local.account.LocalModeSessionStore
import com.bks.model.data.local.account.signOutToLocalMode
import com.bks.model.domain.account.AccountSession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalModeSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearSession() {
        clearPersistedSession()
    }

    @After
    fun cleanUpSession() {
        clearPersistedSession()
    }

    @Test
    fun confirmedLocalModeSurvivesStoreRecreation() {
        val initialStore = LocalModeSessionStore(context)
        assertNull(initialStore.restoreSession())

        assertTrue(initialStore.confirmLocalMode())

        assertEquals(AccountSession.LocalMode, LocalModeSessionStore(context).restoreSession())
    }

    @Test
    fun signingOutTransitionsToAndPersistsLocalMode() {
        val initialStore = LocalModeSessionStore(context)

        assertEquals(AccountSession.LocalMode, signOutToLocalMode(initialStore))

        assertEquals(AccountSession.LocalMode, LocalModeSessionStore(context).restoreSession())
    }

    private fun clearPersistedSession() {
        context.getSharedPreferences(
            LOCAL_MODE_SESSION_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
