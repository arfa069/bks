package com.bks.view.review

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bks.model.domain.billsync.BillBackfillState
import com.bks.view.common.theme.BksTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillBackfillScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun renderReviewEntryAndExplicitStartDialog() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 860.dp))) {
                BksTheme {
                    Surface(Modifier.fillMaxSize()) {
                        ReviewQueueScreen(state = testReviewQueueUiState(), backfillContent = {
                            BillBackfillCard(BillBackfillState(serviceConnected = true), {}, {}, {})
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("review-backfill-action").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            save(bitmap, "review-backfill.png")
            bitmap.recycle()
        }
        compose.onNodeWithTag("review-backfill-action").performClick()
        compose.onNodeWithTag("review-backfill-start").assertIsDisplayed()
    }

    private fun save(bitmap: Bitmap, name: String) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").exists() }
        val directory = root.resolve("apps/android/build/outputs/backfill-preview").apply { mkdirs() }
        directory.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
