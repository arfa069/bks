package com.bks.view.review

import com.bks.viewmodel.review.formatReviewDateTime
import com.bks.viewmodel.review.parseReviewDateTime
import com.bks.viewmodel.review.toTransactionKind
import com.bks.viewmodel.ledger.label
import com.bks.viewmodel.ledger.amountMinorToText
import com.bks.view.common.components.Button
import com.bks.viewmodel.review.parseReviewEvidenceText
import com.bks.viewmodel.review.ReviewEntryUiModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.bks.view.common.components.OutlinedButton
import androidx.compose.material3.Text
import com.bks.view.common.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import com.bks.model.domain.ledger.defaultFlowDirection
import com.bks.viewmodel.ledger.FundingAccountUiModel
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.viewmodel.ledger.LedgerEntryFormState
import com.bks.viewmodel.ledger.LedgerEntryFormSubmission
import com.bks.viewmodel.ledger.UNCATEGORIZED_ENTRY_CATEGORY_ID
import com.bks.view.common.entry.ledgerCategoryOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class AiSuggestionRequest(
    val entry: ReviewEntryUiModel,
    val draft: LedgerEntryFormState,
    val input: LedgerEntryFormSubmission,
    val categories: List<LedgerCategoryUiModel>,
    val fundingAccounts: List<FundingAccountUiModel>,
    val categoryCandidates: List<String>,
    val actions: ReviewEditorToolActions
)

@Composable
internal fun ReviewEditorTools(
    entry: ReviewEntryUiModel,
    draft: LedgerEntryFormState,
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>,
    actions: ReviewEditorToolActions
) {
    var showEvidence by remember(entry.id) { mutableStateOf(false) }
    var aiMessage by remember(entry.id) { mutableStateOf<String?>(null) }
    var aiRequestInFlight by remember(entry.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val aiCategoryCandidates = remember(
        categories,
        draft.flowDirection,
        draft.transactionKind
    ) {
        ledgerCategoryOptions(
            categories = categories,
            flowDirection = draft.flowDirection,
            transactionKind = draft.transactionKind
        ).asSequence()
            .filterNot { it.id == UNCATEGORIZED_ENTRY_CATEGORY_ID }
            .map(LedgerCategoryUiModel::reviewDisplayName)
            .distinct()
            .toList()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showEvidence = !showEvidence }) {
                    Text(if (showEvidence) "收起证据" else "查看证据")
                }
                OutlinedButton(
                    onClick = {
                        if (aiRequestInFlight) return@OutlinedButton
                        val input = runCatching { draft.toSubmission(System.currentTimeMillis()) }
                            .getOrElse {
                                aiMessage = it.message ?: "当前内容无法获取分类建议"
                                return@OutlinedButton
                            }
                        aiRequestInFlight = true
                        aiMessage = null
                        coroutineScope.launch {
                            try {
                                aiMessage = requestAiSuggestion(
                                    AiSuggestionRequest(
                                        entry = entry,
                                        draft = draft,
                                        input = input,
                                        categories = categories,
                                        fundingAccounts = fundingAccounts,
                                        categoryCandidates = aiCategoryCandidates,
                                        actions = actions
                                    )
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: RuntimeException) {
                                aiMessage = "云端 AI 暂时不可用，请稍后重试"
                            } finally {
                                aiRequestInFlight = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("ai-suggest-button"),
                    enabled = !aiRequestInFlight
                ) {
                    if (aiRequestInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("ai-suggest-loading"),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在获取建议")
                    } else {
                        Text("AI 建议分类")
                    }
                }
            }
            aiMessage?.let {
                Text(
                    it,
                    modifier = Modifier.testTag("ai-suggest-message"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (showEvidence) EvidenceSection(entry)
        }
    }
}

private suspend fun requestAiSuggestion(request: AiSuggestionRequest): String {
    val input = request.input
    val result = request.actions.onAiSuggest(
        request.entry.copy(
            title = input.merchantTitle,
            amountMinor = input.amountMinor,
            transactionTimeText = formatReviewDateTime(
                input.transactionTimeEpochMillis,
                java.time.ZoneId.systemDefault()
            ),
            kindLabel = input.transactionKind.toReviewLabel(),
            categoryId = input.categoryId,
            category = input.categoryId.toReviewCategoryName(request.categories),
            fundingAccountId = input.fundingAccountId,
            fundingAccountLabel = request.fundingAccounts
                .firstOrNull { it.id == input.fundingAccountId }
                ?.label
                .orEmpty(),
            note = input.note
        ),
        request.categoryCandidates
    )
    return result.category?.let { category ->
        val categoryId = request.categories.firstOrNull {
            it.reviewDisplayName() == category
        }?.id ?: DefaultCategories.idForName(
            category,
            input.transactionKind
        )
        if (categoryId == null) {
            "AI 建议“$category”不在现有分类中"
        } else {
            request.actions.onDraftChange(request.draft.copy(categoryId = categoryId))
            "AI 建议：$category"
        }
    } ?: result.message
}

internal fun ReviewEntryUiModel.toLedgerEntryFormState(
    categories: List<LedgerCategoryUiModel>,
    fundingAccounts: List<FundingAccountUiModel>
): LedgerEntryFormState {
    val transactionKind = kindLabel.toTransactionKind()
    val selectedCategoryId = categoryId
        ?: categories.firstOrNull { it.reviewDisplayName() == category }?.id
        ?: DefaultCategories.idForName(category, transactionKind)
        ?: UNCATEGORIZED_ENTRY_CATEGORY_ID
    val selectedFundingAccountId = fundingAccountId
        ?: fundingAccounts.firstOrNull { it.label == fundingAccountLabel }?.id
    return LedgerEntryFormState(
        flowDirection = transactionKind.defaultFlowDirection(),
        transactionKind = transactionKind,
        amountText = amountMinorToText(amountMinor),
        transactionTimeEpochMillis = parseReviewDateTime(
            transactionTimeText,
            java.time.ZoneId.systemDefault()
        ) ?: capturedAtEpochMillis,
        merchantTitle = title,
        categoryId = selectedCategoryId,
        fundingAccountId = selectedFundingAccountId,
        creatingFundingAccount = false,
        newFundingAccountLabel = "",
        note = note.orEmpty(),
        paymentSource = sourceLabel.toReviewPaymentSource()
    )
}

internal fun String?.toReviewCategoryName(categories: List<LedgerCategoryUiModel>): String =
    this?.let { id ->
        DefaultCategories.nameForId(id)
            ?: categories.firstOrNull { it.id == id }?.name
    } ?: "未分类"

internal fun TransactionKind.toReviewLabel(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

internal fun String.toReviewPaymentSource(): PaymentSource? = when (trim()) {
    "微信" -> PaymentSource.WECHAT
    "支付宝" -> PaymentSource.ALIPAY
    "其他应用" -> PaymentSource.OTHER
    else -> null
}

@Composable
private fun EvidenceSection(entry: ReviewEntryUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("证据", fontWeight = FontWeight.SemiBold)
        Text("来源：${entry.sourceLabel}", style = MaterialTheme.typography.bodySmall)
        Text("捕获时间：${entry.captureTimeText}", style = MaterialTheme.typography.bodySmall)
        Text("解析字段", fontWeight = FontWeight.SemiBold)
        entry.parsedFields.forEach { field ->
            Text(field, style = MaterialTheme.typography.bodySmall)
        }
        parseReviewEvidenceText(entry.rawEvidenceText).forEach { (label, text) ->
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
