# Step 4.1 — Account Entry

## Status

Pending

## Goal

将认证、短信、微信登录和多异步 operation state 从 Account Screen 移入 `AccountEntryViewModel`，并由稳定 Account Session state 驱动认证成功后的导航。

## Current Problem

Account Entry UI 当前直接组合 Repository/Gateway、登录控制器和 Root callback。认证成功、错误、loading 与导航分散在 Screen/Route，且若成功依赖 one-shot Effect，观察者切换时可能停留在旧页面。

## Scope

- 登录、注册、短信验证码、微信登录及其 loading/error/operation state。
- 认证结果向 `AccountSessionCoordinator` 的唯一提交入口。
- 登录成功后的稳定 State 导航。
- Snackbar 等临时 UI 反馈的 feature-specific Effect/callback。
- 密码、验证码和表单草稿继续留在 Compose。

## Files

- [`apps/android/src/main/java/com/bks/feature/account/AccountScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountAuthOperations.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountAuthOperations.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountState.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountState.kt)
- [`apps/android/src/main/java/com/bks/feature/account/WechatLoginFlow.kt`](../../../apps/android/src/main/java/com/bks/feature/account/WechatLoginFlow.kt)
- [`apps/android/src/main/java/com/bks/feature/account/WechatAuthCoordinator.kt`](../../../apps/android/src/main/java/com/bks/feature/account/WechatAuthCoordinator.kt)
- [`apps/android/src/main/java/com/bks/BksRouteHost.kt`](../../../apps/android/src/main/java/com/bks/BksRouteHost.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/account/AccountEntryViewModel.kt` 及对应测试。

## Planned Changes

- 创建 `AccountEntryViewModel`；认证、短信、微信和多个异步状态满足独立 owner 准入条件。
- Screen 只提交 action，不直接持有 Account Repository/Gateway 或业务 coroutine。
- 认证成功统一提交 `AccountSessionCoordinator`，由其稳定 `State` 驱动 Root session gate 与导航。
- Snackbar、系统 Intent 等临时反馈按实际语义选择 Channel、SharedFlow 或 callback，不强制统一技术。
- 密码、验证码和表单草稿留在 Compose，不进入 `SavedStateHandle`、日志、ViewModel 持久状态或 Execution Record。
- Account Screen 不再直接组合 Compliance Screen；由 Route 装配独立目的地。

## State / Ownership Changes

- 认证 loading/error/operation state：Screen/Route → `AccountEntryViewModel`。
- 已认证 Session：继续由 `AccountSessionCoordinator` 唯一持有。
- 密码、验证码和表单草稿：继续由 Screen/Composition 持有。
- 登录成功导航：one-shot callback/effect → 稳定 Session state。

## Before

```text
Account Screen callbacks
→ Repository / Gateway / login controller
→ Root fields / one-shot navigation
```

## After

```text
Screen Action
→ AccountEntryViewModel
→ Repository / WeChat boundary
→ AccountSessionCoordinator.State
→ stable session gate / Navigation
```

## Invariants

- 登录成功、Session 切换和关键导航由稳定 State 驱动。
- 密码、验证码和临时票据不进入 `SavedStateHandle`、日志或持久状态。
- 认证 API、Secure Store 格式、账号行为和 Compliance 内容不变。
- Screen 不直接访问 Repository/Gateway。
- 临时 Effect 技术按语义选择，不机械固定为 Channel。

## Risks

High。错误的认证提交或导航信号可能产生已登录但停留旧页、重复提交，或泄漏敏感输入。

## Tests

- 新增 `AccountEntryViewModelTest`：各认证路径的成功、失败、取消、重复提交和 operation state。
- 复用 Account Screen/State、WeChat controller/coordinator/UI 和 `MainActivityTest`。
- 明确证明登录成功不依赖一次性 Effect，敏感输入不进入持久 state。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountEntryViewModelTest" --tests "com.bks.feature.account.AccountScreenTest" --tests "com.bks.feature.account.AccountStateTest" --tests "com.bks.feature.account.WechatLoginControllerTest" --tests "com.bks.feature.account.WechatAuthCoordinatorTest" --tests "com.bks.feature.account.AccountWechatUiTest" --tests "com.bks.MainActivityTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- 登录成功仍依赖可能丢失的 one-shot Effect 才能离开 Account Entry。
- 密码、验证码或临时票据进入 `SavedStateHandle`、日志或持久 state。
- Screen 仍直接访问 Repository/Gateway 或运行认证业务 coroutine。
- 认证结果存在多个 Session 提交入口。
- 需要修改认证 API、存储格式、业务行为或依赖版本。
- 专项测试失败且根因未解决。

## Completion Criteria

- `AccountEntryViewModel` 拥有认证 operation state 和多个异步 action。
- Screen 只处理渲染、表单草稿与 action，不持有 Repository/Gateway。
- 认证成功只提交 `AccountSessionCoordinator`，导航由稳定 Session State 驱动。
- 敏感输入未进入可恢复/持久 state。
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
