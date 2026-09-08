package com.bks.model.platform.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bks.model.domain.notifications.AutomaticBookkeepingHealth
import com.bks.model.platform.billsync.BillSyncServiceHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookkeepingHealthObserverTest {
    @Test
    fun readsInitialHealthObservesLifecycleAndUnregistersWithoutChangingUserIntent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val enabled = MutableStateFlow(true)
        BillSyncServiceHealth.markServiceConnected(true)
        val seen = mutableListOf<AutomaticBookkeepingHealth>()
        val job = backgroundScope.launch { BookkeepingHealthObserver(context, enabled).observe().collect { seen += it } }
        runCurrent()
        assertTrue(seen.last().connected)
        BillSyncServiceHealth.markServiceConnected(false)
        runCurrent()
        assertFalse(seen.last().connected)
        assertTrue(enabled.value)
        enabled.value = false
        runCurrent()
        assertFalse(seen.last().enabled)
        job.cancel()
        runCurrent()
        val size = seen.size
        BillSyncServiceHealth.markServiceConnected(true)
        runCurrent()
        assertEquals(size, seen.size)
        BillSyncServiceHealth.markServiceConnected(false)
    }
}
