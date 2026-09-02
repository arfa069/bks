package com.bks.macrobenchmark

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.uiAutomator
import java.util.regex.Pattern
import kotlin.math.abs

internal const val TARGET_PACKAGE = "com.bks.benchmark"
internal const val ITERATIONS = 10
internal const val SMALL_DATASET_ENTRY_COUNT = 40

internal class BenchmarkApp(private val device: UiDevice) {
    fun lockNaturalOrientation() {
        device.setOrientationNatural()
        device.waitForIdle()
    }

    fun unlockOrientation() {
        device.unfreezeRotation()
    }

    fun resetAndSeed(entryCount: Int = SMALL_DATASET_ENTRY_COUNT) {
        check(device.executeShellCommand("pm clear $TARGET_PACKAGE").contains("Success"))
        val launchResult = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.bks.view.app.MainActivity"
        )
        check(launchResult.contains("Status: ok")) { launchResult }
        val result = InstrumentationRegistry.getInstrumentation().context.contentResolver.call(
            Uri.parse("content://$TARGET_PACKAGE.benchmark-data"),
            "seed",
            null,
            Bundle().apply { putInt("entry_count", entryCount) }
        )
        check(result?.getBoolean("seeded") == true)
        check(result.getInt("entry_count") == entryCount)
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    fun waitForHome() {
        waitForText("账本")
    }

    fun forceStop() {
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    fun openLedger() {
        clickText("账本")
        waitForText("基准账目 01")
    }

    fun scrollLedgerAndOpenDetail() {
        val entriesBeforeScroll = checkNotNull(
            device.wait(
                Until.findObjects(By.text(Pattern.compile("基准账目 \\d+"))),
                TIMEOUT_MILLIS
            )
        )
        check(entriesBeforeScroll.isNotEmpty())
        val listCenterY = entriesBeforeScroll
            .map { it.visibleBounds.centerY() }
            .average()
            .toInt()
        val gestureMargin = device.displayHeight / 5
        check(
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight - gestureMargin,
                device.displayWidth / 2,
                gestureMargin,
                20
            )
        )
        check(device.wait(Until.gone(By.text("基准账目 01")), TIMEOUT_MILLIS)) {
            val visibleEntries = device.findObjects(
                By.text(Pattern.compile("基准账目 \\d+"))
            ).joinToString { "${it.text}=${it.visibleBounds}" }
            "Ledger did not scroll; display=${device.displayWidth}x${device.displayHeight}, " +
                "before=${entriesBeforeScroll.joinToString { "${it.text}=${it.visibleBounds}" }}, " +
                "after=$visibleEntries"
        }
        uiAutomator {
            check(!waitForStableInActiveWindow().isTimeout)
        }
        val entries = checkNotNull(
            device.wait(
                Until.findObjects(
                    By.text(Pattern.compile("基准账目 \\d+"))
                ),
                TIMEOUT_MILLIS
            )
        )
        val entry = checkNotNull(
            entries.minByOrNull { abs(it.visibleBounds.centerY() - listCenterY) }
        )
        entry.click()
        waitForText("编辑账目")
    }

    fun returnHomeFromLedgerDetail() {
        device.pressBack()
        waitForText("默认账本")
        device.pressBack()
        waitForHome()
    }

    fun openReports() {
        clickText("报表")
        waitForText("分类排行")
    }

    fun returnHomeFromReports() {
        val homeButton = checkNotNull(
            device.wait(Until.findObject(By.desc("返回主页")), TIMEOUT_MILLIS)
        )
        homeButton.click()
        device.waitForIdle()
        waitForHome()
    }

    private fun clickText(text: String) {
        val node = checkNotNull(device.wait(Until.findObject(By.text(text)), TIMEOUT_MILLIS))
        node.click()
        device.waitForIdle()
    }

    private fun waitForText(text: String) {
        check(device.wait(Until.hasObject(By.text(text)), TIMEOUT_MILLIS)) {
            val visibleText = device.findObjects(By.pkg(TARGET_PACKAGE))
                .mapNotNull { it.text }
                .filter { it.isNotBlank() }
                .distinct()
            "Timed out waiting for '$text'; " +
                "currentPackage=${device.currentPackageName}; visibleText=$visibleText"
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L
    }
}
