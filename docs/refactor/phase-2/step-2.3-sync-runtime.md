# Step 2.3 — Sync Runtime

## Status

Completed

## Goal

分离 Application-scoped `LedgerSyncRuntime`、Sync UI operation owner 与 Worker invocation，使 Compose Root 不再直接预览、调度或执行同步。

## Current Problem

Ledger sync 的 durable Room state/outbox、账户 gate、UI busy/dialog/conflict 状态、WorkManager 调度和 Worker token 恢复目前跨 Root Effect、Route、Screen 与 Worker 混合，生命周期和 source of truth 不清晰。

## Scope

- Application lifecycle 的 sync runtime 与 resume forwarding。
- Sync UI owner 的 busy、dialog、conflict 和多操作状态。
- Worker 对 Container 依赖图的使用及每次 `doWork()` 的独立安全 token 恢复。
- Entity → Sync UI model 映射。
- 保持 outbox、profile mismatch、conflict handling 与 offline-first 行为。

## Files

- [`apps/android/src/main/java/com/bks/feature/sync/LedgerSyncCoordinator.kt`](../../../apps/android/src/main/java/com/bks/feature/sync/LedgerSyncCoordinator.kt)
- [`apps/android/src/main/java/com/bks/feature/sync/LedgerSyncWorker.kt`](../../../apps/android/src/main/java/com/bks/feature/sync/LedgerSyncWorker.kt)
- [`apps/android/src/main/java/com/bks/feature/sync/LedgerSyncUiState.kt`](../../../apps/android/src/main/java/com/bks/feature/sync/LedgerSyncUiState.kt)
- [`apps/android/src/main/java/com/bks/feature/sync/LedgerSyncLocalStore.kt`](../../../apps/android/src/main/java/com/bks/feature/sync/LedgerSyncLocalStore.kt)
- [`apps/android/src/main/java/com/bks/feature/sync/LedgerSyncRepository.kt`](../../../apps/android/src/main/java/com/bks/feature/sync/LedgerSyncRepository.kt)
- [`apps/android/src/main/java/com/bks/feature/settings/LedgerSyncSettingsSection.kt`](../../../apps/android/src/main/java/com/bks/feature/settings/LedgerSyncSettingsSection.kt)
- [`apps/android/src/main/java/com/bks/BksAppEffectsContext.kt`](../../../apps/android/src/main/java/com/bks/BksAppEffectsContext.kt)
- 计划新增：`LedgerSyncRuntime.kt`、`LedgerSyncViewModel.kt` 及对应测试。

## Planned Changes

- 创建 Application-scoped `LedgerSyncRuntime`，观察 durable Room sync state/outbox 和 Account gate。
- 创建 `LedgerSyncViewModel`：当前 busy、dialog、conflict 和多个交互操作满足独立 owner 准入条件。
- Root 只转发 Application resume，不执行 preview、schedule 或 synchronize。
- Worker 从 `BksAppContainer` 获取 Application-scoped sync dependencies，但每次 `doWork()` 独立恢复并验证安全 token。
- Sync conflict Entity 在 UI owner 内映射为 UI model，不向 Screen 泄漏数据实体。
- Data/Backup 尚未迁移期间可提供只读 legacy state projection，并在 Step 4.3 删除。

## State / Ownership Changes

- Durable sync/outbox source of truth：继续由 Room/现有本地存储负责。
- Application 调度与账户 gate：Root Effect → `LedgerSyncRuntime`。
- UI busy/dialog/conflict/operation：Root/Route/Screen → `LedgerSyncViewModel`。
- Worker operation/token：每次 Worker invocation 独立持有，不提升为 Application singleton。

## Before

```text
Compose Root / Route / Settings Screen
→ Sync Coordinator / Scheduler / Repository
→ Runtime mirrors

Worker
→ 自行重建依赖与 token 状态
```

## After

```text
Room sync state + Account gate
→ LedgerSyncRuntime
→ scheduling / resume

Route → LedgerSyncViewModel → UiState / Actions → Screen

Worker invocation
→ BksAppContainer dependencies
→ per-doWork token restore
```

## Invariants

- 保持 offline-first、account scope、conflict handling、pending/outbox 和 profile mismatch 行为。
- 本地写入与 outbox 仍在同一事务；远端 apply 不产生反向 outbox。
- Worker 每次 invocation 独立恢复 token，不缓存跨账户认证状态。
- Sync account/profile 绑定使用稳定 State，不依赖 one-shot Effect。
- 不修改 sync protocol、API、Room schema 或 WorkManager contract。
- `LedgerSyncRuntime.start()` 必须幂等。
- 重复调用不得建立重复 outbox/session collector，也不得造成重复 WorkManager schedule。
- Runtime 的长期 coroutine 属于 Application 生命周期，不得绑定 Route/Compose scope。
- resume event 可以多次到达，但必须由 Runtime 自身保证调度去重和 eligibility 判断。

## Risks

High。错误 gate 或生命周期可能重复调度、覆盖其他账户数据、漏 outbox，或让 Worker 使用过期 token。

## Tests

- 新增 `LedgerSyncRuntimeTest`、`LedgerSyncViewModelTest`、`LedgerSyncWorkerTest`。
- 复用 Outbox、Remote Merge、Profile Switch、Scheduler 和 HTTP Repository 测试。
- 覆盖 resume gate、stale token、conflict UI mapping、Worker 独立 token 恢复和重复调度防护。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.sync.LedgerSyncRuntimeTest" --tests "com.bks.feature.sync.LedgerSyncViewModelTest" --tests "com.bks.feature.sync.LedgerSyncWorkerTest" --tests "com.bks.feature.sync.LedgerSyncOutboxTest" --tests "com.bks.feature.sync.LedgerSyncRemoteMergeTest" --tests "com.bks.feature.sync.LedgerSyncProfileSwitchTest" --tests "com.bks.feature.sync.LedgerSyncSchedulerTest"
.\gradlew.bat :apps:android:detekt
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

## Stop Conditions

- outbox、profile mismatch、remote merge 或 conflict 行为发生变化。
- Root 仍直接 preview、schedule 或 synchronize。
- Worker 需要重建第二套 Repository/Coordinator graph，或缓存跨 invocation token。
- 正确性依赖 UI Effect 是否被消费。
- 继续需要修改 sync protocol、API、Room schema 或升级依赖。
- 专项或 Phase 全量 Android JVM tests 失败且根因未解决。

## Completion Criteria

- `LedgerSyncRuntime` 是 Application 调度和 Account gate owner。
- `LedgerSyncViewModel` 只拥有 UI operation state，Room 仍是 durable source of truth。
- Compose Root 只转发 resume，不执行同步业务。
- Worker 使用 Container 的生产依赖图并在每次 `doWork()` 独立恢复 token。
- Entity 不泄漏到 Sync Screen，legacy projection 的删除点明确。
- 专项测试、必要 Detekt、diff 检查与 Phase 全量 Android JVM tests 通过。

---

## Execution Record

### Actual Changes

- 新增 Application-scoped `LedgerSyncRuntime`，直接收集 `AccountSessionCoordinator` 稳定状态与 Room sync state/outbox/conflict Flow，并一次性注册正式账本表的 invalidation observer；`start()`/`stop()` 均幂等，长期 collector 使用 Container 的 Application scope。Runtime 统一拥有 verified + signed-in + enabled gate、远端 profile preview、profile mismatch 稳定状态、周期/即时 WorkManager 调度、resume forwarding 与关闭同步时的调度取消。
- Root 中原有 Room observer、三 Flow combine、远端 preview、eligibility 判断与 WorkManager 调度已删除；Compose 仅 lifecycle-aware 收集 `LedgerSyncViewModel.uiState`，并在 `ON_RESUME` 时转发 `LedgerSyncRuntime.onResume()`。同步 UI state 不再写入 `BksAppRuntimeState` legacy projection，而是经 `BksRouteContext` 直接提供给 Profile、Data/Backup 与账户切换对话框。
- 新增 lifecycle-scoped `LedgerSyncViewModel`，统一拥有 preview/dialog、preview busy、sync busy、account-switch busy 与逐 conflict resolving 状态；preview、enable、sync-now、disable、resolve 和 switch-account 交互均通过该 owner。`AccountSyncConflictEntity` 在 ViewModel 内映射为 `LedgerSyncConflictUiModel`，Screen 不再引用 Room Entity、同步 JSON contract 或 payload parser。
- `BksAppContainer` 现在持有唯一生产 `LedgerSyncCoordinator` 与 `LedgerSyncRuntime`；`BksApplication` 启动 Runtime，account override 测试路径创建与 override Session 匹配的 Runtime。Worker 复用 Container Coordinator，不再重建第二套 graph；`runLedgerSyncWorkerInvocation` 保证每次 `doWork()` 先重新恢复当前安全凭据、再检查 durable enabled state，并将 retryable/terminal 失败保持为 WorkManager retry/failure。
- `DataAndBackupScreen` 将同步回调归并为 `LedgerSyncSectionActions`，Settings Screen 的 busy/dialog 不再由 `remember` 持有；现有确认文案、HTTP 风险提示、冲突选择、outbox/profile mismatch/offline-first 行为与 Coordinator 同步算法保持不变。
- Runtime gate 在账户安全恢复完成前不取消既有 WorkManager；远端 preview 失败后由下次 resume 重试。每次 gate 变化递增 generation、取消旧 preview，并在旧请求返回后再次核对 generation/gate，防止旧 token 结果调度任务。成功 enable/switch 会通过 Runtime 立即确认当前 durable profile、关闭 mismatch dialog 并恢复 periodic/now 调度，不依赖后续额外 preview 成功。
- `LedgerSyncViewModel` 的 transient preview/busy state 绑定当前 Session；Session 变化时清空 operation state，旧账户 preview 返回后也必须通过 Session identity 复核才可写入 UI。新增 `LedgerSyncRuntimeTest`、`LedgerSyncViewModelTest`、`LedgerSyncWorkerTest`，并调整 Container、characterization 与 Data/Backup UI 测试，覆盖幂等启动、verified resume gate、恢复/离线顺序、瞬时 preview 失败重试、旧请求失效、switch 成功后 follow-up preview 失败、跨账户 preview 丢弃、profile mismatch、Entity → UI 映射、逐 invocation token 恢复及 retry/failure 映射。

### Deviations From Plan

- 当前依赖图有 `ViewModel`/`viewModelScope`，但没有可直接调用 Compose `viewModel()` 的 artifact；因此按总览允许的最小依赖例外，显式加入当前已解析版本 `androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4`。依赖复审时先以该直接 artifact 运行 `dependencyInsight`，再临时移除它重跑等价基线；两次 `debugRuntimeClasspath` 的 `lifecycle-runtime` 均解析为 `2.9.4`，证明本 Step 没有升级 Lifecycle 或其他依赖，随后恢复最小直接 artifact。
- Plan 预期 Data/Backup 尚未迁移时保留只读 legacy projection；实际已能在本 Step 以最小改动让 Route 直接消费 ViewModel state，因此未创建 projection，也不需要等待 Step 4.3 删除。现有整个 `BksAppRuntimeState` God Layer 的其他 feature 字段仍按 Step 7.1 处理。
- 为建立实际 Application owner 与迁移所有消费者，最小修改了 Plan 清单外的 `BksApplication.kt`、`BksAppContainer.kt`、`BksAppDependencies.kt`、`BksApp.kt`、`BksAppRuntime.kt`、`BksRouteContext.kt`、`BksLedgerRoutes.kt`、`BksProfileRoutes.kt`、`BksDialogs.kt`、Gradle catalog/build 文件及对应测试；未进入 Ledger、Review、Backup 或后续 Step。
- 未修改 `LedgerSyncRepository.kt`、sync protocol、API、Room schema、WorkManager unique-work contract 或 `LedgerSyncCoordinator` 同步算法。`LedgerSyncLocalStore.database` 仅从 private 收窄调整为 module-internal，供同 feature Runtime 注册/移除 observer，没有新增数据真相或持久化行为。
- `DataAndBackupScreen` 是既有多职责长参数 Screen；同步 actions 归并后仍超过 Detekt 阈值。为避免在本 Step 扩大为 Backup/Delete Screen API 重构，仅对该既有签名增加局部 `LongParameterList` suppression；没有新增 baseline 或通用 wrapper。

### Test Results

- Step 指定专项命令（`LedgerSyncRuntimeTest`、`LedgerSyncViewModelTest`、`LedgerSyncWorkerTest`、Outbox、Remote Merge、Profile Switch、Scheduler）：最终通过，7 suites、28 tests、0 failures；其中新增 5 个审查回归用例覆盖 restoring/offline 不取消、preview failure resume 重试、旧 gate 请求失效、switch 后 preview failure 仍关闭 dialog、跨账户 preview 丢弃。
- 补充回归（`DataAndBackupScreenTest`、`BksAppContainerTest`、`SyncRuntimeCharacterizationTest`、`MainActivityTest`）：通过；与专项合并运行共 11 类测试，0 failures。
- `:apps:android:detekt`：通过，0 issues。首次运行报告 3 个长参数问题；移除 Runtime 的独立 Database 参数、让确认对话框直接读取 UI state、归并 sync section actions 后，仅保留既有 Data/Backup Screen 的局部 suppression，复跑通过。
- `:apps:android:testDebugUnitTest --max-workers=1`：最终代码通过，80 suites、436 tests、0 failures、0 errors、0 skipped。
- `:apps:android:assembleDebug`：通过；D8 仍输出既有 `wechat-sdk-android-6.8.40` stack-map warnings，但未导致构建失败，且与本 Step 同步改动无关。
- `git diff --check`：通过。
- 首轮新增测试因 Room Flow 的查询运行在外部 executor，单纯 `advanceUntilIdle()` 未等待真实 emission；测试改为等待 Runtime/ViewModel 的稳定 `StateFlow` 或 scheduler signal 后通过。另有一个 Data/Backup UI 测试仍按旧 Screen-local preview 假设等待对话框；改为模拟 ViewModel state projection 后原有文案和交互断言通过。生产断言未放宽。
- 审查修复首轮专项有 1 个新 switch 测试失败，根因是测试仓库未实现该真实流程必经的 outbox push；补为按 mutation 返回 accepted result 后，同一专项与完整 suite 均通过。修复后首次 Detekt 报告 Runtime `TooManyFunctions`/`ComplexCondition`；将重复 gate 条件收回 `SchedulingGate.canPreview` 并内联 resume retry 后复跑为 0 issues，没有新增 suppression。

### Remaining Risks

- 未运行 instrumentation、真机、真实后端或多设备同步验收；本 Step 未修改协议、API、Room schema、Manifest、权限或可见产品契约，Android JVM/Robolectric 覆盖 owner、Room state、WorkManager scheduling 与 UI wiring。真实进程死亡、后台调度和跨设备并发仍需后续集成/验收覆盖。
- Runtime 在 restoring、`OfflineUnverified`/deletion cooling-off 时停止新 eligibility/resume enqueue，但保留既有已注册 periodic work；恢复中 token 尚为空不会触发 cancel，并有确定性 JVM 测试覆盖。瞬时 preview failure 不会建立新调度，但会在后续 resume 重试；Worker 仍以每次 invocation 的安全存储 token 和 durable enabled state 为最终 gate。
- `LedgerSyncCoordinator` 既有 companion `Mutex` 继续序列化进程内同步操作；本 Step 没有改变该并发边界。Worker、Runtime preview 和 UI operation 共享 Container graph，但不缓存 token。

### Final Status

Completed
