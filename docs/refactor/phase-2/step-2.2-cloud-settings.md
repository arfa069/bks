# Step 2.2 — Cloud Settings

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
