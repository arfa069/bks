package com.bks.view.categorization

import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.normalizeMerchantContains
import com.bks.model.domain.ledger.DefaultCategories
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bks.view.common.components.Button
import com.bks.view.common.components.OutlinedTextField
import com.bks.view.common.components.TextButton
import com.bks.view.common.entry.ManualSelection
import com.bks.view.common.entry.ManualSelectionField
@Composable
internal fun CategorizationRuleDialog(
    initialRule: CategorizationRule?,
    categories: List<LedgerCategoryUiModel>,
    onDismiss: () -> Unit,
    onSave: (CategorizationRule) -> Unit
) {
    var merchantContains by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.merchantContains.orEmpty())
    }
    var categoryId by rememberSaveable(initialRule?.id) {
        mutableStateOf(
            categories.firstOrNull { category ->
                category.name.equals(initialRule?.category, ignoreCase = true)
            }?.id.orEmpty()
        )
    }
    val normalizedMerchants = normalizeMerchantContains(merchantContains)
    val selectedCategory = categories.firstOrNull { it.id == categoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "新建规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchantContains,
                    onValueChange = { merchantContains = it },
                    label = { Text("商户包含") },
                    supportingText = { Text("多个商户请用英文逗号分隔") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ManualSelectionField(
                    label = "分类",
                    selection = ManualSelection(
                        selected = categoryId,
                        options = categories.map(LedgerCategoryUiModel::id),
                        itemLabel = { id ->
                            categories.firstOrNull { it.id == id }?.let { category ->
                                DefaultCategories.nameForId(category.id) ?: category.name
                            } ?: "请选择分类"
                        },
                        testTag = "categorization-rule-category"
                    ),
                    onSelected = { categoryId = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CategorizationRule(
                            id = initialRule?.id.orEmpty(),
                            merchantContains = normalizedMerchants,
                            category = requireNotNull(selectedCategory).name,
                            priority = initialRule?.priority ?: 0,
                            enabled = initialRule?.enabled ?: true,
                            updatedAtEpochMillis = initialRule?.updatedAtEpochMillis ?: 0
                        )
                    )
                },
                enabled = normalizedMerchants.isNotBlank() && selectedCategory != null
            ) {
                Text("保存规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

internal fun List<CategorizationRule>.nextUpdatedAt(): Long =
    (maxOfOrNull { it.updatedAtEpochMillis } ?: 0L) + 1L

internal fun List<CategorizationRule>.nextRuleId(): String {
    val nextIndex = asSequence()
        .mapNotNull { rule -> rule.id.removePrefix("rule-").toLongOrNull() }
        .maxOrNull()
        ?.plus(1L)
        ?: 1L
    return "rule-$nextIndex"
}
