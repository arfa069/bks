package com.bks.model.platform.billsync

import com.bks.model.domain.billsync.BillSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Enrich only an admitted, not-yet-persisted capture; never rewrite a user's saved entry. */
internal suspend fun completeMissingMerchant(
    original: BillSyncResult,
    readCurrent: suspend () -> BillSyncResult?
): BillSyncResult {
    if (!original.merchantMissing) return original
    val first = original.createdEntries.singleOrNull() ?: return original
    repeat(3) {
        delay(400)
        val latest = try {
            readCurrent()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return original
        val entry = latest.createdEntries.singleOrNull() ?: return original
        if (!latest.recognized || entry.source != first.source) return original
        if (entry.amountMinor != first.amountMinor || entry.currency != first.currency ||
            entry.transactionKind != first.transactionKind) return original
        if (!latest.merchantMissing) {
            return original.copy(
                merchantMissing = false,
                createdEntries = listOf(first.copy(
                    merchantTitle = entry.merchantTitle,
                    note = entry.note,
                    parsedFieldsText = entry.parsedFieldsText
                ))
            )
        }
    }
    return original
}
