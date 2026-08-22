# Step 5.2 — Screen Boundaries

## Status

Pending

## Goal

清除 Screen 的 Room Entity、Repository/Gateway 依赖和跨 feature 偶然依赖，使 Screen contract 收敛为 feature UI model、UiState、Action 与必要平台 callback。

## Current Problem

部分 Screen 直接接收 `CategoryEntity`、`FundingAccountEntity`、`AccountSyncConflictEntity` 等持久层类型；Review/Ledger 共用表单导致 feature 反向依赖，Account、Review、Categorization 之间还存在显示常量或 UI 组合造成的循环。

## Scope

- Screen contract 的 Entity → UI model 替换。
- `*Screen.kt` 中 Repository/Gateway/DAO import 清理。
- Review/Ledger 真正共享 entry form UI 的最小共享包评估。
- `review→billsync`、`account→categorization/compliance` 等残余 feature edge。
- 不按文件行数机械拆分职责单一的 Compose 文件。

## Files

- [`apps/android/src/main/java/com/bks/feature/ledger/`](../../../apps/android/src/main/java/com/bks/feature/ledger/)
- [`apps/android/src/main/java/com/bks/feature/review/`](../../../apps/android/src/main/java/com/bks/feature/review/)
- [`apps/android/src/main/java/com/bks/feature/account/`](../../../apps/android/src/main/java/com/bks/feature/account/)
- [`apps/android/src/main/java/com/bks/feature/categorization/`](../../../apps/android/src/main/java/com/bks/feature/categorization/)
- [`apps/android/src/main/java/com/bks/feature/settings/`](../../../apps/android/src/main/java/com/bks/feature/settings/)
- 候选新增：`apps/android/src/main/java/com/bks/ui/entry/` 下的共享 UI model/components，仅在 Ledger 与 Review 都真正消费时创建。

## Planned Changes

- 由对应 feature owner 将 Room Entity 映射为 UI model，Screen 不接收持久层实体。
- 清除所有 `*Screen.kt` 对 Repository、Gateway 和 DAO 的直接访问。
- Profile 使用自己的 account summary UI model；Settings 使用自身 backup/sync UI model。
- 只有 Ledger 与 Review 确实共享的 entry form UI model/components 才移入 `ui.entry`；单一消费者内容保留在所属 feature。
- 移除 `review→billsync` 显示常量、`account→categorization/compliance` 等偶然依赖，不建立新的通用 wrapper。
- 只按变化原因与职责拆 UI 文件，不按行数拆分。

## State / Ownership Changes

不新增状态 owner。Room Entity → UI model mapping 归现有 Feature ViewModel/StateHolder；Screen 只持有本地表单、Dialog、scroll 和 animation state。

## Before

```text
Feature Screen
→ Room Entity / Repository / Gateway
→ other feature UI/model/constants
```

## After

```text
Feature owner
→ feature UiState / UI models
→ Screen

真正双消费者的 entry UI
→ minimal shared ui.entry package
```

## Invariants

- Screen 只负责渲染、UI interaction、本地表单状态和平台 launcher/window effect。
- 不为单一消费者创建 shared package、wrapper 或额外 owner。
- 不按文件长度机械拆分 Compose 文件。
- Screen 可见行为、文本、导航和交互不变。
- 不修改 Room schema、API、sync protocol、backup format 或 feature 业务行为。

## Risks

Medium。过度共享会形成新的公共 God UI；类型映射不完整可能造成 UI 回归，或把 feature 循环迁移到通用包。

## Tests

- 运行受影响的 Ledger、Review、Account、Settings Screen tests。
- 运行 `MainActivityTest` 保护跨 Screen 装配和可达性。
- 用结构搜索验证 Context、Entity、Repository、Gateway 禁止依赖。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.MainActivityTest" --tests "com.bks.feature.ledger.LedgerEntryEditorScreenTest" --tests "com.bks.feature.review.ReviewQueueContentTest" --tests "com.bks.feature.account.AccountScreenTest" --tests "com.bks.feature.settings.DataAndBackupScreenTest"
.\gradlew.bat :apps:android:detekt
rg -n "BksRouteContext|BksRouteContentContext" apps/android/src/main/java
rg -n "^import com\.bks\.data\.local\..*Entity|Repository|Gateway" apps/android/src/main/java/com/bks/feature -g "*Screen.kt"
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

两个结构搜索的禁止命中必须清零；允许命中必须逐条记录理由，不能用宽泛例外掩盖残余依赖。

## Stop Conditions

- 共享 entry UI 只有单一消费者或需要承载业务 state。
- 移除 Entity/Repository/Gateway 依赖需要改变 Screen 可见行为。
- feature 循环只是被移动到新的 shared/common package。
- 禁止结构搜索仍有无法解释的命中。
- 需要新增无价值 wrapper、修改 schema/protocol/format 或按行数机械拆文件。
- 专项或 Phase 全量 Android JVM tests 失败且根因未解决。

## Completion Criteria

- Screen contract 只包含 UI model、UiState、Action 和必要平台 callback。
- `*Screen.kt` 不导入 Room Entity、Repository、Gateway 或 DAO。
- 目标 feature graph 无循环，偶然显示/类型依赖已移除。
- 共享 entry UI 只包含真实双消费者内容，没有无价值 shared wrapper。
- 专项测试、结构搜索、必要 Detekt、diff 检查与 Phase 全量 Android JVM tests 通过。

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
