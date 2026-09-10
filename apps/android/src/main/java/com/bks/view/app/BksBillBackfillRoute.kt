package com.bks.view.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bks.model.platform.app.bksAppContainer
import com.bks.model.platform.billsync.BillSyncPermission
import com.bks.view.review.BillBackfillCard
import com.bks.viewmodel.review.BillBackfillViewModel
import kotlinx.coroutines.launch

@Composable
internal fun BksBillBackfillRoute(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BillBackfillViewModel(context.bksAppContainer.billBackfillCoordinator) as T
        }
    }
    val viewModel: BillBackfillViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    BillBackfillCard(
        state = state,
        onStart = viewModel::start,
        onFinish = viewModel::finish,
        onOpenAccessibilitySettings = {
            runCatching { context.startActivity(BillSyncPermission.settingsIntent()) }
                .onFailure { scope.launch { snackbarHostState.showSnackbar("无法打开系统设置，请手动开启无障碍服务") } }
        }
    )
}
