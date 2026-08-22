# Step 4.3 — Backup

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
