package com.bks.view.ledger

import com.bks.model.domain.ledger.PaymentSource
import com.bks.viewmodel.ledger.FundingAccountDeleteResult

internal data class FundingAccountManagementActions(
    val onBack: () -> Unit,
    val onCreateFundingAccount: suspend (String, PaymentSource?) -> Unit,
    val onUpdateFundingAccount: suspend (Long, String, PaymentSource?) -> Unit,
    val onSetDefaultFundingAccount: suspend (Long?) -> Unit,
    val onDeleteFundingAccount: suspend (Long) -> FundingAccountDeleteResult
)
