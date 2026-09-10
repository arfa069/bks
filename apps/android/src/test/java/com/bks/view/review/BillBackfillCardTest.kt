package com.bks.view.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bks.model.domain.billsync.BillBackfillPhase
import com.bks.model.domain.billsync.BillBackfillState
import com.bks.model.domain.ledger.PaymentSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillBackfillCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startRequiresLiveServiceAndExplicitChoice() {
        var state by mutableStateOf(BillBackfillState())
        var chosen: PaymentSource? = null
        var settingsOpened = 0
        compose.setContent { BillBackfillCard(state, { chosen = it }, {}, { settingsOpened++ }) }
        compose.onNodeWithTag("review-backfill-action").performClick()
        compose.onNodeWithTag("review-backfill-start").assertIsNotEnabled()
        compose.onNodeWithText("无障碍设置").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
        assertEquals(null, chosen)
        compose.runOnIdle { state = state.copy(serviceConnected = true) }
        compose.onNodeWithTag("review-backfill-source-WECHAT").performClick()
        compose.onNodeWithTag("review-backfill-start").performClick()
        assertEquals(PaymentSource.WECHAT, chosen)
        compose.onNodeWithTag("review-backfill-start").assertDoesNotExist()
    }

    @Test fun runningSessionOffersFinishAndShowsActualCounts() {
        var finished = 0
        compose.setContent {
            BillBackfillCard(BillBackfillState(sessionId = 1, phase = BillBackfillPhase.Running, insertedCount = 3,
                duplicateCount = 1), {}, { finished++ }, {})
        }
        compose.onNodeWithText("新增 3 笔 · 跳过重复 1 笔").assertIsDisplayed()
        compose.onNodeWithText("结束").performClick()
        assertEquals(1, finished)
    }

    @Test fun cardAndDialogActionsFitPhoneTabletAndLargeText() {
        var size by mutableStateOf(DpSize(400.dp, 800.dp))
        var scale by mutableStateOf(1f)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size) then
                DeviceConfigurationOverride.FontScale(scale)) {
                BillBackfillCard(BillBackfillState(serviceConnected = true), {}, {}, {})
            }
        }
        listOf(400, 610, 900).forEach { width ->
            compose.runOnIdle { size = DpSize(width.dp, 800.dp) }
            compose.onNodeWithTag("review-backfill-action").assertIsDisplayed().performClick()
            compose.onNodeWithTag("review-backfill-start").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
        }
        compose.runOnIdle { size = DpSize(400.dp, 800.dp); scale = 1.5f }
        compose.onNodeWithTag("review-backfill-action").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review-backfill-start").assertIsDisplayed()
    }
}
