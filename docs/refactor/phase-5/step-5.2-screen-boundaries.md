# Step 5.2 — Screen Boundaries

## Status

Completed

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

- Review AI 分类的 token、云设置和 `AiCategorizationGateway` 依赖从 `ReviewQueueScreen` contract 移入既有 `ReviewQueueViewModel` owner；Screen 只接收 `onAiSuggest` action 和 Review 自有结果 model，`BksReviewRoute` 只负责把 owner action 装配给 Screen。
- 将 Ledger 与 Review 确实共同消费的 entry form state、submission、category/funding UI model 和 Compose fields 移入最小 `ui.entry` 包；Manual Entry Screen、Ledger wrapper、持久化输入及 Entity/UI mapping 继续留在 Ledger feature。
- Screen action 不再暴露 `LedgerEntryInput`：Ledger owner 在 feature 边界把 `LedgerEntryFormSubmission` 映射为持久化输入，Review 编辑器则映射为 `PendingReviewEdit`；默认分类 Entity → UI model mapping 同样归 Ledger owner。
- Profile Screen 改为接收自有 `ProfileAccountSummaryUiModel`；Settings Data/Backup Screen 改为接收自有 backup/sync UI state、model、result 和 action contract，由 Composition Root mapper 适配 Ledger/Sync owner 类型。
- 移除 `review→billsync` 显示常量、`review→ledger` entry UI/model、`settings→ledger/sync` 和 `profile→account` Screen 偶然依赖；复审后将 Billsync 与 Review 真实共同使用的 `PaymentSource` / `CaptureReason` 标签及反向解析收敛为 feature 根边界的 typed mapping，避免显示字符串变化静默改变持久化枚举，未新增通用 wrapper 或状态 owner。
- 复审后将 Composition Root 与 `ReviewQueueViewModel` 重复的 AI 设置生效条件收敛到 Account owner 的单一函数；只有当前已验证账号 token 与云设置 token 匹配且云写入允许时，AI consent 与增强上下文授权才会生效。
- Data & Backup Route 的 Ledger entry → backup UI model 投影改为按 `ledgerState.entries` identity 记忆，避免无关 Compose 重组在主线程重复复制整表。
- 未新增状态 owner、依赖或 schema/API/protocol/backup format 变更；Screen 可见文本、导航和交互保持不变。

### Deviations From Plan

- Categorization/Compliance 未为符合计划而机械修改；Account 仅因复审发现的隐私门控重复新增一个既有状态类型上的纯函数。剩余 `AccountCloudSettingsCoordinator → categorization` 是云设置的明确业务接口，`ledger → account` 默认资金账户设置与 `review → account/categorization` AI owner 依赖也位于 owner 层，当前均无 feature 循环且不是 Screen 偶然依赖。
- 没有迁移全部 Ledger UI；只迁移 Ledger 与 Review 已存在的真实双消费者 entry form 内容，单一消费者的 Screen、wrapper 与持久化 mapping 仍归原 feature。
- 没有按文件长度拆分 Compose，也没有新增 ViewModel、Coordinator、class 或 wrapper；新增内容只有两个真实双消费者边界上的 typed/pure mapping 函数，拆分依据共享消费者和变化原因。
- Step 5.1 文档仍按用户约束保持 `Blocked`，没有修改其他 Step 的实施记录；其记录的 Review Route/Screen gateway 实际阻塞点已由本 Step 消除。

### Test Results

- `:apps:android:compileDebugKotlin` 与 `:apps:android:compileDebugUnitTestKotlin` 通过；仅有既存 `LocalClipboardManager` 弃用警告。
- 最终专项组合通过：Step 指定的 `MainActivityTest`、`LedgerEntryEditorScreenTest`、`ReviewQueueContentTest`、`AccountScreenTest`、`DataAndBackupScreenTest`，以及额外覆盖新边界的 `ReviewQueueAiSuggestionTest`、`ReviewQueueViewModelTest`、`ProfileScreenTest`，共 8 suites / 73 tests，0 failures/errors/skipped。最终微调后的首次组合运行暴露测试 Harness 未提供纯 UI 默认分类 fallback，补齐测试边界 fixture 后失败用例与完整专项组合均通过。
- 复审修复专项通过：`BillSyncPipelineTest`、`BillSyncCaptureProcessorTest`、`ReviewQueuePersistenceTest`、`ReviewQueueViewModelTest`、`BksAppRuntimeTest`、`DataAndBackupScreenTest`，共 6 suites / 45 tests，0 failures/errors/skipped；覆盖 Billsync → Review → Room 枚举/标签回环、匹配 token 的授权路径、token 不匹配与云写入受阻时 Gateway 零调用，以及 Data & Backup Screen contract。
- `:apps:android:detekt` 最终通过，未新增 suppression；首次检查发现的函数数、复杂度和测试 helper 参数问题已按职责归位消除，复审修复后再次运行仍通过。
- Android Phase 全量 `:apps:android:testDebugUnitTest` 通过：91 suites / 529 tests，0 failures/errors/skipped。
- `rg` 确认 `BksRouteContext|BksRouteContentContext` 为 0 命中；目标 `*Screen.kt` 的 Entity/Repository/Gateway 禁止依赖为 0 命中；额外目标 feature Screen 跨 feature import 搜索为 0 命中。
- `git diff --check` 退出码 0、无 whitespace error；Windows Git 仅输出工作区既有文件的 CRLF/LF 转换提示。未运行 `assembleDebug`、Instrumentation 或真机测试：本 Step 未修改 Manifest、Resources、Gradle、BuildConfig 或平台配置，计划与模块规则不要求 APK 验证。

### Remaining Risks

- JVM/Robolectric 已覆盖 Screen contract、导航装配、共享表单、Review AI owner、同步和备份，但尚未做真机手工视觉与交互验收。
- `ui.entry` 继续使用 `FlowDirection`、`TransactionKind`、`PaymentSource` 等稳定 data-local value enum，但不暴露 Entity、Repository、Gateway 或 DAO；本 Step 没有为形式纯度复制这些稳定类型。
- Account cloud settings、Ledger 默认资金账户和 Review AI 的 owner 层业务依赖仍存在但无循环；后续若继续收敛边界，不应把它们机械搬入泛化 common wrapper。
- 当前改动保留在未提交工作区；未 commit、push 或创建分支。

### Final Status

Completed
