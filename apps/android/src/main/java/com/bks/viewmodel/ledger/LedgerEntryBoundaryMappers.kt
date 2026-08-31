package com.bks.viewmodel.ledger

import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.ledger.LedgerUiEntry
import com.bks.model.domain.ledger.LedgerEntryCommand
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.viewmodel.ledger.LedgerEntryFormState
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import com.bks.viewmodel.ledger.UNCATEGORIZED_ENTRY_CATEGORY_ID
import java.math.BigDecimal

internal fun LedgerUiEntry.toLedgerEntryFormState(): LedgerEntryFormState = LedgerEntryFormState(
    flowDirection = flowDirection,
    transactionKind = transactionKind,
    amountText = BigDecimal(amountMinor).movePointLeft(2).toPlainString(),
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    merchantTitle = title,
    categoryId = categoryId ?: UNCATEGORIZED_ENTRY_CATEGORY_ID,
    fundingAccountId = fundingAccountId,
    creatingFundingAccount = false,
    newFundingAccountLabel = "",
    note = note.orEmpty(),
    paymentSource = paymentSource
)

internal fun LedgerEntryFormSubmission.toLedgerEntryCommand(): LedgerEntryCommand = LedgerEntryCommand(
    flowDirection = flowDirection,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    merchantTitle = merchantTitle,
    categoryId = categoryId,
    fundingAccountId = fundingAccountId,
    newFundingAccountLabel = newFundingAccountLabel,
    note = note,
    paymentSource = paymentSource
)

internal fun defaultLedgerCategoryUiModels(): List<LedgerCategoryUiModel> =
    DefaultCategories.systemDefaults(0).map { category ->
        LedgerCategoryUiModel(
            id = category.id,
            name = category.name,
            kind = category.kind
        )
    }
