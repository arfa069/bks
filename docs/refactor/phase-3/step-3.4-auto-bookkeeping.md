# Step 3.4 — Automatic Bookkeeping

## Status

Completed

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

- 从 `BksAppRuntimeState` 删除 `automaticBookkeepingEnabled` Boolean mirror，并从
  `BksAppActions` 删除 Root 级乐观 setter；`BksAppEffects` 不再把 Room 值复制回 Root。
- 新增 `AutomaticBookkeepingViewModel`：lifecycle-aware 收集 Room enabled Flow，立即投影
  用户目标值，并在写入确认前保持 `writeInProgress`；失败时回滚到持久值并发送提示。
- `BksFeatureOwners` 持有该 ViewModel 和 feature state，Room Flow 仍是 enabled intent 的
  最终持久 source of truth；configuration recreation 不会创建 Route-local Boolean mirror。
- `BksAutomaticBookkeepingRoute` 将 enabled intent、Accessibility permission 和 Service
  health 组合为 `AutomaticBookkeepingUiState`，用户切换时才调用 Repository 写入；打开系统
  设置仍走现有 platform callback。
- `AutomaticBookkeepingScreen` 改为只消费组合后的 `UiState`、渲染状态并发送 action。
- 写入中开关暂时禁用，避免 Room 回流前的连续点击重复发送旧 `checked` 对应的目标值；
  `AutomaticBookkeepingViewModelTest` 覆盖即时投影、重复写入阻断、失败回滚和
  configuration recreation。
- 强化 Screen 和 App/Route 集成测试：enabled=true 且 permission 缺失或 Service
  disconnected 时 intent 保持开启，开关 action 会经 Route 写入 Room 并由 Flow 回显。
- 未修改 Room schema、Accessibility 采集范围、权限判断、Service health 持久方式或
  Review Queue 流程。

### Deviations From Plan

- 初次实现未创建独立 owner；代码审查确认异步 Room 回流会使受控 Switch 在快速连续点击时
  重复发送同一目标值。为拥有写入中状态、即时交互投影、失败回滚与 configuration
  recreation 生命周期，最终按准入规则创建了 `AutomaticBookkeepingViewModel`。
- `LocalPreferencesRepository`、`BillSyncPermission` 和 `BillSyncStateCoordinator` 的现有
  边界已经满足本 Step 不变量，因此没有为了匹配 Files 列表而制造代码改动。

### Test Results

- `.\gradlew.bat :apps:android:testDebugUnitTest --tests
  "com.bks.feature.monitoring.AutomaticBookkeepingScreenTest" --tests
  "com.bks.feature.monitoring.AutomaticBookkeepingViewModelTest" --tests
  "com.bks.MainActivityTest.automaticBookkeepingTogglePersistsThroughRouteAndRoomFlow" --tests
  "com.bks.data.local.LocalPreferencesRepositoryTest" --tests
  "com.bks.feature.billsync.BillSyncPermissionTest"`：通过，18 tests，0 failures。
- `.\gradlew.bat :apps:android:detekt`：通过。首次检查发现新增 `UiState` 与 Screen
  同文件触发 `MatchingDeclarationName`，拆分为同 feature 独立文件后通过；状态收集提升后
  又发现 `BksApp` 触及 `LongMethod` 阈值，归入现有 `BksFeatureOwners` 后通过。
- `git diff --check`：通过；仅输出工作区内既有未修改文件的行尾转换 warning，无 whitespace
  error。
- `.\gradlew.bat :apps:android:testDebugUnitTest`：通过，84 suites / 455 tests，0 skipped，
  0 failures，0 errors。
- `assembleDebug` 未运行：本 Step 未修改 Manifest、Resources、依赖或 Android platform
  configuration，按重构 README 验证规则不需要 APK 验证。
- 最终运行 `.\gradlew.bat --stop` 释放 Gradle Daemon。

### Remaining Risks

- 未做真机页面观察；本 Step 未改变权限、Service 或 Accessibility 行为，风险限于 Compose
  状态装配。permission/service disconnected 与 intent 分离已由 JVM/Compose 测试覆盖。
- 写入中的重复交互已由禁用开关阻断，写入失败会回滚并提示；尚未用真机慢盘或人为注入
  Room 故障观察视觉时序，相关 owner 与 Compose 行为由 JVM 测试覆盖。

### Final Status

Completed — 2026-08-24
