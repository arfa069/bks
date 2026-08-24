# Step 4.1 — Account Entry

## Status

Completed

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

- 新增 `AccountEntryViewModel`，由其 `viewModelScope` 和 `StateFlow` 统一持有普通认证、短信倒计时、微信授权/绑定的 loading、错误和 operation state；Snackbar 临时反馈通过 feature-local `Channel` 暴露。
- 普通登录、注册、找回密码和微信认证成功均只调用 Route 注入的 `AccountSessionCoordinator.commit`；本地模式只调用 `AccountSessionCoordinator.enterLocalMode`。认证成功后不再向 Screen/Route 发送成功 callback。
- `AccountScreen` 改为只持有普通账号表单草稿、运行 reducer、转发 action 和渲染 ViewModel state；不再接收 `AccountRepository`、持久化 callback 或启动认证业务 coroutine。
- `WechatLoginController` 改为无内部 Compose state 的认证边界，`AccountEntryViewModel` 成为微信异步状态 owner；微信绑定的标识、密码和验证码草稿改由 `WechatLoginScreens` 内的 Compose state 持有。
- `BksAccountEntry` 负责创建 ViewModel、装配 Compliance 独立内容并观察 `AccountSessionCoordinator.state`；首次登录依靠根 session gate，LocalMode 上的登录覆盖页依靠稳定 `SignedIn` state 关闭。
- 新增 `AccountEntryViewModelTest` 与 Screen 测试 harness，扩充 fake Repository 的挂起请求 seam，覆盖成功、失败、取消、短信倒计时、普通/微信重复提交、Session 唯一提交入口及敏感草稿不进入 ViewModel state。
- 复审后为 Activity-scoped `AccountEntryViewModel` 增加显式 Account Entry generation/关闭边界：账户管理返回、稳定 `SignedIn` 导航和进入本地模式都会取消在途任务、清空瞬态状态并在 Session commit 前拒绝迟到结果；未使用 Composition dispose 清理，因此 configuration recreation 仍保留合法的 ViewModel operation。
- 短信请求增加 operation 与 SMS-context generation；手机号或 Register/Recovery/Landing flow 改变时取消并作废挂起请求、恢复 idle，忽略 coroutine cancellation 的旧响应也不能在新上下文启动倒计时或发布错误。
- 显式关闭 Account Entry 时按 purpose 作废持久化的微信 `SignInOrRegister` state/relay；合法的进程冷启动 callback 仍可由新 ViewModel 继续处理，且不会误清 Account Management 的 `LinkCurrentAccount` 授权上下文。

### Deviations From Plan

- 当前基线已经存在拆分后的 `AccountAuthContent.kt`、`AccountAuthOperations.kt`、`WechatLoginScreens.kt`，因此没有为计划形态重复创建内容类，只调整实际 ownership 和调用边界。
- 保留 `WechatLoginController` 作为可测试的无状态 Repository/微信协议操作边界，而不是把全部分支机械复制进 ViewModel；所有可观察异步状态仍只由 `AccountEntryViewModel` 持有。
- `AccountUiState` 继续作为现有表单渲染模型复用；其中 operation/countdown 值只由 Screen 从 ViewModel state 投影，Compose 保存的 `formState` 不再更新这些字段，也不再包含 Session。

### Test Results

- 重构前专项基线：Step 列出的既有 6 个 Account 测试类及 `MainActivityTest`，`BUILD SUCCESSFUL`。
- 重构及复审修复后专项验证：新增 `AccountEntryViewModelTest` 加 Step 列出的全部测试类，`BUILD SUCCESSFUL`；覆盖关闭入口后的非协作取消认证、挂起短信上下文切换、微信瞬态清理、purpose-bound 授权作废与 cold-start callback。
- `./gradlew.bat :apps:android:testDebugUnitTest`：通过；最终 XML 汇总为 85 个 suite、468 tests、0 failures、0 errors、0 skipped。
- `./gradlew.bat :apps:android:detekt`：通过。
- `git diff --check`：通过。
- `android_code_reviewer` 最终只读复审：原 P1/P2 与中途发现的微信 cold-start 回归均闭合，无新增 P1/P2。
- 未运行 `assembleDebug`：本 Step 未修改 Manifest、Resources、Gradle dependency、BuildConfig 或 Android platform configuration，按总计划无需 APK 验证。

### Remaining Risks

- 未进行真实后端账号、短信或微信 OpenSDK/回调的真机验收；本 Step 仅以 fake Repository/Gateway、Robolectric UI 和既有 HTTP/微信边界测试证明行为保持。
- ViewModel 中的微信 ticket 只存在于非持久 `StateFlow`，进程死亡后会要求用户重新授权；这与本 Step 禁止持久化临时票据的安全边界一致。

### Final Status

Completed（2026-08-24）
