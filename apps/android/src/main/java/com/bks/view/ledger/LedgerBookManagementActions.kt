package com.bks.view.ledger

import com.bks.viewmodel.ledger.LedgerBookDeleteResult

internal data class LedgerBookManagementActions(
    val onBack: () -> Unit,
    val onCreateLedger: suspend (String) -> Unit,
    val onSelectLedger: suspend (String) -> Unit,
    val onDeleteLedger: suspend (String) -> LedgerBookDeleteResult
)
