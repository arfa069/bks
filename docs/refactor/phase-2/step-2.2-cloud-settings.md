# Step 2.2 — Cloud Settings

## Status

Completed

## Goal

建立 `AccountCloudSettingsCoordinator`，统一 token-scoped AI settings 与账户级默认资金账户云配置，消除 Root Cloud Settings Effect 和跨账户状态泄漏风险。

## Current Problem

Cloud AI settings、默认资金账户本地/云端同步和 token 生命周期目前由 Root Effect 与多个 feature 路径编排。账户切换时旧请求或旧设置可能继续生效，本地成功/云失败语义也缺少单一 owner。

## Scope

- AI settings 的加载、保存和 token scope。
- 默认资金账户本地优先、云端 pending upload 语义。
- 注销冷静期与 `OfflineUnverified` 的 cloud write gate。
- Root Cloud Settings Effect 的迁出与只读 legacy bridge。
- 不修改 cloud API 或本地偏好数据格式。

## Files

- [`apps/android/src/main/java/com/bks/BksAppEffectsContext.kt`](../../../apps/android/src/main/java/com/bks/BksAppEffectsContext.kt)
- [`apps/android/src/main/java/com/bks/BksAppRuntime.kt`](../../../apps/android/src/main/java/com/bks/BksAppRuntime.kt)
- [`apps/android/src/main/java/com/bks/feature/categorization/HttpCloudAiSettingsGateway.kt`](../../../apps/android/src/main/java/com/bks/feature/categorization/HttpCloudAiSettingsGateway.kt)
- [`apps/android/src/main/java/com/bks/data/local/LocalPreferencesRepository.kt`](../../../apps/android/src/main/java/com/bks/data/local/LocalPreferencesRepository.kt)
- [`apps/android/src/main/java/com/bks/feature/account/AccountSessionState.kt`](../../../apps/android/src/main/java/com/bks/feature/account/AccountSessionState.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/account/AccountCloudSettingsCoordinator.kt` 及对应测试。

## Planned Changes

- 创建 `AccountCloudSettingsCoordinator`，以 Step 2.1 的稳定 Session/token state 为输入。
- 账户切换后立即使旧 token 的加载、保存和回调结果失效。
- 默认资金账户先完成本地持久化；云端失败保留本地结果并记录既有 pending upload 语义。
- 注销冷静期和 `OfflineUnverified` 状态禁止 cloud write。
- 删除 Root Cloud Settings business Effect，尚未迁移消费者只读取单向 projection。
- 不创建额外 Settings Manager、UseCase 或无状态 wrapper。

## State / Ownership Changes

- AI settings 与默认资金账户云同步状态：Root runtime/Effect → `AccountCloudSettingsCoordinator`。
- 本地默认资金账户的 source of truth 仍是现有持久层。
- Coordinator 只持有 token-scoped operation state，不复制 Room/Preferences 为第二真相。

## Before

```text
Root Compose Effect + feature callback
→ Account token / Cloud gateway / Preferences
→ BksAppRuntimeState settings copy
```

## After

```text
AccountSessionCoordinator.State
→ AccountCloudSettingsCoordinator
   ├─ LocalPreferencesRepository
   └─ Cloud settings gateway
→ token-scoped stable state / pending result
```

## Invariants

- 云设置不得跨 token 或账户泄漏。
- 默认资金账户保持“本地成功优先；云失败不回滚本地”的现有语义。
- 注销冷静期与 `OfflineUnverified` 不执行 cloud write。
- 不修改 API、sync protocol、Room schema 或本地偏好格式。
- 持续状态使用稳定 State；临时反馈的 Effect 技术由实际 feature 语义决定。

## Risks

High。旧 token 响应可能覆盖新账户状态，错误的失败处理可能回滚已成功的本地设置，或在无权限状态执行云写入。

## Tests

- 新增 `AccountCloudSettingsCoordinatorTest`：token 切换、stale response、本地成功/云失败、offline/deletion gate。
- 复用 `HttpCloudAiSettingsGatewayTest` 验证现有网关契约。
- 复用 `LocalPreferencesRepositoryTest` 验证本地 source of truth 与 pending 语义。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountCloudSettingsCoordinatorTest" --tests "com.bks.feature.categorization.HttpCloudAiSettingsGatewayTest" --tests "com.bks.data.local.LocalPreferencesRepositoryTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- 旧 token 的完成结果仍能覆盖当前账户状态。
- 云失败会撤销已成功的本地默认资金账户写入。
- 注销冷静期或 `OfflineUnverified` 仍可触发 cloud write。
- Coordinator 只能通过复制持久数据为第二 source of truth 才能工作。
- 需要修改 cloud API、偏好格式、Room schema 或创建无价值 wrapper。
- 专项测试失败且根因未解决。

## Completion Criteria

- Cloud AI settings 与默认资金账户云配置由单一 token-scoped Coordinator 编排。
- Root Cloud Settings business Effect 已删除，legacy projection 只读。
- 账户切换会使旧 token 工作立即失效。
- 本地成功/云失败、offline 和 deletion gate 语义保持不变。
- 专项测试、必要 Detekt 与 diff 检查通过，Execution Record 已更新。

---

## Execution Record

### Actual Changes

- 新增 Application-scoped `AccountCloudSettingsCoordinator`，直接收集 Step 2.1 的 `AccountSessionCoordinator.StateFlow`，以 generation + token/account key scope 统一拥有 Cloud AI settings 加载/保存、同步中状态和默认资金账户 pending upload 编排；`start()` 使用原子门控保证幂等。
- Session/token、`OfflineUnverified` 或注销冷静期变化会立即重置 Coordinator 稳定状态并使旧 generation 失效；AI settings 本地提交在最终发布点再次核对 operation scope，失效提交会恢复原本地值。Cloud operation 通过单一 `Mutex` 串行，取消路径通过 `finally` 释放同步中状态，避免 Activity 重建后永久阻断后续写入。
- 默认资金账户现在由 Coordinator 调用既有 `FundingAccountRepository` 先完成本地事务，并在独立的短事务锁内按账户 key 写入 pending cache；云操作继续串行，但不会把本地写入排在网络请求之后。generation gate 会阻止较早的 initial load 覆盖新本地选择；云失败或调用方取消会保留本地默认值和 `pendingUpload=true`，后续 verified Session 启动会重试并在成功后清除 pending。
- `BksAppContainer` 持有唯一生产 Coordinator，`BksApplication` 启动它；测试 override 使用与 override Session/Gateway 匹配的 Coordinator。`BksApp` lifecycle-aware 收集稳定 State，并只向尚未迁移的 Root runtime 写入单向 legacy projection。
- 删除 `BksCloudSettingsEffects` 和 Root/Route 对 `CloudAiSettingsGateway` 的直接业务调用；`BksAppActions.persistAiSettings`、默认资金账户回调和本地数据清理只委托 Coordinator，既有用户反馈文案保持不变。
- 新增 `AccountCloudSettingsCoordinatorTest`，扩展 `BksAppContainerTest`，覆盖幂等启动、token 切换、迟到写响应、取消后 in-flight 清理、慢 initial load 期间的本地优先写入、本地成功/云失败、pending 重试、offline/deletion gate 和生产 singleton/override owner。

### Deviations From Plan

- 当前代码显示默认资金账户的本地 source of truth 实际位于 `FundingAccountRepository`/`LocalLedgerRepository`，不是 `LocalPreferencesRepository`；因此 Coordinator 同时使用前者执行本地优先写入、后者维护既有账户隔离 cache/pending 语义，没有复制持久数据为第二真相。
- 为建立真正的 Application owner 并迁移实际消费者，最小修改了 Plan 文件清单外的 `BksApplication.kt`、`BksAppContainer.kt`、`BksAppDependencies.kt`、`BksApp.kt`、`BksLedgerRoutes.kt` 和对应 Container 测试；未进入 Sync Runtime 或后续 Step。
- `HttpCloudAiSettingsGateway.kt`、`LocalPreferencesRepository.kt` 与 `AccountSessionState.kt` 无需修改：当前 API、缓存格式、account key 与 gate 已满足本 Step，强行调整会扩大范围。
- 未新增 Settings Manager、UseCase、wrapper、dependency、Effect Channel 或 SharedFlow；现有同步用户反馈继续由调用方 snackbar callback 呈现。

### Test Results

- Step 专项命令（`AccountCloudSettingsCoordinatorTest`、`HttpCloudAiSettingsGatewayTest`、`LocalPreferencesRepositoryTest`）：通过；三类共 22 tests、0 failures。另含 `BksAppContainerTest` 的同轮 focused 验证通过，合计 25 tests、0 failures。
- `:apps:android:detekt`：通过，0 issues。首次运行仅报告 Coordinator 辅助函数数达到阈值；内联一个无价值只读 helper 后通过，未添加 suppression 或新 wrapper。
- `:apps:android:testDebugUnitTest --max-workers=1`：通过，77 suites、424 tests、0 failures、0 errors、0 skipped。
- 前两次额外全量尝试各出现一个不同的非稳定既有用例失败：`MainActivityTest.reportsFollowTheSelectedLedgerBook` 与 `SecureAccountSessionStoreTest.corruptedCiphertextIsClearedAndDowngradesSafely`；两者分别单独复跑通过，最终完整串行运行全绿，未修改或放宽这些无关测试。
- `git diff --check`：通过。

### Remaining Risks

- `BksAppRuntimeState` 仍保留 AI settings、loaded token 和 in-flight 的只读 projection，供尚未迁移的 Route/Screen 使用；Cloud settings 正确性与写入已不依赖该 bridge，projection 按 Step 7.1 删除。
- 无 `accountUuid` 且无 `accountId` 的 legacy SignedIn Session 仍无法建立账户隔离的默认资金账户 cache key；这是既有 `cloudConfigAccountKey()` 边界，本 Step 未改变认证契约或扩大 fallback。
- 未运行 instrumentation、真机、真实后端或 `assembleDebug`：本 Step 未修改 Manifest、Resource、Gradle dependency、Cloud API、Room schema 或偏好格式；真实后端取消/响应时序仍需后续集成验收覆盖。

### Final Status

Completed
