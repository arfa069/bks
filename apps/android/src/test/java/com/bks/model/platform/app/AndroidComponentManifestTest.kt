package com.bks.model.platform.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidComponentManifestTest {
    @Test
    fun manifestUsesRenamedApplicationActivityAndAccessibilityService() {
        val manifest = projectRoot()
            .resolve("apps/android/src/main/AndroidManifest.xml")
            .readText()

        assertTrue(manifest.contains("com.bks.model.platform.app.BksApplication"))
        assertTrue(manifest.contains("com.bks.view.app.MainActivity"))
        assertTrue(
            manifest.contains("com.bks.model.platform.billsync.BillSyncAccessibilityService")
        )
        assertTrue(manifest.contains("com.bks.action.WECHAT_AUTH_CALLBACK"))
        assertFalse(manifest.contains("com.bks.BksApplication"))
        assertFalse(manifest.contains("com.bks.MainActivity"))
        assertFalse(manifest.contains("com.bks.feature.billsync.BillSyncAccessibilityService"))
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { current ->
            current.parentFile?.absoluteFile
        }.first { it.resolve("settings.gradle.kts").exists() }
    }
}
