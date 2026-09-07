package com.bks.viewmodel.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerCsvEncoderTest {
    @Test
    fun csvExportUsesStableSpreadsheetSchema() {
        val csv = exportLedgerCsv(
            listOf(
                LedgerCsvEntryUiModel(
                    id = "ledger-1",
                    title = "Lunch",
                    amountMinor = 3590,
                    transactionTimeText = "2026-07-08 12:20",
                    category = "Food",
                    sourceLabel = "WeChat",
                    kindLabel = "Expense",
                    flowType = "EXPENSE",
                    note = "Client meeting"
                )
            )
        )

        assertEquals(
            "id,transaction_time,title,amount,flow_type,category,source,transaction_kind,note\n" +
                "ledger-1,2026-07-08 12:20,Lunch,35.90,EXPENSE,Food,WeChat,Expense,Client meeting",
            csv
        )
    }

    @Test
    fun csvFilenameIncludesSanitizedCurrentLedgerName() {
        assertEquals(
            "2026-07-16-14-30-家庭_日常-ledger.csv",
            ledgerCsvFilename(" 家庭/日常 ", "2026-07-16-14-30")
        )
    }
}
