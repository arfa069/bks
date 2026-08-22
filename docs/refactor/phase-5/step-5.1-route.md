# Step 5.1 — Route

## Status

Pending

## Goal

让所有 Route 收敛为 Navigation、ViewModel/StateHolder 获取、lifecycle-aware state collection、平台 callback 和 Screen 装配，不再通过 `BksRouteContext` 访问任意依赖或执行业务 coroutine。

## Current Problem

`BksRouteContext` 同时携带 dependencies、runtime、presentation、bindings、actions、appState 和 coroutineScope，形成 Service Locator/God Context。Ledger/Profile Route 还直接执行 Repository、Gateway、Sync 或持久化操作。

## Scope

- `BksRouteHost`、`BksLedgerRoutes`、`BksProfileRoutes` 和其他 Route 的参数与职责。
- 逐 Route 移除 `BksRouteContext`/`BksRouteContentContext` 消费。
- Feature owner 获取与 lifecycle-aware state collection。
- Session gate、登录成功和关键导航的稳定 State 驱动。
- 临时 legacy context 只保留未迁移消费者，最终由 Step 7.1 删除定义。

## Files

- [`apps/android/src/main/java/com/bks/BksRouteContext.kt`](../../../apps/android/src/main/java/com/bks/BksRouteContext.kt)
- [`apps/android/src/main/java/com/bks/BksRouteHost.kt`](../../../apps/android/src/main/java/com/bks/BksRouteHost.kt)
- [`apps/android/src/main/java/com/bks/BksLedgerRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksLedgerRoutes.kt)
- [`apps/android/src/main/java/com/bks/BksProfileRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksProfileRoutes.kt)
- [`apps/android/src/main/java/com/bks/AppNavigationModels.kt`](../../../apps/android/src/main/java/com/bks/AppNavigationModels.kt)
- [`apps/android/src/main/java/com/bks/ui/BksAppState.kt`](../../../apps/android/src/main/java/com/bks/ui/BksAppState.kt)

## Planned Changes

- 逐 Route 以明确参数和 feature owner factory 替换 `BksRouteContext`。
- Route 不接收整个 Container、Repository、Gateway、Scheduler 或任意 CoroutineScope。
- 删除 `BksLedgerRoutes`、`BksProfileRoutes` 与其他 Route 中的数据库、网络、同步和业务持久化操作。
- Route 只获取 ViewModel/StateHolder、lifecycle-aware collect `UiState`、连接平台 callback 并装配 Screen。
- Session gate、登录成功和关键导航由稳定 State 驱动；Snackbar、Intent、文件请求等 one-shot UI 输出使用对应 feature 已选择的方案。
- 不在本 Step 提前删除仍有合法未迁移消费者的 legacy bridge；每迁一个 Route 就移除对应 context 字段。

## State / Ownership Changes

- Route 不再拥有 feature state、业务 coroutine 或持久化错误处理。
- Feature state 由 Phase 2–4 的 Coordinator/ViewModel/StateHolder 持有。
- Navigation 仍由 `BksAppState`/Route host 持有。
- 平台 callback 仍由 Activity/Route/Screen 边界持有。

## Before

```text
Route
→ BksRouteContext
→ runtime / presentation / actions / coroutineScope
→ arbitrary Repository / Gateway / Scheduler
```

## After

```text
Route(args, appState, feature factory, platform bindings)
→ Feature ViewModel / StateHolder
→ lifecycle-aware UiState + Actions
→ Screen
```

## Invariants

- Route 不执行数据库、网络、同步或业务 coroutine。
- Route 不接收整个 Container，也不形成新的缩小版 Service Locator。
- 登录成功、Session 切换与关键导航由稳定 State 驱动。
- Navigation 行为、Screen 可达性和平台 callback 行为不变。
- 不修改业务行为、Room schema、API、sync protocol 或 backup format。

## Risks

Medium。若前置 feature owner 未完成，过早移除 Context 会造成 Big Bang；若把 Context 换成 Container 参数，只会形成新的 Service Locator。

## Tests

- `MainActivityTest`：Root gate、路由和 Activity callback。
- `BksAppStateTest`：导航 state/return target。
- `ProfileScreenTest` 与受迁移 Route 对应 Screen tests：装配和可达性。
- 每迁一个 Route，补运行该 feature 在 Phase 2–4 已有的 owner 测试。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.MainActivityTest" --tests "com.bks.ui.BksAppStateTest" --tests "com.bks.feature.profile.ProfileScreenTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- Steps 2.1–4.4 的相关 feature owner 尚未完成或不稳定。
- Route 必须接收整个 Container/Context 才能装配 feature。
- Route 仍需要 Repository/Gateway/Scheduler 或任意业务 CoroutineScope。
- 关键导航只能通过 one-shot Effect 才能工作。
- 移除 Context 会改变业务行为或需要扩大 Step 范围。
- 专项测试失败且根因未解决。

## Completion Criteria

- 所有已迁移 Route 只持有明确参数、feature owner、Navigation 和平台 binding。
- `BksLedgerRoutes`、`BksProfileRoutes` 等不再执行 Repository/Cloud/Sync 业务。
- `BksRouteContext` 字段随消费者逐项移除，没有用 Container 创建替代 Service Locator。
- 关键导航由稳定 State 驱动。
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
