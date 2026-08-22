# Step 4.2 — Account Management

## Status

Pending

## Goal

将账号验证、退出、注销、Profile、微信绑定/解绑/合并和头像上传等复杂异步业务迁入 `AccountManagementViewModel`，统一向 Account Session owner 提交结果。

## Current Problem

`AccountManagementScreen` 与多个 WeChat/Profile 子 Composable 直接执行 Account Repository 操作。operation state、取消、ActivityResult、敏感 Dialog 和 Session/Profile 更新混在 UI 中，容易产生状态分叉或残留临时票据。

## Scope

- verify、sign out、request/cancel deletion、Profile 更新。
- 微信绑定、解绑、合并与头像上传的业务操作。
- Repository operation、取消和结果提交。
- 相机/相册 launcher、URI 获取、敏感 Dialog 草稿继续留在 Screen。
- 保留已有有真实价值的 WeChat 边界组件。

## Files

- [`apps/android/src/main/java/com/bks/feature/account/AccountManagementScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountManagementScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountDeletionState.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountDeletionState.kt)
- [`apps/android/src/main/java/com/bks/feature/account/WechatAccountManagement.kt`](../../../apps/android/src/main/java/com/bks/feature/account/WechatAccountManagement.kt)
- [`apps/android/src/main/java/com/bks/feature/account/WechatAccountAvatarEditor.kt`](../../../apps/android/src/main/java/com/bks/feature/account/WechatAccountAvatarEditor.kt)
- [`apps/android/src/main/java/com/bks/feature/account/WechatAuthCoordinator.kt`](../../../apps/android/src/main/java/com/bks/feature/account/WechatAuthCoordinator.kt)
- [`apps/android/src/main/java/com/bks/BksProfileRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksProfileRoutes.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/account/AccountManagementViewModel.kt` 及对应测试。

## Planned Changes

- 创建 `AccountManagementViewModel`；验证、退出、注销、Profile、微信和头像的状态/操作数量满足独立 owner 准入条件。
- 将 Repository 调用、业务 coroutine、取消与提交迁入 ViewModel。
- 相机/相册 launcher 只把 URI 交给 ViewModel；平台 launcher 本身保留在 Screen。
- Session/Profile/deletion 结果统一提交 Step 2.1 的 `AccountSessionCoordinator`。
- 保留 `WechatAuthCoordinator`、`WechatLoginController` 等已有且经过测试的纯/平台边界组件，不机械包裹或重写。
- 所有成功、取消和失败路径按当前行为清理敏感输入和临时票据。

## State / Ownership Changes

- Account management operation/loading/error：Screen/sub-Composable → `AccountManagementViewModel`。
- Session/Profile/deletion stable state：继续由 `AccountSessionCoordinator` 唯一发布。
- Camera/gallery launcher、Dialog 草稿和敏感输入：继续由 Screen/Composition 持有。

## Before

```text
Screen / WeChat sub-Composable
→ AccountRepository
→ callbacks
→ Root account fields / local operation state
```

## After

```text
Screen Action / platform URI
→ AccountManagementViewModel
→ Repository + existing WeChat components
→ AccountSessionCoordinator
→ stable StateFlow + feature-specific UI feedback
```

## Invariants

- Session/Profile/deletion state 不产生多个 owner。
- 敏感输入与临时票据在成功、取消和适用失败路径清理，不进入持久 state 或日志。
- 相机/相册 launcher 与 `FLAG_SECURE` 等平台职责不移入 ViewModel。
- 认证/账户 API 和业务行为不变。
- 不重写已有有价值的 WeChat coordinator/controller。

## Risks

High。操作顺序或状态提交错误可能导致注销/退出状态不一致、Profile 字段丢失、微信票据泄漏或重复上传。

## Tests

- 新增 `AccountManagementViewModelTest`：verify、sign out、deletion、Profile、WeChat、avatar 的成功/失败/取消。
- 复用 Account Management UI、Deletion State、WeChat UI/avatar 和 HTTP Repository 测试。
- 覆盖 Session/Profile/deletion 统一提交和临时票据清理。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountManagementViewModelTest" --tests "com.bks.feature.account.AccountManagementScreenTest" --tests "com.bks.feature.account.AccountWechatUiTest" --tests "com.bks.feature.account.AccountDeletionStateTest" --tests "com.bks.feature.account.WechatAvatarTest" --tests "com.bks.feature.account.HttpAccountRepositoryTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- Account UI 仍直接调用 Repository 或运行业务 coroutine。
- Session/Profile/deletion 结果出现多个写入 owner 或覆盖无关字段。
- 敏感输入/票据无法在成功或取消路径安全清理。
- 平台 launcher 被错误移入 ViewModel，或 lifecycle 不再可取消。
- 需要修改账户 API、存储格式、业务行为或创建额外无状态 wrapper。
- 专项测试失败且根因未解决。

## Completion Criteria

- `AccountManagementViewModel` 拥有账号管理 operation state 与复杂异步 action。
- Account Management UI 不直接访问 Repository。
- Session/Profile/deletion 结果统一进入 `AccountSessionCoordinator`。
- launcher/Dialog/敏感草稿仍属于 Screen，临时票据按路径清理。
- 既有 WeChat 边界组件保留且专项测试、必要 Detekt、diff 检查通过。

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
