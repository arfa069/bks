package com.bks.viewmodel.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticBookkeepingViewModelTest {
    @Test
    fun optimisticTargetBlocksAnotherWriteUntilRoomConfirms() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val enabled = MutableStateFlow(false)
            val releaseWrite = CompletableDeferred<Unit>()
            val writes = mutableListOf<Boolean>()
            val viewModel = AutomaticBookkeepingViewModel(enabled) { target ->
                writes += target
                releaseWrite.await()
                enabled.value = target
            }
            advanceUntilIdle()

            viewModel.setEnabled(true)
            assertEquals(AutomaticBookkeepingFeatureState(true, true), viewModel.uiState.value)
            advanceUntilIdle()

            viewModel.setEnabled(false)
            assertEquals(AutomaticBookkeepingFeatureState(true, true), viewModel.uiState.value)

            releaseWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(true), writes)
            assertEquals(AutomaticBookkeepingFeatureState(true, false), viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedWriteRollsBackToPersistedIntentAndEmitsNotice() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val enabled = MutableStateFlow(false)
            val viewModel = AutomaticBookkeepingViewModel(enabled) {
                error("write failed")
            }
            advanceUntilIdle()

            viewModel.setEnabled(true)
            assertTrue(viewModel.uiState.value.enabled)
            assertTrue(viewModel.uiState.value.writeInProgress)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.enabled)
            assertFalse(viewModel.uiState.value.writeInProgress)
            assertEquals("保存自动记账设置失败，请重试", viewModel.notices.first())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun configurationRecreationRetainsPendingWriteState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val enabled = MutableStateFlow(false)
            val releaseWrite = CompletableDeferred<Unit>()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AutomaticBookkeepingViewModel(enabled) { target ->
                        releaseWrite.await()
                        enabled.value = target
                    } as T
            }
            val firstOwner = ViewModelProvider(store, factory)[AutomaticBookkeepingViewModel::class.java]
            advanceUntilIdle()
            firstOwner.setEnabled(true)
            advanceUntilIdle()

            val recreatedOwner = ViewModelProvider(store, factory)[AutomaticBookkeepingViewModel::class.java]

            assertSame(firstOwner, recreatedOwner)
            assertEquals(AutomaticBookkeepingFeatureState(true, true), recreatedOwner.uiState.value)
            releaseWrite.complete(Unit)
            advanceUntilIdle()
            assertEquals(AutomaticBookkeepingFeatureState(true, false), recreatedOwner.uiState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
