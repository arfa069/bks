package com.bks.model.platform.billsync

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWindowTrackerTest {
    @Test
    fun successfulWindowRemainsSuppressedUntilReleaseIsRequested() {
        val tracker = CaptureWindowTracker()

        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
        tracker.persistenceSucceeded(PACKAGE_NAME, WINDOW_ID)
        assertFalse(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))

        tracker.release(PACKAGE_NAME, WINDOW_ID)
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
    }

    @Test
    fun failureReleasesWindowAndCancellationKeepsSuccessfulSuppression() {
        val tracker = CaptureWindowTracker()

        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
        tracker.persistenceFailed(PACKAGE_NAME, WINDOW_ID)
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))

        tracker.persistenceCancelled(PACKAGE_NAME, WINDOW_ID)
        assertFalse(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
    }

    @Test
    fun newPaymentWhilePersistenceIsInFlightReleasesOnlyAfterCompletion() {
        val tracker = CaptureWindowTracker()
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
        tracker.releaseForNewPayment(PACKAGE_NAME)
        assertFalse(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))

        tracker.persistenceSucceeded(PACKAGE_NAME, WINDOW_ID)
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
    }

    @Test
    fun newPaymentDoesNotReleaseAnotherPackage() {
        val tracker = CaptureWindowTracker()
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
        tracker.persistenceSucceeded(PACKAGE_NAME, WINDOW_ID)
        tracker.releaseForNewPayment("other.package")
        assertFalse(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))
    }

    @Test
    fun latestDeferredCaptureIsMergedAndReturnedOnce() {
        val tracker = CaptureWindowTracker()
        assertTrue(tracker.acceptIfNew(PACKAGE_NAME, WINDOW_ID))

        assertTrue(tracker.deferIfPersistenceInFlight(batch(1L)))
        assertTrue(tracker.deferIfPersistenceInFlight(batch(2L)))
        tracker.persistenceSucceeded(PACKAGE_NAME, WINDOW_ID)

        val deferred = tracker.takeDeferredCapture(PACKAGE_NAME, WINDOW_ID)
        assertEquals(2, deferred?.eventTypeCounts?.get(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertNull(tracker.takeDeferredCapture(PACKAGE_NAME, WINDOW_ID))
    }

    private fun batch(timestamp: Long) = BillSyncCaptureBatch(
        trigger = CaptureTrigger(PACKAGE_NAME, WINDOW_ID),
        timestampEpochMillis = timestamp,
        eventTypeCounts = mapOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED to 1)
    )

    private companion object {
        const val PACKAGE_NAME = ALIPAY_PACKAGE_NAME
        const val WINDOW_ID = 7
    }
}
