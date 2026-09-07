package com.bks.model.data.local.ledger

import com.bks.model.data.local.database.LedgerEntryEntity
import com.bks.model.domain.ledger.EntryOrigin
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerEntityMappersTest {
    @Test
    fun ledgerEntryRoundTripPreservesNullableFields() {
        val entity = LedgerEntryEntity(
            id = "entry-1",
            ledgerBookId = "ledger-1",
            paymentSource = null,
            originalCaptureSource = null,
            entryOrigin = EntryOrigin.MANUAL,
            originPendingEntryId = null,
            flowDirection = FlowDirection.OUTFLOW,
            transactionKind = TransactionKind.EXPENSE,
            amountMinor = 1_200,
            currency = "CNY",
            merchantTitle = "午餐",
            transactionTimeEpochMillis = 1_000,
            categoryId = null,
            fundingAccountId = null,
            note = null,
            evidenceSummary = null,
            parsedFieldsText = null,
            confirmedAtEpochMillis = 2_000,
            updatedAtEpochMillis = 2_000,
            deletedAtEpochMillis = null
        )

        val domain = entity.toDomain()

        assertEquals(entity, domain.toEntity())
        assertNull(domain.paymentSource)
        assertNull(domain.categoryId)
        assertNull(domain.fundingAccountId)
    }

    @Test
    fun emptyEntityListMapsToEmptyDomainList() {
        val mapped = emptyList<LedgerEntryEntity>().map(LedgerEntryEntity::toDomain)

        assertTrue(mapped.isEmpty())
    }
}
