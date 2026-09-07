package com.bks.view.review

import com.bks.viewmodel.review.ReviewEntryUiModel
internal data class ReviewQueueListActions(
    val onConfirm: (String) -> Unit,
    val onIgnore: (String) -> Unit,
    val onEdit: (ReviewEntryUiModel) -> Unit,
    val onShowIgnoredList: () -> Unit,
    val onNavigateHome: () -> Unit
)
