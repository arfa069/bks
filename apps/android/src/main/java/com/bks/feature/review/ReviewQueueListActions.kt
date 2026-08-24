package com.bks.feature.review

internal data class ReviewQueueListActions(
    val onConfirm: (String) -> Unit,
    val onIgnore: (String) -> Unit,
    val onEdit: (ReviewQueueEntry) -> Unit,
    val onShowIgnoredList: () -> Unit,
    val onNavigateHome: () -> Unit
)
