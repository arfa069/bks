package com.bks.viewmodel.settings

data class LedgerCsvEntryUiModel(
    val id: String,
    val transactionTimeText: String,
    val title: String,
    val amountMinor: Long,
    val flowType: String,
    val category: String,
    val sourceLabel: String,
    val kindLabel: String,
    val note: String?
)
