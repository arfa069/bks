# Step 4.3 — Backup

## Status

Completed

## Goal

将 backup validate/import/export 与本机数据删除等长操作迁入 `DataAndBackupViewModel`，依赖 Room Flow 自然刷新，并避免为单一 delete 方法机械创建额外 Coordinator。

## Current Problem

Backup Section 当前自行启动业务 coroutine，Root `clearLocalData()` 横跨 Sync、头像、Room 和 Diagnostics。operation state、文件 launcher、口令、加解密与数据清理顺序混在 Screen/Root，恢复或删除后还可能手动修改 Runtime 镜像。

## Scope

- Backup validate、import、export 的 operation state 和业务调用。
- 本机删除的有序 operation 与 finally 清理。
- ActivityResult、文件选择、用户确认和口令继续留在 Screen。
- Sync settings/operations 复用 Step 2.3 的 Sync UI owner。
- 独立 `LocalDataDeletionCoordinator` 的准入判断，默认不创建。

## Files

- [`apps/android/src/main/java/com/bks/feature/settings/DataAndBackupScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/DataAndBackupScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/settings/LocalBackupSection.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/LocalBackupSection.kt)
- [`apps/android/src/main/java/com/bks/feature/settings/PersistedLocalDataBackup.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/PersistedLocalDataBackup.kt)
- [`apps/android/src/main/java/com/bks/feature/settings/LocalDataPortability.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/LocalDataPortability.kt)
- [`apps/android/src/main/java/com/bks/feature/settings/LedgerSyncSettingsSection.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/LedgerSyncSettingsSection.kt)
- [`apps/android/src/main/java/com/bks/BksProfileRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksProfileRoutes.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/settings/DataAndBackupViewModel.kt` 及对应测试。
- 默认不新增：`LocalDataDeletionCoordinator.kt`；只有出现复用或独立 Application 生命周期时才重新评估。

## Planned Changes

- 创建 `DataAndBackupViewModel`；validate/import/export/delete 等长操作满足独立 owner 准入条件。
- validate 只解密和校验，保证零写入。
- import 成功和 delete 完成后只依赖 Room/Repository Flow 刷新 UI，不手动清空 Review/Ledger Runtime 镜像。
- 本机删除默认作为 ViewModel owner 的一个有序 operation，保持既有取消 Sync、清理头像、原子清 Room 与 finally 清诊断日志等顺序/清理语义。
- 只有 delete operation 被其他入口复用或具有独立 Application 生命周期时，才拆出 `LocalDataDeletionCoordinator`，并在 Execution Record 说明依据。
- ActivityResult、文件读写位置选择、用户确认和口令留在 Screen；加解密、validate 和 restore 业务在 owner 中。
- Sync Section 使用 `LedgerSyncViewModel`，不并入 Backup owner。

## State / Ownership Changes

- Backup/delete loading、error、progress、cancellation：Screen/Root → `DataAndBackupViewModel`。
- 账本、Review、Settings 等恢复后的 source of truth：继续是 Room/Repository Flow。
- 文件 launcher、URI、口令和确认 Dialog：继续由 Screen/Composition 持有。
- Sync UI state：继续由 Step 2.3 的 Sync UI owner 持有。

## Before

```text
Screen coroutine / Root clearLocalData()
→ Backup repository / Scheduler / Room / avatar / diagnostics
→ manual Runtime mirror clearing
```

## After

```text
Screen platform callback + Action
→ DataAndBackupViewModel
→ backup repository / ordered delete operation
→ Room transaction / Flow refresh

Sync Section → LedgerSyncViewModel
```

## Invariants

- validate 零写入。
- backup wire format、codec、crypto、metadata 和导入语义不变。
- delete 保持既有顺序、原子数据清理与 finally 语义，不遗漏本地数据域。
- import/delete 后不手动维护 Root 数据镜像。
- 口令不进入 `SavedStateHandle`、日志或持久 state。
- 不修改 Room schema、sync protocol、backup format 或创建不满足准入条件的删除 Coordinator。

## Risks

High。错误操作顺序可能造成半恢复、残留数据、误删、Sync 继续运行，或敏感口令泄漏。

## Tests

- 新增 `DataAndBackupViewModelTest`：validate、import、export、delete 的成功/失败/取消和 Flow refresh。
- 复用 Backup Repository、Portability、DataAndBackup Screen 与 Sync ViewModel 测试。
- 必须证明 validate 零写入、restore/delete 覆盖既有数据域、删除失败仍执行必要 finally 清理。
- 如准入后拆出删除 Coordinator，必须新增其顺序、复用和 lifecycle 测试。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.settings.DataAndBackupViewModelTest" --tests "com.bks.feature.settings.LocalDataBackupRepositoryTest" --tests "com.bks.feature.settings.LocalDataPortabilityTest" --tests "com.bks.feature.settings.DataAndBackupScreenTest" --tests "com.bks.feature.sync.LedgerSyncViewModelTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- validate 发生任何持久写入。
- restore/delete 需要修改 backup format、Room schema、sync protocol 或业务行为。
- 删除顺序、原子清理或 finally 语义无法保持。
- import/delete 仍需要手动清 Root runtime 镜像才能刷新。
- 拟新增 `LocalDataDeletionCoordinator` 不满足复用或独立生命周期准入条件。
- 专项测试失败且根因未解决。

## Completion Criteria

- `DataAndBackupViewModel` 拥有 backup/delete 长操作 state。
- validate 零写入，restore/delete 行为和完整数据域有测试保护。
- import/delete 后由 Room Flow 自然刷新，不操作 Root 镜像。
- Screen 保留 launcher/口令/确认等平台和敏感 UI state。
- Sync Section 复用 Sync UI owner；没有无价值删除 Coordinator。
- 专项测试、必要 Detekt 与 diff 检查通过。

---

## Execution Record

### Actual Changes

- 新增 `DataAndBackupViewModel`，以 `StateFlow<DataAndBackupUiState>` 统一拥有加密备份导出、只读校验、恢复和本机删除的 operation/failure state；同一时刻只接受一个长操作，普通操作随 owner 取消并恢复 Idle，删除沿用 `NonCancellable` 完成既有不可中断清理边界。
- `DataAndBackupViewModel` 在恢复前等待 Review operation idle，再通过 `LedgerViewModel.runLocalDataReplacement` 执行原子 restore；恢复完成依赖 Repository/Room Flow 投影，不手动写 Review、Ledger 或 Root runtime 镜像。
- 本机删除顺序保持为 Review idle → 清头像缓存 → 清 cloud settings 本地状态 → disable Sync → `LedgerViewModel.clearLocalData` 原子清理 Room/重建默认数据 → `finally` 清 diagnostics。移除了 Root 中对 `runtime.categorizationRules` 的手动清空，分类规则继续由 `LocalPreferencesRepository.categorizationRules` Flow 自然刷新。
- `DataAndBackupScreen` / `LocalBackupSection` 仅保留文件 launcher、URI/MediaStore 读写、CSV 导出、口令和确认 Dialog；加密、validate、restore/delete coroutine 与 busy/failure state 迁入 ViewModel。口令只存在于短生命周期 Compose 状态和调用参数中，未进入 ViewModel state、`SavedStateHandle`、日志或持久化。
- `BksApp` 创建并 lifecycle-scope `DataAndBackupViewModel`，`BksProfileRoutes` 只装配 state/actions；Ledger Sync Section 继续直接使用 Step 2.3 的 `LedgerSyncViewModel`。
- 新增 `DataAndBackupViewModelTest`，并更新 `DataAndBackupScreenTest` 的 owner test harness，覆盖成功、失败、取消、并发屏蔽、删除顺序与 finally 清理。
- `LedgerSyncRuntime.disable()` 现通过 `LedgerSyncCoordinator` 的既有全局 mutex 等待在途 Sync 完成后再清 Sync state；backup import 也在同一 mutex 内完成完整本机 replacement，防止旧网络响应在删除/恢复后重新写入 Room。
- restore transaction 在替换正式数据后清除恢复前的 Sync conflicts、解除 metadata conflict block、清空旧 outbox，再依据保留的 server metadata 与恢复后快照重建 mutations；metadata-less stale create、旧 conflict payload 和 blocked metadata 不再跨恢复残留。
- export/import/delete 结果改为带 ID、可确认消费的非敏感 ViewModel notice；validation 使用 UI session/request ID 交接，Composition detach 会立即逻辑失效并取消旧校验。已校验或校验失败但仍依赖当前文件/口令的 continuation 只在 ViewModel 中保留非敏感 session ID，重建时明确提示重新选择；backup 正文、URI 与口令始终不进入 ViewModel state。

### Deviations From Plan

- 未新增 `LocalDataDeletionCoordinator`：删除仅有“数据与备份”一个入口，且不需要独立 Application 生命周期；不满足复用/独立生命周期准入条件。
- `LocalDataPortability.kt` 和 `LedgerSyncSettingsSection.kt` 无生产改动：backup format/CSV 契约稳定，Sync Section 已复用 `LedgerSyncViewModel`。`PersistedLocalDataBackup.kt` 仅在既有 import transaction 中补齐 Sync outbox/conflict 一致性清理，没有改 wire format、codec、crypto、schema 或正式数据替换语义。
- 没有新增百分比式 progress：底层 backup/Room operation 没有稳定、行为等价的进度信号；UI state 记录当前具体 operation、busy、failure 和取消回 Idle，保留原有“请稍候/正在恢复/正在删除”可见语义。

### Test Results

- `DataAndBackupViewModelTest`：17/17 通过；除原有 operation/delete 覆盖外，新增同一 ViewModel 上的 UI detach/attach、在途 validation 取消、已校验/失败 continuation 失效、attach-before-old-detach、notice 精确消费与覆盖防护。
- Step 专项命令通过：`DataAndBackupViewModelTest` 17、`LocalDataBackupRepositoryTest` 12、`LocalDataPortabilityTest` 3、`DataAndBackupScreenTest` 10、`LedgerSyncViewModelTest` 4、`LedgerSyncCoordinatorTest` 3，共 49/49，0 skipped/failures/errors。
- `LedgerSyncCoordinatorTest` 覆盖 Sync 先持锁时 disable/replacement 等待、replacement 先持锁时新 Sync 等待，以及 disable 最终清空 Sync state；Backup repository 回归覆盖 metadata-less stale outbox、旧 conflict/blocked metadata 与敏感旧 payload 清理。
- Android 全量 `:apps:android:testDebugUnitTest` 通过：88 suites / 507 tests，0 skipped/failures/errors。首次全量尝试仅因 Windows 上 `.robolectric-download-lock` 被其他进程占用导致 `MainActivityTest` class initialization 失败；该类单独重跑通过，随后全量重跑通过。
- `:apps:android:detekt` 通过；`git diff --check` 通过。
- 未运行 `assembleDebug`、Instrumentation 或真机测试：本 Step 未修改 Manifest、Resources、Gradle dependency、BuildConfig 或平台配置，计划与模块规则不要求 APK 验证。

### Remaining Risks

- MediaStore Downloads 的真实文件创建、系统文件选择器及 Activity/process recreation 未做设备端验收；Robolectric 覆盖 launcher/口令/确认/锁定/结果文案，ViewModel 状态机测试覆盖同一 owner 上的旧 UI detach 与新 UI attach，但不能替代真实 OEM 文件系统和 Activity 重建联测。
- 删除和恢复的数据库完整性由现有 Room/Robolectric repository、Ledger replacement 及本轮全量测试覆盖；未额外运行设备端 SQLite Instrumentation。
- `BksRouteContext` 仍暂时携带该 ViewModel/state，这是当前已完成 Step 的桥接形态；Route God Layer 收敛属于 Step 5.1，本 Step 未提前执行。

### Final Status

Completed
