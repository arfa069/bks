package com.bks

import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.bks.feature.account.FakeAccountRepository
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BksAppContainerTest {
    private val application = ApplicationProvider.getApplicationContext<BksApplication>()

    @Test
    fun applicationOwnsOneContainerAndOneDatabaseGraph() {
        val container = application.appContainer
        val overrides = BksAppOverrides()

        assertSame(container, application.appContainer)
        assertSame(
            container.database,
            application.appContainer.database
        )
        assertSame(
            container.ledgerRepository,
            application.appContainer.ledgerRepository
        )
        assertSame(
            container.preferencesRepository,
            application.appContainer.preferencesRepository
        )
        assertSame(
            container.accountSessionCoordinator,
            container.resolveAccountSessionCoordinator(overrides)
        )
        assertSame(
            container.accountCloudSettingsCoordinator,
            container.resolveAccountCloudSettingsCoordinator(
                overrides,
                container.accountSessionCoordinator
            )
        )
        assertSame(
            container.ledgerSyncRuntime,
            container.resolveLedgerSyncRuntime(overrides, container.accountSessionCoordinator)
        )
    }

    @Test
    fun overridesReuseTheProductionDatabaseGraph() {
        val container = application.appContainer
        val accountRepository = FakeAccountRepository()

        val overrides = BksAppOverrides(accountRepository = accountRepository)
        val sessionCoordinator = container.resolveAccountSessionCoordinator(overrides)
        val cloudSettingsCoordinator = container.resolveAccountCloudSettingsCoordinator(
            overrides,
            sessionCoordinator
        )
        val syncRuntime = container.resolveLedgerSyncRuntime(overrides, sessionCoordinator)

        assertNotSame(container.accountSessionCoordinator, sessionCoordinator)
        assertNotSame(
            container.accountCloudSettingsCoordinator,
            cloudSettingsCoordinator
        )
        assertNotSame(container.ledgerSyncRuntime, syncRuntime)
        assertSame(container.database, application.appContainer.database)
        assertSame(container.ledgerRepository, application.appContainer.ledgerRepository)
    }

    @Test
    fun lifecycleFactoriesCreateDistinctOwners() {
        val container = application.appContainer
        val activity = ComponentActivity()

        assertNotSame(
            container.createBillSyncCaptureProcessor(),
            container.createBillSyncCaptureProcessor()
        )
        assertNotSame(
            container.createBillSyncStateCoordinator(activity),
            container.createBillSyncStateCoordinator(activity)
        )
        assertSame(
            container.ledgerSyncCoordinator,
            application.appContainer.ledgerSyncCoordinator
        )
    }
}
