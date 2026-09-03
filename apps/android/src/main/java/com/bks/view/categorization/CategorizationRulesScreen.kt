package com.bks.view.categorization

import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.categorization.CategorizationRulesUpdate
import com.bks.model.domain.categorization.upsert
import com.bks.viewmodel.categorization.CategorizationAiUiState
import com.bks.viewmodel.ledger.LedgerCategoryUiModel
import com.bks.viewmodel.ledger.defaultLedgerCategoryUiModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.view.common.components.Button
import com.bks.view.common.components.TextButton
import com.bks.view.common.visual.CachedResourceImage

private val CategorizationContentMaxWidth = 880.dp

data class CategorizationRulesActions(
    val onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    val onBack: (() -> Unit)? = null
)

@Composable
fun CategorizationRulesScreen(
    modifier: Modifier = Modifier,
    categories: List<LedgerCategoryUiModel> = defaultLedgerCategoryUiModels(),
    aiUiState: CategorizationAiUiState = CategorizationAiUiState(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var rules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = { update -> rules = update(rules) },
        categories = categories,
        modifier = modifier,
        aiUiState = aiUiState,
        actions = CategorizationRulesActions(
            onAiSettingsChange = onAiSettingsChange,
            onBack = onBack
        )
    )
}

@Composable
@Suppress("LongParameterList")
fun CategorizationRulesScreen(
    rules: List<CategorizationRule>,
    onRulesChange: (CategorizationRulesUpdate) -> Unit,
    categories: List<LedgerCategoryUiModel> = defaultLedgerCategoryUiModels(),
    modifier: Modifier = Modifier,
    aiUiState: CategorizationAiUiState = CategorizationAiUiState(),
    actions: CategorizationRulesActions = CategorizationRulesActions()
) {
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var isCreating by rememberSaveable { mutableStateOf(false) }
    val editingRule = remember(rules, editingRuleId) {
        rules.firstOrNull { it.id == editingRuleId }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = CategorizationContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    top = 20.dp,
                    end = horizontalPadding,
                    bottom = 32.dp
                )
                .testTag("categorization-rules-page"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CategorizationRulesHeader(onBack = actions.onBack)

            CategorizationRulesSummaryCard(ruleCount = rules.size)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "智能辅助",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                CategorizationAiSettingsSection(
                    uiState = aiUiState,
                    onSettingsChange = actions.onAiSettingsChange
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RuleListHeading(
                    ruleCount = rules.size,
                    onCreate = {
                        editingRuleId = null
                        isCreating = true
                    }
                )

                CategorizationRuleListContent(
                    rules = rules,
                    onEdit = { rule ->
                        editingRuleId = rule.id
                        isCreating = false
                    },
                    onDelete = { rule ->
                        onRulesChange { currentRules ->
                            currentRules.filterNot { it.id == rule.id }
                        }
                    }
                )
            }
        }
    }

    if (isCreating || editingRule != null) {
        CategorizationRuleDialog(
            initialRule = editingRule,
            categories = categories.ifEmpty { defaultLedgerCategoryUiModels() },
            onDismiss = {
                isCreating = false
                editingRuleId = null
            },
            onSave = { savedRule ->
                onRulesChange { currentRules ->
                    currentRules.upsert(
                        savedRule.copy(
                            id = editingRule?.id ?: currentRules.nextRuleId(),
                            updatedAtEpochMillis = currentRules.nextUpdatedAt()
                        )
                    )
                }
                isCreating = false
                editingRuleId = null
            }
        )
    }
}

@Composable
private fun CategorizationRulesSummaryCard(ruleCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("categorization-summary-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) {
                CachedResourceImage(
                    imageRes = R.drawable.aa_profile_rules,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = "本地规则",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleSummaryBadge(
                        label = "$ruleCount 条规则",
                        modifier = Modifier.testTag("categorization-rule-count")
                    )
                    RuleSummaryBadge(label = "本机生效")
                }
            }
        }
    }
}

@Composable
private fun RuleSummaryBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun RuleListHeading(
    ruleCount: Int,
    onCreate: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "我的规则",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape
        ) {
            Text(
                text = ruleCount.toString(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onCreate,
            modifier = Modifier.testTag("create-rule")
        ) {
            Text("新建规则")
        }
    }
}

@Composable
private fun CategorizationRulesHeader(onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "分类规则",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "让常见交易按你的习惯自动归类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        onBack?.let { back ->
            Spacer(Modifier.width(12.dp))
            TextButton(
                onClick = back,
                modifier = Modifier.testTag("categorization-back")
            ) {
                Text("返回")
            }
        }
    }
}
