# Step 4.4 — Diagnostics

## Status

Pending

## Goal

将诊断日志刷新、清理、加密导出和取消迁入 `DiagnosticLogsViewModel`，同时保留 Screen 的安全窗口、文件 launcher 和平台写入职责。

## Current Problem

Diagnostic Screen 当前自行获取 Repository、刷新、清理、生成导出、取消和写文件。业务 coroutine 与 `FLAG_SECURE`、敏感显示开关、口令及 ActivityResult 生命周期混在同一 UI owner 中。

## Scope

- logs/loading/export/cancellation operation state。
- Repository refresh、clear、generate encrypted export、cancel。
- `FLAG_SECURE`、ActivityResult、用户选择位置和文件写入继续留在 Screen。
- 加密结果的 one-shot UI handoff，技术按 feature 语义选择。
- 保留现有脱敏、加密、轮转和安全窗口实现。

## Files

- [`apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticLogsScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticLogsScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticLogsContent.kt`](../../../apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticLogsContent.kt)
- [`apps/android/src/main/java/com/bks/feature/diagnostics/AndroidDiagnosticLogRepository.kt`](../../../apps/android/src/main/java/com/bks/feature/diagnostics/AndroidDiagnosticLogRepository.kt)
- [`apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticEncryptedStore.kt`](../../../apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticEncryptedStore.kt)
- [`apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticSanitizer.kt`](../../../apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticSanitizer.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/diagnostics/DiagnosticLogsViewModel.kt` 及对应测试。

## Planned Changes

- 创建 `DiagnosticLogsViewModel`；刷新、清理、导出、取消和 export cancellation 满足独立 owner 准入条件。
- ViewModel 处理 refresh、clear、generate encrypted export 与 cancel。
- 加密结果通过适合本页面语义的 Effect 或 callback 交给 Screen 平台 writer；不规定必须使用 Channel。
- `FLAG_SECURE`、ActivityResult、文件写入、敏感显示开关和口令留在 Screen。
- Screen 在 stop、cancel 和 success 时继续关闭敏感显示并清空口令。
- 保留现有脱敏、加密、轮转和安全窗口逻辑，不改变导出格式。

## State / Ownership Changes

- logs/loading/export/cancel operation：Screen → `DiagnosticLogsViewModel`。
- Diagnostic Repository：Application-scoped dependency，由 ViewModel factory 注入。
- `FLAG_SECURE`、launcher、平台 writer、敏感显示和口令：继续由 Screen/Composition 持有。

## Before

```text
Diagnostic Screen lifecycle/coroutine
→ Repository refresh / clear / encrypted export
→ ActivityResult writer + secure window
```

## After

```text
Screen Action
→ DiagnosticLogsViewModel
→ Diagnostic Repository
→ encrypted export result Effect/callback
→ Screen platform writer + secure window
```

## Invariants

- 诊断脱敏、加密、轮转、export format 与 `FLAG_SECURE` 行为不变。
- 口令和敏感显示状态不进入 `SavedStateHandle`、日志或持久 state。
- Screen 仍负责平台文件 writer 和安全窗口生命周期。
- One-shot handoff 按语义选择 Channel、SharedFlow 或 callback，不机械统一。
- 不修改 API、Room schema、backup format 或敏感数据边界。

## Risks

Medium。错误 lifecycle 或 handoff 可能在取消后继续导出、暴露敏感内容，或未恢复安全窗口状态。

## Tests

- 新增 `DiagnosticLogsViewModelTest`：refresh、clear、export、cancel、failure 和 result handoff。
- 复用 Screen、Secure Window、Repository、Crypto 和 Sanitizer 测试。
- 覆盖 stop/cancel/success 清理口令与敏感显示，确保 export result 不包含未脱敏数据。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.diagnostics.DiagnosticLogsViewModelTest" --tests "com.bks.feature.diagnostics.DiagnosticLogsScreenTest" --tests "com.bks.feature.diagnostics.DiagnosticSecureWindowTest" --tests "com.bks.feature.diagnostics.AndroidDiagnosticLogRepositoryTest" --tests "com.bks.feature.diagnostics.DiagnosticExportCryptoTest" --tests "com.bks.feature.diagnostics.DiagnosticSanitizerTest"
.\gradlew.bat :apps:android:detekt
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

## Stop Conditions

- `FLAG_SECURE`、敏感显示或口令清理行为发生变化。
- Screen 仍自行获取 Repository 或运行导出业务 coroutine。
- ViewModel 开始持有 ActivityResult launcher、平台文件 writer 或持久化口令。
- 只能通过固定 Channel 才能表达业务正确状态，或关键状态被降为 one-shot Effect。
- 需要修改加密/导出格式、敏感数据边界或依赖版本。
- 专项或 Phase 全量 Android JVM tests 失败且根因未解决。

## Completion Criteria

- `DiagnosticLogsViewModel` 拥有 refresh/clear/export/cancel operation state。
- Screen 不获取 Repository、不运行导出业务 coroutine。
- 安全窗口、平台 writer、敏感显示和口令仍由 Screen 管理。
- Effect/callback 技术按页面语义选择，现有脱敏/加密/轮转行为不变。
- 专项测试、必要 Detekt、diff 检查与 Phase 全量 Android JVM tests 通过。

---

## Execution Record

### Actual Changes

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
