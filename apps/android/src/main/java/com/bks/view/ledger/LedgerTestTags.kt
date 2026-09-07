package com.bks.view.ledger

internal object LedgerTestTags {
    const val ENTRY_LIST = "ledger-entry-list"
    const val SEARCH_FIELD = "ledger-search-field"
    const val FILTER_BUTTON = "ledger-filter-button"
    const val MORE_MENU = "ledger-more"
    const val MANAGE_LEDGERS = "ledger-manage-ledgers"
    const val MANAGE_FUNDING_ACCOUNTS = "ledger-manage-funding-accounts"
    const val RECENTLY_DELETED = "ledger-recently-deleted"
    const val ADD_LEDGER = "ledger-add"
    const val LEDGER_NAME = "ledger-name"
    const val CONFIRM_ADD_LEDGER = "ledger-confirm-add"
    const val CONFIRM_DELETE_LEDGER = "ledger-confirm-delete"
    const val ADD_FUNDING_ACCOUNT = "funding-account-add"
    const val FUNDING_ACCOUNT_LABEL = "funding-account-label"
    const val FUNDING_ACCOUNT_SOURCE = "funding-account-source"
    const val SAVE_FUNDING_ACCOUNT = "funding-account-save"
    const val CONFIRM_DELETE_FUNDING_ACCOUNT = "funding-account-confirm-delete"

    fun ledgerBook(id: String): String = "ledger-book-$id"
    fun selectLedger(id: String): String = "ledger-select-$id"
    fun deleteLedger(id: String): String = "ledger-delete-$id"
    fun fundingAccount(id: Long): String = "funding-account-$id"
    fun editFundingAccount(id: Long): String = "funding-account-edit-$id"
    fun deleteFundingAccount(id: Long): String = "funding-account-delete-$id"
}
