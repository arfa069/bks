package com.bks.view.app

import com.bks.viewmodel.sync.LedgerSyncActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BksDialogsTest {
    @Test
    fun switchAccountSuccessShowsSuccessMessage() = runTest {
        val messages = mutableListOf<String>()

        runLedgerSyncAccountSwitchAction(
            switchAccount = { LedgerSyncActionResult.Success(Unit) },
            showMessage = { message -> messages += message }
        )

        assertEquals(listOf("账户同步数据已切换"), messages)
    }

    @Test
    fun switchAccountFailureShowsFailureMessage() = runTest {
        val messages = mutableListOf<String>()

        runLedgerSyncAccountSwitchAction(
            switchAccount = {
                LedgerSyncActionResult.Failure("切换失败")
            },
            showMessage = { message -> messages += message }
        )

        assertEquals(listOf("切换失败"), messages)
    }

    @Test
    fun cancelSignedInAccountLaunchesSignOutBeforeMovingToLocalMode() {
        val actions = mutableListOf<String>()

        cancelLedgerSyncAccountSwitch(
            signedInToken = "token-a",
            launchSignOut = { token -> actions += "sign-out:$token" },
            moveToLocalMode = { actions += "local-mode" }
        )

        assertEquals(listOf("sign-out:token-a", "local-mode"), actions)
    }

    @Test
    fun cancelLocalAccountOnlyMovesToLocalMode() {
        val actions = mutableListOf<String>()

        cancelLedgerSyncAccountSwitch(
            signedInToken = null,
            launchSignOut = { token -> actions += "sign-out:$token" },
            moveToLocalMode = { actions += "local-mode" }
        )

        assertEquals(listOf("local-mode"), actions)
    }
}
