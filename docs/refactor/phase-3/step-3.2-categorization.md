# Step 3.2 — Categorization

## Status

Completed

## Goal

收紧 Categorization 边界，移除其对 Account Session 和 Review model 的反向依赖，并在执行时按准入规则决定是否需要独立 presentation owner。

## Current Problem

AI categorization client 与 UI 路径混用了 Account、Review 和 Categorization 类型，形成 feature 循环。若机械新增 `CategorizationViewModel`，可能只得到规则 Flow 与简单 Repository 转发的微型 wrapper。

## Scope

- AI categorization client 的 token、通用 payload 和 settings 输入边界。
- Review entry → AI payload 的调用方映射。
- 通用 backend endpoint 校验位置。
- Categorization rules 与 AI settings presentation owner 的准入判断。
- 不新增额外 UseCase、Manager 或无价值 interface。

## Files

- [`apps/android/src/main/java/com/bks/feature/categorization/AiCategorization.kt`](../../../apps/android/src/main/java/com/bks/feature/categorization/AiCategorization.kt)
- [`apps/android/src/main/java/com/bks/feature/categorization/HttpAiCategorizationGateway.kt`](../../../apps/android/src/main/java/com/bks/feature/categorization/HttpAiCategorizationGateway.kt)
- [`apps/android/src/main/java/com/bks/feature/categorization/CategorizationRules.kt`](../../../apps/android/src/main/java/com/bks/feature/categorization/CategorizationRules.kt)
- [`apps/android/src/main/java/com/bks/feature/categorization/CategorizationRulesScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/categorization/CategorizationRulesScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/BackendTransportPolicy.kt`](../../../apps/android/src/main/java/com/bks/feature/BackendTransportPolicy.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueueState.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueueState.kt)
- 候选新增：`CategorizationViewModel.kt` 或纯 Kotlin feature state holder，仅在执行时满足准入条件后创建。

## Planned Changes

- `AiCategorizationClient` 接收 token、通用 payload 与 settings，不接收 `AccountSession` 或 `ReviewQueueEntry`。
- Review feature 自己把 Review entry 映射为通用 AI payload。
- 通用 endpoint/transport 校验归入 `BackendTransportPolicy.kt`，不由 Account 或 Categorization 私有复制。
- 执行前判断规则 Flow、写入错误和 AI settings operation 是否形成独立状态、生命周期或多异步操作：满足则创建 `CategorizationViewModel`/state holder；否则并入页面现有自然 owner。
- 不为一两个 Repository 方法或无状态 mapping 创建独立类。

## State / Ownership Changes

- Account Session 只向边界提供必要 token，不把 Account domain model交给 Categorization。
- Review entry → AI payload mapping 由 Review owner 负责。
- Categorization presentation state 是否独立持有由准入检查决定；持久规则 source of truth 仍是 Repository/Room。

## Before

```text
Categorization
↔ AccountSession domain model
↔ ReviewQueueEntry domain/UI model
→ feature cycles
```

## After

```text
Account owner → token
Review owner → generic AI payload
Categorization client → token + payload + settings

Rules Repository Flow
→ natural Categorization presentation owner（仅满足准入时独立）
```

## Invariants

- AI categorization 结果仍进入 Review 流程，不绕过 Review 直接写已确认 Ledger。
- 不修改 AI/cloud API、endpoint 语义、Room schema 或 categorization 业务规则。
- 不创建只做 Repository 转发或无状态 mapping 的 ViewModel/Coordinator。
- 通用 transport policy 不依赖具体 feature model。

## Risks

Medium。主要风险是移动类型依赖时改变 payload/endpoint 行为，或把 feature 循环替换为共享层和微型 wrapper 膨胀。

## Tests

- 复用 Categorization rules、Screen、AI client、HTTP gateway 和 Account repository 测试。
- 如最终创建 presentation owner，再增加其成功、失败、空值和 lifecycle 专项测试。
- 增加/保留类型边界测试，确认 token 与通用 payload 不携带 Account/Review domain object。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.categorization.CategorizationRulesTest" --tests "com.bks.feature.categorization.CategorizationRulesScreenTest" --tests "com.bks.feature.categorization.AiCategorizationClientTest" --tests "com.bks.feature.categorization.HttpAiCategorizationGatewayTest" --tests "com.bks.feature.account.HttpAccountRepositoryTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

如创建 `CategorizationViewModel` 或 state holder，必须把其专属测试加入命令。

## Stop Conditions

- 新 owner 不满足独立状态、生命周期、多异步操作或真实业务编排准入条件。
- 移除循环依赖需要改变 AI payload、endpoint、API 或 categorization 业务行为。
- 新的 shared/Manager/UseCase 只能用“架构一致性”解释，无法指出消除的耦合。
- `account ↔ categorization` 或 `categorization ↔ review` 依赖循环仍存在。
- 专项测试失败且根因未解决。

## Completion Criteria

- AI client 只接收 token、通用 payload 和 settings。
- Review 自己完成 Review entry → AI payload 映射。
- 通用 endpoint 校验位于非具体 feature 的 transport policy。
- Account/Categorization 与 Categorization/Review 的循环依赖消失。
- 是否创建独立 owner 已按准入规则记录，没有新增微型 wrapper。
- 专项测试、必要 Detekt 与 diff 检查通过。

---

## Execution Record

### Actual Changes

- `AiCategorizationClient` 的调用边界改为 nullable token、`AiCategorizationPayload` 与 `AiCategorizationSettings`；Categorization 生产代码不再引用 `AccountSession` 或 `ReviewQueueEntry`。Client 继续负责登录/同意 gate、候选分类去空去重、增强上下文二次授权与 Gateway 结果映射，Gateway 仍只接收非空类型 token 和通用 payload。
- 新增 Review-owned 纯映射文件 `ReviewCategorization.kt`：由 Review 将 `ReviewQueueEntry` 映射为通用 AI payload，并在未授权增强上下文时剔除 note/raw evidence；原先位于 Categorization 的 `ReviewQueueEntry.applyCategorizationSuggestion` 同步迁回 Review，调用既有确定性 `suggestCategory` 规则函数。自动捕获仍先生成 Review entry，再应用建议并持久化 Review Queue。
- `ReviewQueueScreen` 只接收 token，不再接收 `AccountSession`；`BksReviewRoute` 从已登录 Session 提取 token 后传入。既有无 Gateway/未登录/未同意、候选分类、loading、防重复提交、失败文案和建议写回编辑草稿行为保持不变。
- `toBackendEndpointOrNull` 从 Categorization 的 Cloud settings Gateway 文件迁入现有非具体 feature 的 `BackendTransportPolicy.kt`；Account、AI categorization 与 Cloud AI settings HTTP 实现复用同一校验，HTTPS、仅私网测试 HTTP、userinfo/query/fragment 拒绝和 endpoint 拼接语义未变。
- 未创建 `CategorizationViewModel` 或 state holder：规则 presentation 只有 Repository Flow 与单一 replace operation，筛选/编辑/Dialog 已由 Screen 局部状态持有；AI settings 的持久状态、busy 与 cloud-write gate 已由 Step 2.2 的 `AccountCloudSettingsCoordinator` 持有，不满足独立生命周期、多异步业务编排或新增 source-of-truth 的准入条件。
- 新增 `ReviewCategorizationTest`，并改写 `AiCategorizationClientTest` 直接使用 token/payload/settings；增加反射边界断言，确认 Client 参数不包含 Account/Review domain 类型，同时覆盖最小 payload、增强上下文授权与二次脱敏。

### Deviations From Plan

- 未修改候选文件 `ReviewQueueState.kt`：该文件已集中 reducer/model，新增独立的纯函数文件 `ReviewCategorization.kt` 能明确表达 Review-owned mapping，且没有引入类、interface、Manager 或 UseCase。
- 未修改 `CategorizationRulesScreen.kt` 或创建候选 presentation owner；这是按 Plan 准入规则得出的执行结果，不是遗漏。既有 Root rules projection 仍作为可回滚桥接，移除 God Layer 属于后续 Step，当前 Step 不提前处理。
- 为迁移真实调用方与保持自动捕获路径编译/行为一致，最小修改了候选清单外的 `BksLedgerRoutes.kt`、`ReviewQueueScreen.kt`、`BillSyncCaptureProcessor.kt`、`HttpCloudAiSettingsGateway.kt`、`LocalPreferencesRepositoryTest.kt` 及对应 Review 测试；未进入 Step 3.3 的 Review state/persistence/concurrency 重构。
- 未修改 shared API、Backend route、AI payload 字段、endpoint 路径、Room schema、sync protocol、backup format、依赖或可见 UI。

### Test Results

- 变更前 Step 指定专项基线：`CategorizationRulesTest`、`CategorizationRulesScreenTest`、`AiCategorizationClientTest`、`HttpAiCategorizationGatewayTest`、`HttpAccountRepositoryTest` 全部通过，Gradle `BUILD SUCCESSFUL`。
- 最终 Step 指定专项加直接受影响补充回归通过：上述 5 类，加 `HttpCloudAiSettingsGatewayTest`、`ReviewCategorizationTest`、`ReviewQueueAiSuggestionTest`、`BillSyncCaptureProcessorTest`、`LocalPreferencesRepositoryTest`，共 10 suites、66 tests、0 failures、0 errors、0 skipped。
- `:apps:android:detekt`：通过，0 issues；最终复跑为 `BUILD SUCCESSFUL`。
- 依赖边界搜索：Categorization 生产代码对 Account/Review 的 import 为 0；Account/Review 只单向依赖 Categorization contract，循环已消失。`toBackendEndpointOrNull` 仅定义于 `BackendTransportPolicy.kt`，三个 HTTP consumer 均从通用位置导入。
- `git diff --check`：tracked 与两个新增文件分别检查通过。

### Remaining Risks

- 未运行 Android 全量 JVM/Robolectric suite、instrumentation、真机或真实 Backend 验收：本 Step 不是 Phase 完成点，且未修改 StateFlow、Room/API/资源/Manifest/依赖；专项 JVM/Robolectric 已覆盖实际边界与 UI 调用链。
- Categorization rules 仍通过当前 Root runtime/actions bridge 暴露给页面和 Review 保存规则入口；本 Step 按 owner 准入规则保留该可回滚桥接，后续 God Layer 收敛 Step 才处理，当前没有新增第二份持久真相。
- `LedgerSyncRepository` 保留其既有 sync-specific endpoint wrapper/policy；本 Step 只移动 Account/Categorization 共用 helper，未跨入已完成的 Sync Runtime Step 或改变其网络契约。

### Final Status

Completed
