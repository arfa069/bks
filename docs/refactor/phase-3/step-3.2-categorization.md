# Step 3.2 — Categorization

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
