package com.bks.view.common.entry

import androidx.compose.runtime.saveable.listSaver
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.LedgerEntryFormState

internal val LedgerEntryFormStateSaver = listSaver<LedgerEntryFormState, Any>(
    save = { state ->
        listOf(
            state.flowDirection.name,
            state.transactionKind.name,
            state.amountText,
            state.transactionTimeEpochMillis,
            state.merchantTitle,
            state.categoryId,
            state.fundingAccountId ?: NO_FUNDING_ACCOUNT_ID,
            state.creatingFundingAccount,
            state.newFundingAccountLabel,
            state.note,
            state.paymentSource?.name.orEmpty()
        )
    },
    restore = { values ->
        LedgerEntryFormState(
            flowDirection = FlowDirection.valueOf(values[0] as String),
            transactionKind = TransactionKind.valueOf(values[1] as String),
            amountText = values[2] as String,
            transactionTimeEpochMillis = values[3] as Long,
            merchantTitle = values[4] as String,
            categoryId = values[5] as String,
            fundingAccountId = (values[6] as Long).takeUnless { it == NO_FUNDING_ACCOUNT_ID },
            creatingFundingAccount = values[7] as Boolean,
            newFundingAccountLabel = values[8] as String,
            note = values[9] as String,
            paymentSource = (values[10] as String)
                .takeIf(String::isNotEmpty)
                ?.let(PaymentSource::valueOf)
        )
    }
)

private const val NO_FUNDING_ACCOUNT_ID = Long.MIN_VALUE
