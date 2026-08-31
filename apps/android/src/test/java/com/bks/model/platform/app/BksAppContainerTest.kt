package com.bks.model.platform.app

import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountSessionCoordinator
import com.bks.model.application.sync.LedgerSyncCoordinator
import com.bks.model.application.sync.LedgerSyncRuntime
import com.bks.model.domain.categorization.CloudAiSettingsGateway
import com.bks.model.domain.ledger.LedgerRepository
import com.bks.model.domain.settings.PreferencesRepository
import com.bks.model.platform.app.BksApplication
import com.bks.model.domain.account.TestAccountRepository
import androidx.test.core.app.ApplicationProvider
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
    fun applicationOwnsOneContainerAndOneModelGraph() {
        val container = application.appContainer

        assertSame(container, application.appContainer)
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
            application.appContainer.accountSessionCoordinator
        )
        assertSame(
            container.accountCloudSettingsCoordinator,
            application.appContainer.accountCloudSettingsCoordinator
        )
        assertSame(
            container.ledgerSyncRuntime,
            application.appContainer.ledgerSyncRuntime
        )
    }

    @Test
    fun compositionFactoriesReuseModelRepositoriesAndCreateDistinctCoordinators() {
        val container = application.appContainer
        val sessionCoordinator = container.createAccountSessionCoordinator(
            repository = TestAccountRepository()
        )
        val cloudSettingsCoordinator = container.createAccountCloudSettingsCoordinator(
            sessionCoordinator = sessionCoordinator,
            gateway = container.cloudAiSettingsGateway
        )
        val syncRuntime = container.createLedgerSyncRuntime(sessionCoordinator)

        assertNotSame(container.accountSessionCoordinator, sessionCoordinator)
        assertNotSame(
            container.accountCloudSettingsCoordinator,
            cloudSettingsCoordinator
        )
        assertNotSame(container.ledgerSyncRuntime, syncRuntime)
        assertSame(container.ledgerRepository, application.appContainer.ledgerRepository)
        assertSame(container.preferencesRepository, application.appContainer.preferencesRepository)
    }

    @Test
    fun lifecycleFactoriesCreateDistinctOwners() {
        val container = application.appContainer

        assertNotSame(
            container.createBillSyncCaptureProcessor(),
            container.createBillSyncCaptureProcessor()
        )
        assertSame(
            container.ledgerSyncCoordinator,
            application.appContainer.ledgerSyncCoordinator
        )
    }
}
