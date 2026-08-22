# Step 3.4 — Automatic Bookkeeping

## Status

Pending

## Goal

分离自动记账的持久 intent、Android 系统权限和 Accessibility Service health，并按准入规则决定是否需要独立 `AutomaticBookkeepingViewModel`/StateHolder。

## Current Problem

Root 当前持有 automatic Boolean，UI 容易把用户持久启用意图、系统权限与 Service 当前连接健康混成一个状态。若只为一个 Boolean 和 setter 创建 ViewModel，又会产生无状态微型 wrapper。

## Scope

- 自动记账 enabled intent 的 Room Flow 与写入状态。
- Accessibility permission/service health 的平台 binding。
- Route 对 feature state 与平台状态的组合。
- Screen 打开系统设置的 callback。
- 独立 owner 的执行时准入判断。

## Files

- [`apps/android/src/main/java/com/bks/feature/monitoring/AutomaticBookkeepingScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/monitoring/AutomaticBookkeepingScreen.kt)
- [`apps/android/src/main/java/com/bks/data/local/LocalPreferencesRepository.kt`](../../../apps/android/src/main/java/com/bks/data/local/LocalPreferencesRepository.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncPermission.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncPermission.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncStateCoordinator.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncStateCoordinator.kt)
- [`apps/android/src/main/java/com/bks/BksProfileRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksProfileRoutes.kt)
- 候选新增：`AutomaticBookkeepingViewModel.kt` 或纯 Kotlin state holder，仅在满足准入条件时创建。

## Planned Changes

- enabled 的持久 source of truth 继续使用 Room/Preferences Repository Flow。
- permission 与 Service connected/health 继续作为 Android platform state，不反写 enabled intent。
- Route 只合并 feature state 与平台 bindings，并传给 Screen。
- Screen 只渲染、发送 action 和请求打开系统设置。
- 若存在独立 Room Flow、写入中状态、失败回滚和 configuration recreation，则创建独立 owner；否则并入已有自然 settings/monitoring owner。
- 不允许为一个 Boolean 和 setter 创建纯转发 wrapper。

## State / Ownership Changes

- enabled intent：Root Boolean mirror → Room Flow 驱动的自然 feature owner。
- permission/service health：继续由 Android/BillSync platform coordinator 提供，不与 enabled 合并为单一真假值。
- 临时系统设置 launcher：继续由 Route/Screen 平台 callback 持有。

## Before

```text
Root automatic Boolean
↔ Preferences
↔ permission / service health
→ Screen callbacks
```

## After

```text
Room enabled intent → feature state owner
Android permission + service health → Route platform binding
Route → combined UiState → Screen
```

## Invariants

- enabled 的真相仍在持久层。
- permission 被撤销或 Service disconnected 不得反写关闭 enabled。
- 自动记账候选仍必须进入 Review Queue。
- 不扩大 Accessibility 采集范围或修改权限行为。
- 不创建不满足准入条件的 ViewModel/Coordinator/Wrapper。

## Risks

Low。主要风险是把运行时 health 误写回持久 intent，或为简单转发新增无价值 owner。

## Tests

- 复用 `AutomaticBookkeepingScreenTest`、`LocalPreferencesRepositoryTest` 和 `BillSyncPermissionTest`。
- 明确覆盖 enabled=true 且 permission/service disconnected 时 intent 保持不变。
- 如创建独立 owner，新增写入中、失败回滚和 configuration recreation 测试。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.monitoring.AutomaticBookkeepingScreenTest" --tests "com.bks.data.local.LocalPreferencesRepositoryTest" --tests "com.bks.feature.billsync.BillSyncPermissionTest"
.\gradlew.bat :apps:android:detekt
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

如创建 state owner，必须加入其专属测试。

## Stop Conditions

- permission/service disconnected 会反写关闭 enabled。
- enabled 仍由 Root Boolean 作为 source of truth。
- 候选 owner 不满足独立状态、生命周期、多异步或错误回滚准入条件。
- 为继续迁移需要扩大 Accessibility 采集范围、修改 Room schema 或改变业务行为。
- 专项或 Phase 全量 Android JVM tests 失败且根因未解决。

## Completion Criteria

- enabled intent、permission 和 service health 是三个语义明确的状态来源。
- Root 不再持有 automatic Boolean mirror。
- Route 只组合 state/platform binding，Screen 只渲染和打开系统设置。
- 独立 owner 的创建与否已按准入规则记录，没有无状态微型 wrapper。
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
