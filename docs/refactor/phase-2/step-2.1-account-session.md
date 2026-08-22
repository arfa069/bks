# Step 2.1 — Account Session

## Status

Completed

## Goal

建立唯一 `AccountSessionCoordinator` 作为跨 Feature、跨 Activity recreation 的 Session owner，并用明确的 Account Entry 导航状态替代“清空真实 Session 来打开登录页”。

## Current Problem

Session restore、verify、runtime status、deletion state 与 Account Entry 导航目前分散在 Compose Effect、Root runtime 和 route callback 中。业务生命周期依赖 Composition，且导航行为可能误改真实 Session。

## Scope

- Account Session restore、verify、offline/invalid 状态和 deletion state。
- `BksAppState` 的 Account Entry mode/return target。
- Root 对 Coordinator 稳定 State 的 lifecycle-aware collection。
- 旧 Route 的单向 runtime bridge；不在此 Step 删除全部旧 Root 类型。
- 默认不创建 `BksAppViewModel`。

## Files

- [`apps/android/src/main/java/com/bks/BksApp.kt`](../../../apps/android/src/main/java/com/bks/BksApp.kt)
- [`apps/android/src/main/java/com/bks/BksAppRuntime.kt`](../../../apps/android/src/main/java/com/bks/BksAppRuntime.kt)
- [`apps/android/src/main/java/com/bks/BksAppEffectsContext.kt`](../../../apps/android/src/main/java/com/bks/BksAppEffectsContext.kt)
- [`apps/android/src/main/java/com/bks/ui/BksAppState.kt`](../../../apps/android/src/main/java/com/bks/ui/BksAppState.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountSessionState.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountSessionState.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountSessionCommit.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountSessionCommit.kt)
- [`apps/android/src/main/java/com/bks/feature/account/SecureAccountSessionStore.kt`](../../../apps/android/src/main/java/com/bks/feature/account/SecureAccountSessionStore.kt)
- [`apps/android/src/main/java/com/bks/feature/account/LocalModeSessionStore.kt`](../../../apps/android/src/main/java/com/bks/feature/account/LocalModeSessionStore.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/account/AccountSessionCoordinator.kt` 及对应测试。

## Planned Changes

- 创建 `AccountSessionCoordinator`，合并 Session、restore、runtime status 与 deletion state 的唯一状态流。
- 保持“先安全保存、后发布稳定 Session state”的提交顺序。
- `BksAppState` 增加明确的 Account Entry mode 和 return target；进入登录/注册页不再通过清空有效 Session 实现。
- Root 只做 lifecycle forwarding 和 lifecycle-aware state collection；Session restore/verify 业务不再由 Compose Effect 执行。
- 默认不创建 `BksAppViewModel`；只有执行时发现独立 Root 启动状态、异步或错误模型满足准入条件才重新评估，并记录偏差。
- 尚未迁移的 Route 只能读取单向 legacy projection，所有 Session 写入统一委托 Coordinator。

## State / Ownership Changes

- Account Session、restore status、verification status、deletion state：Root runtime/Compose Effect → `AccountSessionCoordinator.StateFlow`。
- Account Entry navigation mode/return target：隐式 Session mutation → `BksAppState` 明确导航状态。
- 密码、验证码和其他敏感表单状态仍不进入 Session coordinator 或持久导航状态。

## Before

```text
Compose Root Effect / Route callback
→ Session Store / Account Repository
→ BksAppRuntimeState copy
→ 清空或重建 Session 驱动页面
```

## After

```text
AccountSessionCoordinator
→ stable Session StateFlow
→ Root session gate + feature consumers

BksAppState
→ Account Entry mode / return target
→ Navigation
```

## Invariants

- 登录成功、Session 切换和 Root gate 由稳定 State 驱动，不依赖可能丢失的 one-shot Effect。
- 进入 Account Entry 不清空有效 LocalMode 或已验证 Session。
- Session 状态不跨账户/token 泄漏。
- 敏感输入不进入 `SavedStateHandle`、日志或持久状态。
- 不修改认证 API、Secure Store 格式、Room schema 或业务行为。
- `AccountSessionCoordinator.start()` 必须幂等。
- 多次调用 `start()` 不得重复 restore、重复 verify、重复创建长期 Flow collector。
- Coordinator 长生命周期任务使用 Application-owned scope，不依赖 Compose scope。
- configuration/composition recreation 不得重新触发 Account runtime 初始化。

## Risks

High。错误的提交顺序可能产生“状态已发布但安全存储未成功”、账户间状态泄漏，或导航时丢失有效 Session。

## Tests

- 新增 `AccountSessionCoordinatorTest`：restore、verified、offline、invalid、commit failure 与账户切换。
- 复用 Secure/Local session store 和 `AccountSessionCommitTest`。
- `MainActivityTest` 与 `BksAppStateTest` 保护 Root gate、返回目标和已有导航行为。
- repeated start is idempotent
- recreation does not duplicate collectors/work

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountSessionCoordinatorTest" --tests "com.bks.feature.account.SecureAccountSessionStoreTest" --tests "com.bks.feature.account.LocalModeSessionStoreTest" --tests "com.bks.feature.account.AccountSessionCommitTest" --tests "com.bks.MainActivityTest" --tests "com.bks.ui.BksAppStateTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- Session 正确性仍依赖 Compose Effect 是否执行或 one-shot Effect 是否被消费。
- 进入 Account Entry 仍必须清空有效 Session。
- 安全存储失败后 Coordinator 仍发布新 Session。
- 出现跨账户/token 的旧状态泄漏。
- 继续需要修改认证 API、存储格式、Room schema 或创建不满足准入条件的 Root ViewModel。
- 专项测试失败且根因未解决。

## Completion Criteria

- `AccountSessionCoordinator` 是 Session、restore、runtime status 和 deletion state 的唯一 owner。
- Root 不再用 Compose Effect 执行 Session restore/verify 业务。
- Account Entry mode/return target 是明确导航状态，打开登录页不清空有效 LocalMode Session。
- 所有 Session 写入通过 Coordinator，legacy bridge 只读且有后续删除点。
- 专项测试、必要 Detekt 与 diff 检查通过，Execution Record 已更新。

---

## Execution Record

### Actual Changes

- 新增 Application-scoped `AccountSessionCoordinator` 与稳定 `StateFlow`，统一拥有 Session、restore、verify、offline/invalid runtime status 和 deletion state；`BksApplication` 启动生产 Coordinator，`start()` 通过原子门控保证重复调用只执行一次 restore/verify。
- Coordinator 保持“安全存储成功后再发布 Session”顺序；刷新资料持久化失败时保留旧 Session 并进入 `OfflineUnverified`。所有 Session/deletion/持久化清理转换均推进 generation，迟到的 verified/offline/invalid 结果只有在 generation 未变化时才可应用，invalid 的确认、存储清理和 LocalMode 发布位于同一串行化边界。
- `BksApp` 使用 `collectAsStateWithLifecycle()` 收集 Coordinator 稳定状态，Root gate 直接读取该状态；旧 `BksAppRuntimeState` 只保留单向 projection，Compose 中原有 restore/verify 业务 Effect 已删除，计划在 Step 7.1 随 legacy God Layer 一并删除 projection。
- `BksAppState` 新增可恢复的 `AccountEntryMode` 与 `AccountEntryReturnTarget`。从账户管理打开登录/注册入口时保留真实 LocalMode/SignedIn Session，仅改变导航状态；返回时恢复账户管理层级。
- Account Entry、账户管理、账号切换对话框的 Session 持久化、切换本地模式、invalid、deletion state 写入均委托 Coordinator；旧 `BksAppActions` 中的 Session 写方法及 account dependencies 中的 Store 写入口已移除。
- 新增 `AccountSessionCoordinatorTest`，并扩展 `BksAppStateTest`、`MainActivityTest`、`BksAppContainerTest` 与测试 Repository，覆盖 restore/verified/offline/invalid、持久化失败、账户切换、同账户同 token 的迟到 verified/invalid、幂等启动、Application singleton、导航状态恢复及打开 Account Entry 不清空有效 LocalMode Session。

### Deviations From Plan

- 未创建 `BksAppViewModel`：没有发现独立 Root 状态模型满足准入条件，Application-scoped Coordinator 已直接提供所需稳定状态。
- `AccountSessionState.kt`、`AccountSessionCommit.kt`、`SecureAccountSessionStore.kt` 与 `LocalModeSessionStore.kt` 无需修改；当前类型、提交辅助函数和存储格式已满足本 Step，强行调整会扩大范围。
- 为建立真正跨 Activity recreation 的 Application owner，实际最小修改了 Plan 文件清单之外的 `BksApplication.kt`、`BksAppContainer.kt`、`BksAppDependencies.kt`、`BksRouteHost.kt`、`BksProfileRoutes.kt`、`BksDialogs.kt` 及对应测试；未进入 Cloud Settings 或 Sync Runtime 后续 Step。
- 未新增 Lifecycle/Coroutines dependency：现有 `debugCompileClasspath` 已包含 `lifecycle-runtime-compose`，可直接使用 lifecycle-aware collection。
- 生产 Coordinator 在 `BksApplication.onCreate()` 启动；带 account test override 的 Coordinator 由 container overlay 单独创建并幂等启动，以继续支持现有 Compose 测试，不改变生产 singleton 边界。

### Test Results

- Step 专项命令（`AccountSessionCoordinatorTest`、Secure/Local Store、`AccountSessionCommitTest`、`MainActivityTest`、`BksAppStateTest`）：通过，0 failures。
- `:apps:android:detekt`：通过，0 issues。
- `:apps:android:testDebugUnitTest`：通过，76 suites、417 tests、0 failures、0 errors、0 skipped。
- `git diff --check`：通过。
- 首轮新增 Coordinator 测试因测试任务误挂到 `backgroundScope`，6 个用例在初始状态断言失败；改为由当前 `TestScope` 持有任务后，同一窄集、Step 专项命令与最终全量均通过。生产代码未因该测试接线问题回退或放宽断言。

### Remaining Risks

- `BksAppRuntimeState` 仍承载尚未迁移 feature 的只读 Session projection；Root gate 与 Session 正确性已不依赖该 bridge，但 bridge 本身需按 Step 7.1 删除。
- 带 account override 的测试 Coordinator 是按 composition 创建的 overlay，不是跨 Activity owner；生产路径始终使用 `BksApplication` 持有的唯一 Coordinator，相关 singleton 与重复 `start()` 已由测试覆盖。
- 未运行 instrumentation、真机、真实后端或 `assembleDebug`：本 Step 未修改 Manifest、Resource、Gradle dependency、认证 API、存储格式、Room schema 或可见 UI 契约，Robolectric 导航测试与 Android JVM 全量测试覆盖本次边界变更。

### Final Status

Completed
