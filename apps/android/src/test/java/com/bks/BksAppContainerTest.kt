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
        val firstDependencies = container.createAppDependencies(BksAppOverrides())
        val secondDependencies = container.createAppDependencies(BksAppOverrides())

        assertSame(container, application.appContainer)
        assertSame(container.database, firstDependencies.local.database)
        assertSame(firstDependencies.local.database, secondDependencies.local.database)
        assertSame(container.ledgerRepository, firstDependencies.local.ledgerRepository)
        assertSame(container.preferencesRepository, firstDependencies.local.preferencesRepository)
        assertSame(container.accountSessionCoordinator, firstDependencies.account.sessionCoordinator)
        assertSame(firstDependencies.account.sessionCoordinator, secondDependencies.account.sessionCoordinator)
        assertSame(
            container.accountCloudSettingsCoordinator,
            firstDependencies.account.cloudSettingsCoordinator
        )
        assertSame(
            firstDependencies.account.cloudSettingsCoordinator,
            secondDependencies.account.cloudSettingsCoordinator
        )
        assertSame(container.ledgerSyncCoordinator, firstDependencies.sync.coordinator)
        assertSame(firstDependencies.sync.coordinator, secondDependencies.sync.coordinator)
        assertSame(container.ledgerSyncRuntime, firstDependencies.sync.runtime)
        assertSame(firstDependencies.sync.runtime, secondDependencies.sync.runtime)
    }

    @Test
    fun overridesReuseTheProductionDatabaseGraph() {
        val container = application.appContainer
        val accountRepository = FakeAccountRepository()

        val dependencies = container.createAppDependencies(
            BksAppOverrides(accountRepository = accountRepository)
        )

        assertSame(accountRepository, dependencies.account.accountRepository)
        assertNotSame(container.accountSessionCoordinator, dependencies.account.sessionCoordinator)
        assertNotSame(
            container.accountCloudSettingsCoordinator,
            dependencies.account.cloudSettingsCoordinator
        )
        assertSame(container.database, dependencies.local.database)
        assertSame(container.ledgerRepository, dependencies.local.ledgerRepository)
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
            container.createAppDependencies(BksAppOverrides()).sync.coordinator,
            container.createAppDependencies(BksAppOverrides()).sync.coordinator
        )
    }
}
