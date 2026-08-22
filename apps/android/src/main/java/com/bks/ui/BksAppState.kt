package com.bks.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.bks.AppTab
import com.bks.feature.profile.ProfileDestination
import com.bks.ui.components.AppBottomNavigationItem

/**
 * Remembers and manages UI navigation state, tab selections, list scroll positions,
 * and bottom bar items for [BksApp].
 */
@Stable
@Suppress("LongParameterList")
internal class BksAppState(
    val snackbarHostState: SnackbarHostState,
    val ledgerEntryListState: LazyListState,
    val reportCategoryRankingListState: LazyListState,
    val tabs: List<AppTab>,
    val selectedTab: MutableState<AppTab?>,
    val manualEntryOpen: MutableState<Boolean>,
    val profileDestination: MutableState<ProfileDestination?>,
    val accountEntryMode: MutableState<AccountEntryMode?>,
    val accountEntryReturnTarget: MutableState<AccountEntryReturnTarget?>
) {

    val bottomNavigationItems: List<AppBottomNavigationItem> = tabs.map { tab ->
        AppBottomNavigationItem(
            key = tab.name,
            label = tab.label,
            iconRes = tab.iconRes
        )
    }

    fun openAccountEntry(mode: AccountEntryMode, returnTarget: AccountEntryReturnTarget?) {
        accountEntryMode.value = mode
        accountEntryReturnTarget.value = returnTarget
    }

    fun closeAccountEntry() {
        accountEntryMode.value = null
        accountEntryReturnTarget.value = null
    }
}

internal enum class AccountEntryMode {
    SignInOrRegister
}

internal enum class AccountEntryReturnTarget {
    AccountManagement
}

@Composable
internal fun rememberBksAppState(
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    ledgerEntryListState: LazyListState = rememberLazyListState(),
    reportCategoryRankingListState: LazyListState = rememberLazyListState(),
    tabs: List<AppTab> = remember { AppTab.entries.toList() }
): BksAppState {
    val selectedTab = rememberSaveable(
        stateSaver = nullableEnumStateSaver(AppTab::valueOf)
    ) { mutableStateOf(null) }
    val manualEntryOpen = rememberSaveable { mutableStateOf(false) }
    val profileDestination = rememberSaveable(
        stateSaver = nullableEnumStateSaver(ProfileDestination::valueOf)
    ) { mutableStateOf(null) }
    val accountEntryMode = rememberSaveable(
        stateSaver = nullableEnumStateSaver(AccountEntryMode::valueOf)
    ) { mutableStateOf(null) }
    val accountEntryReturnTarget = rememberSaveable(
        stateSaver = nullableEnumStateSaver(AccountEntryReturnTarget::valueOf)
    ) { mutableStateOf(null) }
    return remember(
        snackbarHostState,
        ledgerEntryListState,
        reportCategoryRankingListState,
        tabs,
        selectedTab,
        manualEntryOpen,
        profileDestination,
        accountEntryMode,
        accountEntryReturnTarget
    ) {
        BksAppState(
            snackbarHostState = snackbarHostState,
            ledgerEntryListState = ledgerEntryListState,
            reportCategoryRankingListState = reportCategoryRankingListState,
            tabs = tabs,
            selectedTab = selectedTab,
            manualEntryOpen = manualEntryOpen,
            profileDestination = profileDestination,
            accountEntryMode = accountEntryMode,
            accountEntryReturnTarget = accountEntryReturnTarget
        )
    }
}

private fun <T : Enum<T>> nullableEnumStateSaver(valueOf: (String) -> T): Saver<T?, String> = Saver(
    save = { value -> value?.name.orEmpty() },
    restore = { value -> value.takeIf(String::isNotEmpty)?.let(valueOf) }
)
