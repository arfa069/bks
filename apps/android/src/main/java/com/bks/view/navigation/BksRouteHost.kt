package com.bks.view.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bks.R
import com.bks.view.profile.ProfileDestination
import com.bks.view.common.BksAppState
import com.bks.view.common.components.AppBottomNavigationBar
import com.bks.view.common.components.SlidePageTransition
import com.bks.view.common.visual.AppWallpaper

@Composable
internal fun BksRouteHost(
    appState: BksAppState,
    content: @Composable (
        targetRoute: AppRoute,
        innerPadding: PaddingValues,
        onManualEntryClosed: () -> Unit,
        onNavigateHome: () -> Unit
    ) -> Unit
) {
    var selectedTab by appState.selectedTab
    var manualEntryOpen by appState.manualEntryOpen
    var profileDestination by appState.profileDestination

    BackHandler(
        enabled = selectedTab != null &&
            (selectedTab != AppTab.Profile || profileDestination == null) &&
            !manualEntryOpen
    ) {
        selectedTab = null
    }
    BackHandler(enabled = selectedTab == AppTab.Profile && profileDestination != null) {
        profileDestination = if (profileDestination == ProfileDestination.Notifications) {
            ProfileDestination.AutomaticBookkeeping
        } else {
            null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(appState.snackbarHostState) },
            bottomBar = {
                if (selectedTab == null && !manualEntryOpen) {
                    AppBottomNavigationBar(
                        items = appState.bottomNavigationItems,
                        selectedKey = null,
                        onItemSelected = { key ->
                            if (!manualEntryOpen) {
                                selectedTab = appState.tabs.first { it.name == key }
                                profileDestination = null
                            }
                        },
                        onAddEntry = { if (!manualEntryOpen) manualEntryOpen = true },
                        enabled = !manualEntryOpen
                    )
                }
            }
        ) { innerPadding ->
            val route = remember(selectedTab, profileDestination, manualEntryOpen) {
                AppRoute(
                    tab = selectedTab,
                    profileDestination = profileDestination.takeIf { selectedTab == AppTab.Profile },
                    manualEntryOpen = manualEntryOpen
                )
            }
            val wallpaperRes = when {
                route.manualEntryOpen -> R.drawable.aa_bg_ledger
                route.tab == null -> R.drawable.aa_bg_account
                route.profileDestination != null -> R.drawable.aa_bg_neutral
                else -> route.tab.backgroundRes
            }
            AppWallpaper(wallpaperRes) {
                SlidePageTransition(route, Modifier.fillMaxSize()) { targetRoute ->
                    content(
                        targetRoute,
                        innerPadding,
                        { manualEntryOpen = false },
                        {
                            selectedTab = null
                            profileDestination = null
                        }
                    )
                }
            }
        }
    }
}
