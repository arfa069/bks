package com.bks.view.settings

import com.bks.model.data.local.backup.validated
import com.bks.model.domain.ledger.LedgerBook
import com.bks.viewmodel.settings.BackupLedgerEntryUiModel
import com.bks.viewmodel.settings.DataAndBackupActions
import com.bks.viewmodel.settings.DataAndBackupNotice
import com.bks.viewmodel.settings.DataAndBackupOperation
import com.bks.viewmodel.settings.DataAndBackupUiState
import com.bks.viewmodel.settings.DataAndBackupValidationResult
import com.bks.viewmodel.settings.LedgerSyncSectionActions
import com.bks.viewmodel.settings.LedgerSyncSettingsActionResult
import com.bks.viewmodel.settings.LedgerSyncSettingsConflictChoice
import com.bks.viewmodel.settings.LedgerSyncSettingsConflictUiModel
import com.bks.viewmodel.settings.LedgerSyncSettingsInitialMode
import com.bks.viewmodel.settings.LedgerSyncSettingsPreviewUiModel
import com.bks.viewmodel.settings.LedgerSyncSettingsUiState
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import com.bks.api.LedgerSyncPayloadContract
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataAndBackupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstEnableExplainsReadableStorageTransportAndDeletionBeforeMerge() {
        var enabledMode: LedgerSyncSettingsInitialMode? = null
        var syncState by mutableStateOf(LedgerSyncSettingsUiState(signedIn = true))
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {},
                ledgerSyncState = syncState,
                ledgerSyncActions = LedgerSyncSectionActions(
                    onPreview = {
                        val result = LedgerSyncSettingsActionResult.Success(
                            LedgerSyncSettingsPreviewUiModel(
                                localRecordCount = 2,
                                cloudRecordCount = 3,
                                insecureHttpTestMode = false
                            )
                        )
                        syncState = syncState.copy(preview = result.value)
                        result
                    },
                    onDismissPreview = { syncState = syncState.copy(preview = null) },
                    onEnable = { mode ->
                        enabledMode = mode
                        LedgerSyncSettingsActionResult.Success(Unit)
                    }
                )
            )
        }

        composeRule.onNodeWithTag("ledger-sync-enable").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("确认并合并").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("服务端可读取", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("生产环境仅通过 HTTPS", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("账号最终注销将删除云端同步数据", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确认并合并").performClick()
        composeRule.waitUntil(5_000) { enabledMode != null }

        assertEquals(LedgerSyncSettingsInitialMode.MERGE, enabledMode)
    }

    @Test
    fun enabledTestModeShowsHttpRiskAndConflictChoices() {
        var resolvedChoice: LedgerSyncSettingsConflictChoice? = null
        val canonical = LedgerSyncPayloadContract.LedgerBook("book-a", "云端账本", 1)
        val candidate = LedgerSyncPayloadContract.LedgerBook("book-a", "本机账本", 1)
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {},
                ledgerSyncState = LedgerSyncSettingsUiState(
                    signedIn = true,
                    enabled = true,
                    insecureHttpTestMode = true,
                    conflicts = listOf(
                        LedgerSyncSettingsConflictUiModel(
                            conflictId = "conflict-a",
                            canonicalVersion = 2,
                            title = "账本冲突",
                            cloudSummary = canonical.name,
                            localSummary = candidate.name
                        )
                    )
                ),
                ledgerSyncActions = LedgerSyncSectionActions(
                    onResolveConflict = { _, _, choice ->
                        resolvedChoice = choice
                        LedgerSyncSettingsActionResult.Success(Unit)
                    }
                )
            )
        }

        composeRule.onNodeWithText("当前为局域网 HTTP 测试同步", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("云端：云端账本").assertIsDisplayed()
        composeRule.onNodeWithText("本机：本机账本").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保留本机").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { resolvedChoice != null }

        assertEquals(LedgerSyncSettingsConflictChoice.CANDIDATE, resolvedChoice)
    }

    @Test
    fun syncControlsRemainReachableAcrossTargetWidthsAndLargeFont() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 700.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                TestDataAndBackupScreen(
                    ledgerEntries = emptyList(),
                    onExportEncryptedBackup = { "backup" },
                    onValidateEncryptedBackup = { _, _ -> },
                    onImportEncryptedBackup = { _, _ -> },
                    onDeleteLocalData = {},
                    onBack = {},
                    ledgerSyncState = LedgerSyncSettingsUiState(signedIn = true, enabled = true)
                )
            }
        }

        listOf(
            DpSize(400.dp, 700.dp) to 1f,
            DpSize(610.dp, 700.dp) to 1f,
            DpSize(900.dp, 1_000.dp) to 1f,
            DpSize(400.dp, 700.dp) to 1.5f
        ).forEach { (size, scale) ->
            composeRule.runOnIdle {
                forcedSize = size
                fontScale = scale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("ledger-sync-now").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("危险区").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun exportCopyDistinguishesCurrentLedgerCsvFromFullBackup() {
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                currentLedgerName = "家庭账本",
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("CSV 仅导出当前账本「家庭账本」", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("加密备份包含全部账本", substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("backup-password-dialog-input").assertCountEquals(0)
        composeRule.onNodeWithText("导出加密备份").assertIsDisplayed()
        composeRule.onNodeWithText("导入加密备份").assertIsDisplayed()
    }

    @Test
    fun exportPromptsForPasswordAndRequiresMoreThanEightCharacters() {
        var exportedPassphrase: String? = null
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { passphrase ->
                    exportedPassphrase = passphrase
                    "backup"
                },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("导出加密备份").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("12345678")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认导出").assertIsNotEnabled()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("9")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认导出").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) { exportedPassphrase != null }
        assertEquals("123456789", exportedPassphrase)
    }

    @Test
    fun encryptedBackupPromptsForPasswordAndRequiresConfirmationBeforeRestore() {
        val encryptedBackup = "AUTO_ACCOUNTING_BACKUP_V4:backup"
        val validatedPassphrases = mutableListOf<String>()
        var validatedBackup: String? = null
        var importedPassphrase: String? = null
        setContentWithBackupFile(encryptedBackup) {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { backup, passphrase ->
                    validatedBackup = backup
                    validatedPassphrases += passphrase
                    if (passphrase != "correct-password") error("invalid")
                },
                onImportEncryptedBackup = { _, passphrase -> importedPassphrase = passphrase },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("backup-password-dialog-input")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("wrong-password")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("密码错误，或备份文件已损坏，请重试")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextClearance()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("correct-password")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("确认替换并恢复")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertEquals(encryptedBackup, validatedBackup)
        assertEquals(listOf("wrong-password", "correct-password"), validatedPassphrases)
        assertEquals(null, importedPassphrase)
        composeRule.onNodeWithText("将替换本机现有数据", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确认替换并恢复").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { importedPassphrase != null }
        assertEquals("correct-password", importedPassphrase)
    }

    @Test
    fun unencryptedFileDoesNotPromptForPassword() {
        val snackbarHostState = SnackbarHostState()
        var validated = false
        setContentWithBackupFile("plain backup") {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> validated = true },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {},
                snackbarHostState = snackbarHostState
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            snackbarHostState.currentSnackbarData != null
        }

        assertFalse(validated)
        assertEquals(
            "所选文件不是受支持的加密备份",
            snackbarHostState.currentSnackbarData?.visuals?.message
        )
        composeRule.onAllNodesWithTag("backup-password-dialog-input").assertCountEquals(0)
        composeRule.onAllNodesWithText("确认替换并恢复").assertCountEquals(0)
    }

    @Test
    fun cancellingValidatedRestoreKeepsImportCallbackUntouched() {
        var imported = false
        setContentWithBackupFile("AUTO_ACCOUNTING_BACKUP_V4:backup") {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> imported = true },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("backup-password-dialog-input")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("secret")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("确认替换并恢复")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()

        assertFalse(imported)
        composeRule.onAllNodesWithText("确认替换并恢复").assertCountEquals(0)
    }

    @Test
    fun deleteRemainsInDangerZoneAndRequiresBothConfirmations() {
        var deleted = false
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = { deleted = true },
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("危险区").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("删除本机数据").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认删除").assertIsNotEnabled()
        composeRule.onNodeWithText("我已了解并完成需要的备份").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("输入 删除本机数据").performTextInput("删除本机数据")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitForIdle()

        assertTrue(deleted)
    }

    @Test
    fun deleteDialogStaysLockedUntilLocalDataReplacementCompletes() {
        val allowDeleteToFinish = CompletableDeferred<Unit>()
        var deletionStarted = false
        composeRule.setContent {
            TestDataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {
                    deletionStarted = true
                    allowDeleteToFinish.await()
                },
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("删除本机数据").performScrollTo().performClick()
        composeRule.onNodeWithText("我已了解并完成需要的备份").performClick()
        composeRule.onNodeWithText("输入 删除本机数据").performTextInput("删除本机数据")
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { deletionStarted }

        composeRule.onNodeWithText("正在删除…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
        composeRule.onNodeWithText("我已了解并完成需要的备份").assertIsNotEnabled()

        allowDeleteToFinish.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("正在删除…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun setContentWithBackupFile(content: String, screen: @Composable () -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test/backup.bak")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(content.toByteArray())
        )
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, uri as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                screen()
            }
        }
        composeRule.waitForIdle()
    }
}

@Composable
@Suppress("LongParameterList")
private fun TestDataAndBackupScreen(
    ledgerEntries: List<BackupLedgerEntryUiModel>,
    currentLedgerName: String = "默认账本",
    onExportEncryptedBackup: suspend (String) -> String,
    onValidateEncryptedBackup: suspend (String, String) -> Unit,
    onImportEncryptedBackup: suspend (String, String) -> Unit,
    onDeleteLocalData: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    ledgerSyncState: LedgerSyncSettingsUiState = LedgerSyncSettingsUiState(),
    ledgerSyncActions: LedgerSyncSectionActions = LedgerSyncSectionActions()
) {
    var state by remember { mutableStateOf(DataAndBackupUiState()) }
    var nextId by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val actions = DataAndBackupActions(
        onExport = { passphrase, writer ->
            state = DataAndBackupUiState(DataAndBackupOperation.Exporting)
            scope.launch {
                val result = runCatching { writer(onExportEncryptedBackup(passphrase)) }
                state = DataAndBackupUiState(
                    notice = DataAndBackupNotice(
                        id = ++nextId,
                        operation = DataAndBackupOperation.Exporting,
                        success = result.isSuccess,
                        detail = result.getOrNull()
                    )
                )
            }
        },
        onValidate = { uiSessionId, backup, passphrase ->
            val requestId = ++nextId
            state = DataAndBackupUiState(DataAndBackupOperation.Validating)
            scope.launch {
                val result = runCatching { onValidateEncryptedBackup(backup, passphrase) }
                state = DataAndBackupUiState(
                    validationResult = DataAndBackupValidationResult(
                        requestId = requestId,
                        uiSessionId = uiSessionId,
                        valid = result.isSuccess
                    )
                )
            }
            requestId
        },
        onImport = { backup, passphrase ->
            state = DataAndBackupUiState(DataAndBackupOperation.Importing)
            scope.launch {
                val result = runCatching { onImportEncryptedBackup(backup, passphrase) }
                state = DataAndBackupUiState(
                    notice = DataAndBackupNotice(
                        id = ++nextId,
                        operation = DataAndBackupOperation.Importing,
                        success = result.isSuccess
                    )
                )
            }
        },
        onDelete = {
            state = DataAndBackupUiState(DataAndBackupOperation.Deleting)
            scope.launch {
                val result = runCatching { onDeleteLocalData() }
                state = DataAndBackupUiState(
                    notice = DataAndBackupNotice(
                        id = ++nextId,
                        operation = DataAndBackupOperation.Deleting,
                        success = result.isSuccess
                    )
                )
            }
        },
        onAttachUi = { ++nextId },
        onDetachUi = {},
        onAcknowledgeValidation = { requestId, uiSessionId ->
            val result = state.validationResult
            if (result?.requestId == requestId && result.uiSessionId == uiSessionId) {
                state = state.copy(validationResult = null)
            }
        },
        onEndValidatedContinuation = {},
        onClearValidation = { state = state.copy(validationResult = null) },
        onAcknowledgeNotice = { id ->
            if (state.notice?.id == id) state = state.copy(notice = null)
        }
    )
    DataAndBackupScreen(
        ledgerEntries = ledgerEntries,
        currentLedgerName = currentLedgerName,
        dataAndBackupState = state,
        dataAndBackupActions = actions,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        ledgerSyncState = ledgerSyncState,
        ledgerSyncActions = ledgerSyncActions
    )
}
