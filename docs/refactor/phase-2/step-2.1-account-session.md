# Step 2.1 — Account Session

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
