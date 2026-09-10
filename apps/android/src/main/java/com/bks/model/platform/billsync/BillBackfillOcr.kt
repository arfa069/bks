package com.bks.model.platform.billsync

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Bitmaps stay in memory and are released even when a session ends during recognition. */
internal class BillBackfillOcr(private val service: AccessibilityService) {
    fun screenReady(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    suspend fun recognize(windowId: Int, stillCurrent: () -> Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !screenReady() || !stillCurrent()) return null
        val bitmap = screenshot(windowId, stillCurrent) ?: return null
        if (!screenReady() || !stillCurrent()) {
            bitmap.recycle()
            return null
        }
        return recognizeBitmap(bitmap)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshot(windowId: Int, stillCurrent: () -> Boolean): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        runCatching {
                            if (!continuation.isActive || !screenReady() || !stillCurrent()) null else {
                                Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { hardware ->
                                    try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                                }
                            }
                        }.getOrNull()
                    } finally { buffer.close() }
                    continuation.resume(bitmap) { _, value, _ -> value?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            if (!stillCurrent() || !screenReady()) {
                continuation.resume(null)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            }
        }

    private suspend fun recognizeBitmap(bitmap: Bitmap): String? = suspendCancellableCoroutine { continuation ->
        val recognizer = runCatching { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            .getOrElse {
                bitmap.recycle()
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                try {
                    val text = if (task.isSuccessful) task.result.textBlocks.flatMap { it.lines }
                        .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                        .joinToString("\n") { it.text } else null
                    if (continuation.isActive) continuation.resume(text)
                } finally {
                    bitmap.recycle()
                    recognizer.close()
                }
            }
        } catch (_: Exception) {
            bitmap.recycle()
            recognizer.close()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
