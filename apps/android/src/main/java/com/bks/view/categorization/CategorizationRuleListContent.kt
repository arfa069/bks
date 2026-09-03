package com.bks.view.categorization

import com.bks.model.domain.categorization.CategorizationRule
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.view.common.components.TextButton
@Composable
internal fun CategorizationRuleListContent(
    rules: List<CategorizationRule>,
    onEdit: (CategorizationRule) -> Unit,
    onDelete: (CategorizationRule) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rules.isEmpty()) {
            EmptyRulesCard()
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("categorization-rule-list-card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                val sortedRules = rules.sortedWith(
                    compareByDescending<CategorizationRule> { it.priority }
                        .thenByDescending { it.updatedAtEpochMillis }
                )
                sortedRules.forEachIndexed { index, rule ->
                    CategorizationRuleRow(
                        rule = rule,
                        onEdit = { onEdit(rule) },
                        onDelete = { onDelete(rule) }
                    )
                    if (index < sortedRules.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp, end = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRulesCard(
    title: String = "还没有分类规则",
    description: String = "新建一条规则，让常见交易自动带上分类建议。"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategorizationRuleRow(
    rule: CategorizationRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(rule.id) { mutableStateOf(false) }
    val accentColor = rule.accentColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("rule-card-${rule.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(13.dp),
            color = accentColor.copy(alpha = 0.14f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = rule.displayName().take(1),
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = rule.displayName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = rule.conditionSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                color = accentColor.copy(alpha = 0.10f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "建议为 ${rule.category}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            TextButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("rule-menu-${rule.id}")
            ) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                    modifier = Modifier.testTag("edit-rule-${rule.id}")
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("delete-rule-${rule.id}")
                )
            }
        }
    }
}

private fun CategorizationRule.displayName(): String =
    merchantContains.ifBlank { "需要补充商户" }

private fun CategorizationRule.conditionSummary(): String =
    merchantContains.takeIf(String::isNotBlank)
        ?.let { "商户包含“$it”" }
        ?: "缺少商户关键词，当前规则不生效"

private fun CategorizationRule.accentColor(): Color = when (category) {
    "餐饮" -> Color(0xFFC77A18)
    "交通" -> Color(0xFF5555C2)
    "退款" -> Color(0xFFC64D5A)
    else -> Color(0xFF5B5BD6)
}
