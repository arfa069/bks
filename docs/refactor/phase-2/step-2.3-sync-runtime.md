# Step 2.3 — Sync Runtime

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
