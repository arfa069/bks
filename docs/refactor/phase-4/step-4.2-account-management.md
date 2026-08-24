# Step 4.2 — Account Management

## Status

Completed

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
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountSessionCoordinatorTest" --tests "com.bks.feature.account.AccountManagementViewModelTest" --tests "com.bks.feature.account.AccountManagementScreenTest" --tests "com.bks.feature.account.AccountWechatUiTest" --tests "com.bks.feature.account.AccountDeletionStateTest" --tests "com.bks.feature.account.WechatAvatarTest" --tests "com.bks.feature.account.HttpAccountRepositoryTest" --tests "com.bks.MainActivityTest"
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

- 新增 `AccountManagementViewModel`，由其 `viewModelScope`、generation guard 和 `StateFlow` 统一持有账号重新验证、退出、注销、Profile、微信绑定/解绑/合并及头像上传的异步 operation/loading/error 状态；关闭页面、Session 切换和输入上下文变化会取消并作废在途结果。
- 账号资料、微信、头像和合并成功只调用 Route 注入的 `AccountSessionCoordinator.commitManagementCredentials`；Coordinator 在同一锁边界校验预期 Session token、合并当前权威注销状态、先持久化再发布，拒绝迟到旧 Session 结果覆盖新 Session 或注销冷静期。重新验证单独调用 `commitVerifiedCredentials`，以服务器返回的注销状态为权威，可正确进入或退出 cooling-off。
- 退出和 Invalid Session 统一调用 Coordinator 的原子 `invalidateSession`：先确认 LocalMode 标记持久化，再清安全存储和头像缓存，最后发布 `LocalMode`；任一步失败均不宣称成功。新凭据持久化失败时先同步完成本地失效，再 best-effort 撤销远端 token，页面关闭只能取消后者，不能留下“磁盘已清、内存仍 SignedIn”的半提交状态。
- 申请/取消注销通过 Coordinator 重建完整 Credentials 并持久化后发布；持久化失败保留原内存/磁盘状态并给出稳定错误，离线重启仍能恢复已提交的冷静期状态。
- `AccountManagementScreen` 改为接收 Screen state、ViewModel dependencies 和 navigation actions，只负责渲染、注销确认 Dialog 与 action 转发；`WechatAccountManagementPanel` 只保留敏感 Dialog 草稿、相机/相册 launcher、临时 URI 和微信 callback 消费装配。
- `WechatAccountIdentityDialogOperations` 收敛为 UI action 到 ViewModel 的薄分发边界；昵称、标识验证码、补绑/换绑、密码合并、微信解绑等 Repository coroutine 全部迁出 Compose。
- 头像 launcher 继续由 `WechatAccountAvatarEditor` 持有；URI 的 Android 解码/压缩通过可测试的平台读取函数在 Compose scope 执行，得到受限 data URL 后交给 ViewModel 完成 Repository 上传、Session commit 和取消/失败状态清理；`WechatAvatarCache` 只由 Coordinator 的凭据接受/本地失效回调更新，移除 ViewModel 的重复缓存 I/O 与第二 owner。
- 保留 `WechatAuthCoordinator`、微信 callback fingerprint 校验、`WechatAvatarCache`、现有 Profile/Identity Dialog 和头像 UI 边界，没有重写协议组件或修改 API/schema/权限。
- `BksProfileRoutes` 创建并装配 Activity-scoped `AccountManagementViewModel`；页面退出会显式清理操作，且只有本次账户管理访问从 `SignedIn` 稳定转换到本地模式时才离开页面，初始本地模式仍可进入账号管理。
- 新增 `AccountManagementViewModelTest` 和 Account Management 测试 harness；扩充 Repository 非协作取消 seam，并将头像真实解码与 launcher→上传接线拆成独立、确定性的回归测试。
- 账号操作与身份/Profile/微信/头像操作共享互斥准入，UI 在任一 lane 忙碌时禁用另一 lane；Coordinator 的 token/deletion 合并继续作为不依赖 UI 的最终事务防线。

### Deviations From Plan

- Plan 写作“相机/相册 launcher 只把 URI 交给 ViewModel”；当前头像解码依赖 Android `Context`、`ImageDecoder` 和临时 URI 清理。为保持 ViewModel 不持有平台对象且不创建单用途无状态 wrapper，launcher/URI 读取和压缩继续留在 Screen 的可取消平台 coroutine，ViewModel 接收压缩后的 data URL 并独占上传及 Session 提交。真实解码与 UI 接线分别有测试覆盖。
- 当前基线已经存在拆分后的 `WechatAccountProfileContent`、Avatar Editor、Identity Dialog Host/Operations 等有价值边界，因此没有按计划重新创建或机械包裹这些组件，只迁移其 Repository/coroutine ownership。
- Account Management 的初始本地模式是现有可达行为；Route 不能看到 `LocalMode` 就退出。最终使用“本次访问是否见过 SignedIn”区分初始本地模式与退出/失效后的稳定 Session 转换。

### Test Results

- 重构前专项基线：Step 列出的 5 个既有 Account/WeChat/Avatar/HTTP 测试类（当时尚无 ViewModel 测试）通过，`BUILD SUCCESSFUL`。
- Step 指定的最终专项验证：`AccountSessionCoordinatorTest`、`AccountManagementViewModelTest`、`AccountManagementScreenTest`、`AccountWechatUiTest`、`AccountDeletionStateTest`、`WechatAvatarTest`、`HttpAccountRepositoryTest`、`MainActivityTest` 全部通过，`BUILD SUCCESSFUL`。
- 新增回归覆盖：本地失效先于可取消远端 revoke、account/identity 双向 lane 互斥、迟到凭据不覆盖注销状态、重新验证采用服务器权威注销状态、注销状态加密持久化/离线恢复/取消恢复、Session/LocalMode 持久化失败不发布，以及缓存回调单次执行。
- `./gradlew.bat :apps:android:testDebugUnitTest`：通过；最终 XML 汇总为 86 个 suite、487 tests、0 failures、0 errors、0 skipped。
- `./gradlew.bat :apps:android:detekt`：通过。
- `git diff --check`：通过。
- 中间完整套件曾暴露初始 `LocalMode` 被 Route 误判为退出后的 2 个 Account 返回层级回归，修复后对应 `MainActivityTest` 通过；同轮一次无关 Ledger flow 时序断言失败，单独复跑及最终完整套件均通过，未修改 Ledger scope。
- 未运行 `assembleDebug`：本 Step 未修改 Manifest、Resources、Gradle dependency、BuildConfig 或 Android platform configuration，按总计划无需 APK 验证。

### Remaining Risks

- 未执行真实后端账号、短信、微信 OpenSDK callback 或相机/相册真机验收；本 Step 以 fake Repository/Gateway、Robolectric launcher、真实 `ImageDecoder` 边界和既有 HTTP Repository 测试证明行为保持。
- 微信 merge/link ticket 只存在于非持久 ViewModel state，并在取消、关闭、上下文切换和成功路径清理；进程死亡后需要重新授权/获取验证码，这是禁止持久化临时票据的预期安全取舍。
- 账号管理 ViewModel 为 Activity-scoped，依赖 Route 的显式关闭和 generation guard 拒绝迟到结果；相关非协作取消与返回层级已有自动化覆盖，但尚未做 Activity recreation/真实微信外跳的设备级观察。
- 远端注销/新 token revoke 在本地失效之后属于 best-effort；网络失败或页面关闭可能使服务端 token 等待自身过期，但本机不会继续发布或恢复该 Session。

### Final Status

Completed（2026-08-24）
