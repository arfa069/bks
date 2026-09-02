package com.bks.view.categorization

import com.bks.model.application.account.settingsSyncInFlight
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.CategorizationRule
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.TransactionKind
import com.bks.viewmodel.categorization.CategorizationAiUiState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategorizationRulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localModeShowsRulesAndLoginHintWithoutAiControls() {
        composeRule.setContent {
            CategorizationRulesScreen()
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithText("登录后可以使用智能分类；本地规则照常生效。").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertDoesNotExist()
    }

    @Test
    fun ruleRowDoesNotRepeatCategoryAsSubtitle() {
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(
                        id = "refund-rule",
                        merchantContains = "退款",
                        category = "退款"
                    )
                ),
                onRulesChange = {}
            )
        }

        composeRule.onAllNodesWithText("退款").assertCountEquals(1)
        composeRule.onNodeWithText("商户包含“退款”").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("建议为 退款").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signedInModeShowsOptionalAiConsentAndDisablesEnhancedContextByDefault() {
        composeRule.setContent {
            CategorizationRulesScreen(
                aiUiState = CategorizationAiUiState(signedIn = true)
            )
        }

        composeRule.onNodeWithText("智能分类").assertIsDisplayed()
        composeRule.onNodeWithText("试用").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertIsOff()
        composeRule.onNodeWithTag("enhanced-context-switch").assertIsNotEnabled()
    }


    @Test
    fun userCanCreateAndEditRule() {
        var rules by mutableStateOf(emptyList<CategorizationRule>())
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = rules,
                onRulesChange = { update -> rules = update(rules) }
            )
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithTag("create-rule").performScrollTo().performClick()
        composeRule.onNodeWithText("商户包含").performTextInput("星巴克")
        composeRule.onNodeWithText("标题关键词").performTextInput("拿铁")
        composeRule.onNodeWithText("来源").performTextInput("微信")
        composeRule.onNodeWithText("交易类型").performTextInput("支出")
        composeRule.onNodeWithText("分类").performTextInput("餐饮")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("星巴克").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("餐饮", rules.single().category)
        }

        composeRule.onNodeWithTag("rule-menu-rule-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("edit-rule-rule-1").performClick()
        composeRule.onNodeWithText("分类").performTextClearance()
        composeRule.onNodeWithText("分类").performTextInput("工作餐")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.runOnIdle {
            assertEquals("工作餐", rules.single().category)
        }
    }

    @Test
    fun userCanDeleteRule() {
        val initialRules = listOf(
            CategorizationRule(
                id = "rule-delete",
                merchantContains = "coffee",
                category = "food"
            )
        )
        var updatedRules: List<CategorizationRule>? = null
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = initialRules,
                onRulesChange = { update -> updatedRules = update(initialRules) }
            )
        }

        composeRule.onNodeWithTag("rule-menu-rule-delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("delete-rule-rule-delete").performClick()

        assertTrue(updatedRules?.isEmpty() == true)
    }

    @Test
    fun newRuleIdDoesNotReuseAnExistingIdAfterDeletion() {
        var rules by mutableStateOf(
            listOf(
                CategorizationRule(id = "rule-1", category = "餐饮"),
                CategorizationRule(id = "rule-3", category = "交通")
            )
        )
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = rules,
                onRulesChange = { update -> rules = update(rules) }
            )
        }

        composeRule.onNodeWithTag("create-rule").performScrollTo().performClick()
        composeRule.onNodeWithText("分类").performTextInput("购物")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("rule-1", "rule-3", "rule-4"), rules.map { it.id })
        }
    }

    @Test
    fun signedInUserCanToggleCloudAiConsent() {
        var settings by mutableStateOf(AiCategorizationSettings())
        composeRule.setContent {
            CategorizationRulesScreen(
                aiUiState = CategorizationAiUiState(signedIn = true, settings = settings),
                onAiSettingsChange = { settings = it },
            )
        }

        composeRule.onNodeWithTag("ai-consent-switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settings.aiConsentGranted)

        composeRule.onNodeWithTag("enhanced-context-switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settings.enhancedContextGranted)
    }

    @Test
    fun cloudAiSwitchesAreDisabledWhileSettingsSynchronize() {
        composeRule.setContent {
            CategorizationRulesScreen(
                aiUiState = CategorizationAiUiState(
                    settings = AiCategorizationSettings(aiConsentGranted = true),
                    signedIn = true,
                    settingsSyncInFlight = true
                )
            )
        }

        composeRule.onNodeWithTag("ai-settings-syncing").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertIsNotEnabled()
        composeRule.onNodeWithTag("enhanced-context-switch").assertIsNotEnabled()
    }

    @Test
    fun ruleFiltersShowOnlyMatchingTransactionKinds() {
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(id = "expense", transactionKind = "支出", category = "餐饮"),
                    CategorizationRule(id = "income", transactionKind = "收入", category = "工资"),
                    CategorizationRule(id = "other", transactionKind = "退款", category = "退款")
                ),
                onRulesChange = {}
            )
        }

        composeRule.onNodeWithTag("rule-filter-row").performScrollTo()
        composeRule.onNodeWithTag("rule-filter-Expense").performClick()

        composeRule.onNodeWithTag("rule-card-expense").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rule-card-income").assertDoesNotExist()
        composeRule.onNodeWithTag("rule-card-other").assertDoesNotExist()
    }

    @Test
    fun ruleDraftAndFilterAreRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(id = "expense", transactionKind = "支出", category = "餐饮"),
                    CategorizationRule(id = "income", transactionKind = "收入", category = "工资")
                ),
                onRulesChange = {}
            )
        }

        composeRule.onNodeWithTag("rule-filter-row").performScrollTo()
        composeRule.onNodeWithTag("rule-filter-Expense").performClick().assertIsSelected()
        composeRule.onNodeWithTag("create-rule").performScrollTo().performClick()
        composeRule.onNodeWithText("商户包含").performTextInput("星巴克")
        composeRule.onNodeWithText("分类").performTextInput("餐饮")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("商户包含").assertTextContains("星巴克")
        composeRule.onNodeWithText("分类").assertTextContains("餐饮")
        composeRule.onNodeWithTag("rule-filter-Expense").assertIsSelected()
    }

    @Test
    fun profileStyleLayoutRemainsReachableAcrossWindowSizesAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        var backRequested = false
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                CategorizationRulesScreen(
                    rules = listOf(
                        CategorizationRule(
                            id = "adaptive-rule",
                            merchantContains = "咖啡店",
                            transactionKind = "支出",
                            category = "餐饮"
                        )
                    ),
                    onRulesChange = {},
                    actions = CategorizationRulesActions(
                        onBack = { backRequested = true }
                    )
                )
            }
        }
        composeRule.waitForIdle()

        listOf(
            TestConfiguration(DpSize(400.dp, 500.dp)),
            TestConfiguration(DpSize(400.dp, 800.dp)),
            TestConfiguration(DpSize(610.dp, 500.dp)),
            TestConfiguration(DpSize(900.dp, 1_000.dp)),
            TestConfiguration(DpSize(400.dp, 500.dp), fontScale = 1.5f)
        ).forEach { configuration ->
            composeRule.runOnIdle {
                forcedSize = configuration.size
                fontScale = configuration.fontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("categorization-back")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("categorization-summary-card")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("categorization-rule-count")
                .assertTextContains("1 条规则")
            composeRule.onNodeWithTag("categorization-ai-card")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("create-rule")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("rule-filter-row").performScrollTo()
            composeRule.onNodeWithTag("rule-filter-Other")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("rule-card-adaptive-rule")
                .performScrollTo()
                .assertIsDisplayed()
        }

        composeRule.onNodeWithTag("categorization-back")
            .performScrollTo()
            .performClick()
        assertTrue(backRequested)
    }

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )

}
