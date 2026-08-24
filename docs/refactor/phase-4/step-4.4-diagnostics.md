# Step 4.4 — Diagnostics

## Status

Completed

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

- 新增 `DiagnosticLogsViewModel`，以 `StateFlow<DiagnosticLogsUiState>` 拥有 Repository 的 enabled/events/stats 投影，以及 refresh、clear、export、cancel、failure 和 export result operation state；Application composition root 通过 lifecycle-scoped factory 注入既有 `DiagnosticLogRepository`。
- 加密导出采用带 request ID 的 rendezvous Channel 交接：ViewModel 只生成密文并拒绝取消后的陈旧 writer completion，Screen 继续在 `Dispatchers.IO` 调用 MediaStore/platform writer，再把非敏感文件名或失败结果回报 ViewModel。密文、口令和 writer 均未进入持久 state、`SavedStateHandle` 或日志。
- `DiagnosticLogsScreen` 不再获取 Repository，也不再通过 Compose scope 运行 refresh、clear 或加密业务 coroutine；Screen/Composition 继续持有 `FLAG_SECURE`、敏感显示、确认/口令 Dialog、平台 writer 和 writer Job。
- stop、离开页面和显式取消会关闭导出 Dialog、取消在途生成/writer 并清除口令 Composition；success/failure 也先关闭口令 Dialog，再显示由 ViewModel stable state 持有、可按 ID 精确消费的非敏感结果。
- writer Job 先登记再启动，旧 writer 的 `finally` 仅在共享槽位仍指向自身时清空，避免旧 writer 延迟退出后丢失新 writer 的 stop/cancel 句柄；refresh 成功只条件清除属于 refresh 的旧失败提示，不覆盖 clear/export 的并发消息。
- 新增 `DiagnosticLogsViewModelTest`，更新 Screen owner test harness，并补充后台期间取消导出、重新进入时口令字段清空、旧/新 writer 重叠取消，以及 refresh 失败后成功恢复的 Robolectric/Compose 回归测试。

### Deviations From Plan

- `DiagnosticLogsContent.kt`、`AndroidDiagnosticLogRepository.kt`、`DiagnosticEncryptedStore.kt` 和 `DiagnosticSanitizer.kt` 无生产改动：当前接口/Flow、加密导出、轮转和双层脱敏已满足 ViewModel 迁移，不需要为 Plan 强制修改稳定实现。
- one-shot 密文交接选用无缓冲 rendezvous Channel，而导出完成/失败/取消结果保留在 `StateFlow`：密文只在当前 Screen collector 存在时交接，关键可见结果不会因重组或 collector 切换而丢失；没有机械地把所有状态降为 Channel effect。
- 当前基线的 `ON_STOP` 只重新遮罩敏感日志，未关闭导出口令 Dialog；本 Step 按 Planned Changes、Tests 和页面“仅本次会话”的安全语义补齐 stop 清理，并记录为计划假设与实际代码的偏差。
- 未新增依赖、百分比 progress 或独立 writer abstraction：底层 Repository 没有稳定进度信号，平台 writer 继续由 Screen 的最小现有 MediaStore seam 承担。

### Test Results

- Step 专项命令通过：`DiagnosticLogsViewModelTest` 8、`DiagnosticLogsScreenTest` 9、`DiagnosticSecureWindowTest` 1、`AndroidDiagnosticLogRepositoryTest` 8、`DiagnosticExportCryptoTest` 2、`DiagnosticSanitizerTest` 4，共 32/32，0 skipped/failures/errors；其中新增测试直接覆盖复审发现的 writer identity 竞态和 refresh 失败→成功恢复。
- Android Phase 全量 `:apps:android:testDebugUnitTest --max-workers=1` 通过：89 suites / 517 tests，0 skipped/failures/errors。首次默认并行全量仅有无关的 `SecureAccountSessionStoreTest.legacyVersionOnePhoneSessionRestoresAndNextSaveUpgradesIt` 因 Robolectric/SharedPreferences 状态未读到而失败；该账号测试类单独重跑通过，随后修复前后均按项目规则串行全量通过，未修改账号代码或测试。
- `:apps:android:detekt` 通过；首次检查发现本 Step 新增的参数/长度/复杂度/函数数问题，已通过同职责内 Dialog 装配提取、overrides 值对象和 writer completion 合并消除，未新增 suppression 或 baseline。
- `git diff --check` 通过。
- 未运行 `assembleDebug`、Instrumentation 或真机测试：本 Step 未修改 Manifest、Resources、Gradle dependency、BuildConfig、Room/API/backup/platform 配置，计划与模块规则不要求 APK 验证。

### Remaining Risks

- MediaStore Downloads 的真实文件创建、OEM 文件系统行为、Activity/process recreation，以及 writer 已进入平台阻塞 I/O 后的取消时序未做设备端验收；ViewModel request ID 会拒绝取消后的陈旧 completion，Screen/Composition 会取消 writer Job，但 Robolectric 不能替代真实 ContentResolver 的中断/回滚行为。
- `FLAG_SECURE` 由既有单元测试覆盖 flag add/clear，Screen 测试覆盖后台重新遮罩和口令清理；尚未在真实设备上观察系统截图、最近任务缩略图和后台/前台切换。
- 加密格式、Keystore store、轮转与 Sanitizer 本轮保持原实现，并由专项回归覆盖；未用真实敏感生产样本或导出文件做手工解密验收，以避免扩大敏感数据边界。

### Final Status

Completed
