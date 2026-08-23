package com.bks.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.bks.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.bks.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.bks.data.local.LedgerEntryInput
import com.bks.data.local.LedgerRepositoryState
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.feature.account.AccountCloudSettingsCoordinator
import com.bks.feature.account.AccountCloudSettingsOperationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
internal class LedgerViewModel(
    private val repository: LocalLedgerRepository,
    private val cloudSettingsCoordinator: AccountCloudSettingsCoordinator
) : ViewModel() {
    private val repositoryState = repository.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LedgerRepositoryState()
    )
    private val mutableLocalDataReplacementInFlight = MutableStateFlow(false)
    val localDataReplacementInFlight = mutableLocalDataReplacementInFlight.asStateFlow()
    val uiState: StateFlow<LedgerUiState> = combine(
        repositoryState,
        localDataReplacementInFlight
    ) { state, replacementInFlight ->
        if (replacementInFlight) {
            LedgerUiState(localDataReplacementInFlight = true)
        } else {
            state.toUiState()
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LedgerUiState()
        )

    init {
        viewModelScope.launch {
            repository.ensureDefaultLedgerBook()
            repository.seedSystemCategories()
            repository.purgeExpiredDeletedLedgerEntries()
        }
    }

    suspend fun createManualEntry(input: LedgerEntryInput) {
        checkLocalDataAvailable()
        val activeLedgerBookId = uiState.value.activeLedgerBookId
            ?: error("当前账本尚未加载")
        repository.createManualEntry(activeLedgerBookId, input)
    }

    suspend fun updateEntry(id: String, input: LedgerEntryInput) {
        checkLocalDataAvailable()
        repository.updateLedgerEntry(id, input)
    }

    suspend fun deleteEntry(id: String) {
        checkLocalDataAvailable()
        repository.moveLedgerEntryToDeleted(id)
    }

    suspend fun restoreEntry(id: String) {
        checkLocalDataAvailable()
        repository.restoreDeletedLedgerEntry(id)
    }

    suspend fun permanentlyDeleteEntry(id: String) {
        checkLocalDataAvailable()
        repository.permanentlyDeleteLedgerEntry(id)
    }

    suspend fun purgeExpiredEntries() {
        checkLocalDataAvailable()
        repository.purgeExpiredDeletedLedgerEntries()
    }

    suspend fun createLedger(name: String) {
        checkLocalDataAvailable()
        repository.createLedgerBook(name)
    }

    suspend fun selectLedger(id: String) {
        checkLocalDataAvailable()
        repository.selectLedgerBook(id)
    }

    suspend fun deleteLedger(id: String): LedgerBookDeleteResult {
        checkLocalDataAvailable()
        return when (val result = repository.deleteLedgerBook(id)) {
            DataLedgerBookDeleteResult.Deleted -> LedgerBookDeleteResult.Deleted
            DataLedgerBookDeleteResult.LastLedgerBook -> LedgerBookDeleteResult.LastLedger
            is DataLedgerBookDeleteResult.NotEmpty -> LedgerBookDeleteResult.NotEmpty(
                activeEntryCount = result.activeEntryCount,
                deletedEntryCount = result.deletedEntryCount
            )
            DataLedgerBookDeleteResult.NotFound -> error("账本不存在")
        }
    }

    suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?) {
        checkLocalDataAvailable()
        repository.createFundingAccount(label, paymentSource)
    }

    suspend fun updateFundingAccount(
        id: Long,
        label: String,
        paymentSource: PaymentSource?
    ) {
        checkLocalDataAvailable()
        repository.updateFundingAccount(id, label, paymentSource)
    }

    suspend fun setDefaultFundingAccount(id: Long?): String? {
        checkLocalDataAvailable()
        return when (cloudSettingsCoordinator.setDefaultFundingAccount(id)) {
            is AccountCloudSettingsOperationResult.CloudFailure ->
                "默认账户已保存到本机，云端同步失败"
            AccountCloudSettingsOperationResult.CloudWritesBlocked,
            AccountCloudSettingsOperationResult.InFlight,
            AccountCloudSettingsOperationResult.Stale,
            AccountCloudSettingsOperationResult.Success -> null
        }
    }

    suspend fun deleteFundingAccount(id: Long): FundingAccountDeleteResult {
        checkLocalDataAvailable()
        return when (val result = repository.deleteFundingAccount(id)) {
            DataFundingAccountDeleteResult.Deleted -> FundingAccountDeleteResult.Deleted
            is DataFundingAccountDeleteResult.Referenced -> FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = result.activeLedgerEntryCount,
                deletedLedgerEntryCount = result.deletedLedgerEntryCount,
                pendingEntryCount = result.pendingEntryCount,
                ignoredEntryCount = result.ignoredEntryCount
            )
            DataFundingAccountDeleteResult.NotFound -> error("资金账户不存在")
        }
    }

    suspend fun clearLocalData(beforeClear: suspend () -> Unit) {
        check(
            mutableLocalDataReplacementInFlight.compareAndSet(
                expect = false,
                update = true
            )
        ) { LOCAL_DATA_REPLACEMENT_MESSAGE }
        try {
            beforeClear()
            repository.clearLocalData()
            val clearedRepositoryState = repository.state.first()
            repositoryState.first { it == clearedRepositoryState }
            val clearedUiState = clearedRepositoryState.toUiState()
            mutableLocalDataReplacementInFlight.value = false
            uiState.first { it == clearedUiState }
        } finally {
            mutableLocalDataReplacementInFlight.value = false
        }
    }

    private fun checkLocalDataAvailable() {
        check(!localDataReplacementInFlight.value) { LOCAL_DATA_REPLACEMENT_MESSAGE }
    }
}

private const val LOCAL_DATA_REPLACEMENT_MESSAGE = "本机数据正在删除，请稍后重试"

private fun LedgerRepositoryState.toUiState(): LedgerUiState {
    val mappedEntries = ledgerEntries.map { it.toLedgerUiEntry() }
    return LedgerUiState(
        entries = mappedEntries,
        deletedEntries = deletedLedgerEntries.map { it.toLedgerUiEntry() },
        categories = categories.map { it.toLedgerCategoryUiModel() },
        fundingAccounts = fundingAccounts.map { it.toFundingAccountUiModel() },
        ledgerBooks = ledgerBooks.map { ledgerBook ->
            LedgerBookUiModel(
                id = ledgerBook.id,
                name = ledgerBook.name,
                activeEntryCount = ledgerBook.activeEntryCount,
                deletedEntryCount = ledgerBook.deletedEntryCount,
                isActive = ledgerBook.id == activeLedgerBook?.id
            )
        },
        activeLedgerBookId = activeLedgerBook?.id,
        activeLedgerName = activeLedgerBook?.name ?: DEFAULT_LEDGER_BOOK_NAME,
        defaultFundingAccountSyncId = defaultFundingAccountSyncId,
        report = buildLedgerReportUiModel(mappedEntries)
    )
}
