package com.bks.ui.entry

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bks.data.local.FlowDirection
import com.bks.data.local.TransactionKind
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.TextButton
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
internal fun SharedLedgerEntryForm(
    title: String,
    initial: LedgerEntryFormState,
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    config: LedgerEntryFormConfig
) {
    var state by rememberSaveable(initial, stateSaver = LedgerEntryFormState.Saver) {
        mutableStateOf(initial)
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val categoryOptions = remember(categories, state.flowDirection, state.transactionKind) {
        ledgerCategoryOptions(categories, state.flowDirection, state.transactionKind)
    }

    LaunchedEffect(categoryOptions, state.categoryId) {
        if (categoryOptions.none { it.id == state.categoryId }) {
            state = state.copy(categoryId = UNCATEGORIZED_ENTRY_CATEGORY_ID)
        }
    }

    fun requestExit() {
        if (state != initial) confirmDiscard = true else config.onExit()
    }

    fun saveEntry() {
        scope.launch {
            runCatching { state.toSubmission(System.currentTimeMillis()) }
                .mapCatching { submission -> config.onSave(submission) }
                .onFailure { config.snackbarHostState.showSnackbar(it.userMessage()) }
        }
    }

    BackHandler { requestExit() }

    Column(modifier = Modifier.fillMaxSize()) {
        ManualEntryHeader(title = title, onBack = ::requestExit)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            config.leadingContent(state) { state = it }
            ManualAmountCard(
                state = state,
                directions = config.flowDirections,
                onStateChange = { state = it }
            )
            ManualTransactionCard(
                state = state,
                categoryOptions = categoryOptions,
                onStateChange = { state = it },
                onSelectTime = {
                    showDateTimePicker(context, state.transactionTimeEpochMillis) {
                        state = state.copy(transactionTimeEpochMillis = it)
                    }
                }
            )
            ManualAccountCard(
                state = state,
                fundingAccounts = fundingAccounts,
                allowCreateFundingAccount = config.allowCreateFundingAccount,
                onStateChange = { state = it }
            )
            if (config.showDefaultFundingAccountHint) {
                Text(
                    "保存时将优先使用默认资金账户",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (config.onDelete != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-entry-delete")
                ) {
                    Text("删除账目")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        ManualEntryActions(
            saveLabel = config.saveLabel,
            onCancel = ::requestExit,
            onSave = ::saveEntry
        )
    }

    DiscardChangesDialog(
        visible = confirmDiscard,
        onDismiss = { confirmDiscard = false },
        onDiscard = config.onExit
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账？") },
            text = { Text("账目将移入最近删除，可在 30 天内恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        config.onDelete?.invoke()
                    }
                ) { Text("移入最近删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

internal fun ledgerCategoryOptions(
    categories: List<LedgerCategoryUiModel>,
    flowDirection: FlowDirection,
    transactionKind: TransactionKind
): List<LedgerCategoryUiModel> {
    val matchingCategories = categories.filter { category ->
        when {
            category.kind == null -> true
            transactionKind == TransactionKind.REFUND -> category.kind == TransactionKind.REFUND
            flowDirection == FlowDirection.INFLOW -> category.kind == TransactionKind.INCOME
            flowDirection == FlowDirection.OUTFLOW -> category.kind == TransactionKind.EXPENSE
            else -> true
        }
    }
    return (matchingCategories + LedgerCategoryUiModel(
        id = UNCATEGORIZED_ENTRY_CATEGORY_ID,
        name = "未分类",
        kind = null
    )).distinctBy { it.id }
}

@Composable
private fun DiscardChangesDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("放弃未保存的修改？") },
        text = { Text("离开后，本次修改不会保存。") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDiscard()
                }
            ) { Text("放弃修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续编辑") } }
    )
}

private fun showDateTimePicker(
    context: android.content.Context,
    currentEpochMillis: Long,
    onSelected: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val current = Instant.ofEpochMilli(currentEpochMillis).atZone(zoneId)
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        LocalDateTime.of(year, month + 1, day, hour, minute)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    )
                },
                current.hour,
                current.minute,
                true
            ).show()
        },
        current.year,
        current.monthValue - 1,
        current.dayOfMonth
    )
    dateDialog.datePicker.maxDate = System.currentTimeMillis()
    dateDialog.show()
}

private fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
