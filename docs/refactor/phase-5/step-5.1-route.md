# Step 5.1 — Route

## Status

Blocked

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
- `BksAppRuntimeTest`：账户 Session 与云设置使用同一快照派生 presentation。
- `BksDialogsTest`：账号切换成功/失败提示，以及取消退出的操作顺序。
- `ProfileScreenTest` 与受迁移 Route 对应 Screen tests：装配和可达性。
- 每迁一个 Route，补运行该 feature 在 Phase 2–4 已有的 owner 测试。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.MainActivityTest" --tests "com.bks.ui.BksAppStateTest" --tests "com.bks.feature.profile.ProfileScreenTest" --tests "com.bks.BksAppRuntimeTest" --tests "com.bks.BksDialogsTest"
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

- 删除 `BksRouteContext`/`BksRouteContentContext`：全部现有消费者改为显式 Route 参数，未用 Container 或新的聚合 Context 替代。
- `BksRouteHost` 只持有 `BksAppState`、`AppRoute` 和显式导航 content callback；Manual Entry、Ledger、Reports、Review、Profile 子路由分别接收既有 feature `UiState`/ViewModel、导航状态或平台 binding。
- 账号入口和账号管理 Route 通过 `ViewModelProvider.Factory` 获取既有 Phase 4 owner；新增 `BksRouteFactories.kt` 只在 `BksApp` Composition Root 将既有账号依赖装配为 factory，Route 不接收 `BksAppDependencies`。
- `BksLedgerSyncAccountSwitchDialog` 不再接收 Context，也不再自行启动 coroutine 或访问 Repository；它只接收稳定同步状态和显式操作 callback。原有切换、提示、远端退出和本地模式切换时序仍由 `BksApp` 现有 Composition Root 装配。
- 账户 Session、删除状态、云端 AI 设置和同步中状态由 `AccountSessionCoordinatorState` 与 `AccountCloudSettingsState` 直接派生为同一 `BksAppPresentation`；不再先经 `LaunchedEffect` 镜像到 legacy runtime，避免账户切换时出现跨快照 UI 状态。
- `BksProfileRoutes` 的账号管理退出导航继续由 `AccountSessionCoordinatorState` 的稳定 Session state 驱动；Screen 可达性、返回目标、列表状态、Snackbar 和 Activity/platform callback 仍沿用原有状态源。

### Deviations From Plan

- 本 Step 未完成全部 Completion Criteria：`ReviewQueueScreen` 的当前生产 API 仍直接要求 `AiCategorizationGateway`，因此 `BksReviewRoute` 必须保留一个显式 gateway 参数。把该边界改成 client/action/owner 需要修改 Screen 的业务装配契约，属于 Step 5.2；按本 Step 的“Route 仍需要 Gateway”与“需要扩大 Step 范围”Stop Conditions 停止，不提前执行 Step 5.2，也不为 Plan 强制创建微型 ViewModel/Coordinator。
- `AppNavigationModels.kt` 与 `BksAppState.kt` 无需修改：当前 `AppRoute`、saveable navigation state 和 Account Entry return target 已满足稳定 State 导航要求。
- 显式展开 Route 装配后，`BksApp` 暂时超过 Detekt 的方法长度/复杂度阈值；本 Step 对该 Composition Root 添加局部 `LongMethod`/`CyclomaticComplexMethod` suppression，而没有重新引入聚合 locator。Application God Layer 的最终拆除仍属于 Step 7.1。
- 复审后新增 `BksAppRuntimeTest` 与 `BksDialogsTest`，直接锁定账户/云设置快照派生以及账号切换/取消退出回调；没有为测试引入新的 ViewModel、Coordinator 或聚合 Context。

### Test Results

- 最终专项组合命令通过：`MainActivityTest` 19、`BksAppStateTest` 1、`ProfileScreenTest` 5，以及 Account Session/Cloud Settings/Account Entry/Account Management/Ledger/Review/Automatic Bookkeeping/Data and Backup/Ledger Sync 9 个 owner suites 82，共 12 suites / 107 tests，0 skipped/failures/errors。
- 复审修复后，新增 `BksAppRuntimeTest` 4 与 `BksDialogsTest` 4；连同 `MainActivityTest` 19、`BksAppStateTest` 1、`ProfileScreenTest` 5 重新运行，共 5 suites / 33 tests，0 skipped/failures/errors。
- 复审修复完成后运行 `:apps:android:testDebugUnitTest` 全量 Android JVM/Robolectric：91 suites / 525 tests，0 skipped/failures/errors。
- `:apps:android:detekt` 最终通过。首次运行准确发现 `BksApp` 显式装配长度/复杂度、文件函数数与账号入口参数数 4 项问题；将两个账号 factory 移入 `BksRouteFactories.kt`，并仅对不可聚合而不形成 locator 的显式根装配/Route 长签名添加局部 suppression 后重跑通过。
- `git diff --check` 通过；`rg` 确认生产/测试代码中 `BksRouteContext`、`BksRouteContentContext` 消费为 0，Route 文件中没有 `BksAppDependencies`、`BksAppContainer`、Repository、Scheduler、`CoroutineScope` 或 `launch`。唯一保留的直接业务边界是上述 Review `AiCategorizationGateway` 阻塞点。
- 未运行 Android Phase 全量、`assembleDebug`、Instrumentation 或真机测试：Phase 5 尚未完成，且本次未修改 Manifest、Resources、Gradle dependency、BuildConfig、Room/API/sync/backup/platform 配置。

### Remaining Risks

- Step 5.1 保持 Blocked，直到 Step 5.2 或用户另行授权的边界方案先让 `ReviewQueueScreen` 不再要求 Gateway；在此之前不能宣称所有 Route 都满足 gateway-free invariant。
- `BksApp` 仍负责 legacy runtime/presentation/actions/effects 和同步切换业务 callback 的 Composition Root 装配；本 Step 已移除 Route locator，但没有越界执行 Step 7.1 的 God Layer 最终清理。
- 现有 JVM/Robolectric 测试覆盖 Root gate、导航/返回、Profile 可达性与 owner 行为；Activity/process recreation、真实文件 picker/微信 callback、真机系统返回和完整 APK 装配尚未做设备端验收。
- 当前改动保留在未提交工作区；未 commit、push 或创建分支。

### Final Status

Blocked
