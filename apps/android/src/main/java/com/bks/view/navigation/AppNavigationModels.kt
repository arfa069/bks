package com.bks.view.navigation

import com.bks.R
import com.bks.view.profile.ProfileDestination
internal enum class AppTab(
    val label: String,
    val iconRes: Int,
    val backgroundRes: Int
) {
    Review(
        label = "待确认",
        iconRes = R.drawable.aa_nav_review_outlined,
        backgroundRes = R.drawable.aa_bg_review
    ),
    Ledger(
        label = "账本",
        iconRes = R.drawable.aa_nav_ledger_outlined,
        backgroundRes = R.drawable.aa_bg_ledger
    ),
    Reports(
        label = "报表",
        iconRes = R.drawable.aa_nav_reports_outlined,
        backgroundRes = R.drawable.aa_bg_reports
    ),
    Profile(
        label = "我的",
        iconRes = R.drawable.aa_nav_profile_outlined,
        backgroundRes = R.drawable.aa_bg_profile
    )
}

internal data class AppRoute(
    val tab: AppTab?,
    val profileDestination: ProfileDestination?,
    val manualEntryOpen: Boolean
)
