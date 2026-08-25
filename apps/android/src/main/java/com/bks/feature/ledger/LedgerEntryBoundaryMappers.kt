package com.bks.feature.ledger

import com.bks.data.local.LedgerEntryInput
import com.bks.data.local.DefaultCategories
import com.bks.ui.entry.LedgerCategoryUiModel
import com.bks.ui.entry.LedgerEntryFormState
import com.bks.ui.entry.LedgerEntryFormSubmission
import com.bks.ui.entry.UNCATEGORIZED_ENTRY_CATEGORY_ID
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

internal fun LedgerEntryFormSubmission.toLedgerEntryInput(): LedgerEntryInput = LedgerEntryInput(
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
