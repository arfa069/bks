package com.bks.model.platform.app

import com.bks.model.application.account.AccountCloudSettingsCoordinator
import com.bks.model.application.account.AccountSessionCoordinator
import com.bks.model.platform.billsync.BillSyncCaptureCoordinator
import com.bks.model.data.local.diagnostics.DiagnosticLogs
import com.bks.model.application.sync.LedgerSyncRuntime
import android.app.Application
import com.bks.model.domain.diagnostics.DiagnosticComponent
import com.bks.model.domain.diagnostics.DiagnosticEvent
import com.bks.model.domain.diagnostics.DiagnosticEventMetadata
import com.bks.model.domain.diagnostics.DiagnosticLevel
import com.bks.model.domain.diagnostics.DiagnosticSensitiveField
import com.bks.model.domain.diagnostics.DiagnosticSensitivePayload
import com.bks.model.domain.diagnostics.DiagnosticSource
import com.bks.model.domain.diagnostics.toDiagnosticExceptionDetails
import com.ven.assists.AssistsCore

class BksApplication : Application() {
    internal val appContainer: BksAppContainer by lazy { BksAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        appContainer
        appContainer.accountSessionCoordinator.start()
        appContainer.accountCloudSettingsCoordinator.start()
        appContainer.ledgerSyncRuntime.start()
        AssistsCore.init(this)
        appContainer.billSyncCaptureCoordinator.start()
        installDiagnosticCrashHandler()
    }

    private fun installDiagnosticCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                appContainer.diagnosticLogs.record(
                    DiagnosticEvent(
                        metadata = DiagnosticEventMetadata(
                            level = DiagnosticLevel.Error,
                            component = DiagnosticComponent.Application,
                            event = "uncaught_exception",
                            source = DiagnosticSource.System,
                            outcome = "failed",
                            reason = "uncaught_exception"
                        ),
                        sensitivePayload = DiagnosticSensitivePayload(
                            mapOf(
                                DiagnosticSensitiveField.ExceptionDetails to
                                    "thread=${thread.name}\n${throwable.toDiagnosticExceptionDetails()}"
                            )
                        )
                    )
                )
            }
            original?.uncaughtException(thread, throwable)
        }
    }
}
