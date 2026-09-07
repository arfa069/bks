package com.bks.viewmodel.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerFormattingTest {
    @Test
    fun amountFormattingPreservesMinorUnitContract() {
        assertEquals("¥45.80", formatMoney(4_580L))
        assertEquals("45.80", amountMinorToText(4_580L))
        assertEquals("-¥45.80", formatSignedMoney(-4_580L))
    }

    @Test
    fun amountParsingRoundsHalfUpAndRejectsInvalidValues() {
        assertEquals(4_581L, parseAmountMinor("45.805"))
        assertNull(parseAmountMinor("abc"))
    }
}
