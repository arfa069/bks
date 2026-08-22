# Step 0.1 — Baseline

## Status

Completed

## Goal

冻结重构前的 Android 行为基线，重新确认批准 Plan 记录的 406 个通过测试、Detekt 零问题和 Debug 构建状态，并只为下一阶段即将修改但缺少保护的行为补 characterization tests。

## Current Problem

现有测试覆盖较广，但尚未按后续迁移边界明确哪些行为是重构不变量。若用一个覆盖整个 App 生命周期的 God Test 补齐，会把当前 God Layer 固化到测试结构中，并产生低价值维护成本。

## Scope

- 复用现有 Account、Review、Sync、Backup、Navigation 测试。
- 仅在真实缺口存在时，按业务边界补小型 characterization test。
- 记录测试、Detekt、Debug build 与 Git 工作树基线。
- 不实施任何架构迁移，不建立新生产 owner。

## Files

- [`apps/android/src/test/java/com/bks/MainActivityTest.kt`](../../../apps/android/src/test/java/com/bks/MainActivityTest.kt)
- [`apps/android/src/test/java/com/bks/ui/BksAppStateTest.kt`](../../../apps/android/src/test/java/com/bks/ui/BksAppStateTest.kt)
- [`apps/android/src/test/java/com/bks/feature/account/`](../../../apps/android/src/test/java/com/bks/feature/account/)
- [`apps/android/src/test/java/com/bks/feature/review/`](../../../apps/android/src/test/java/com/bks/feature/review/)
- [`apps/android/src/test/java/com/bks/feature/sync/`](../../../apps/android/src/test/java/com/bks/feature/sync/)
- [`apps/android/src/test/java/com/bks/feature/settings/`](../../../apps/android/src/test/java/com/bks/feature/settings/)
- 仅在确认缺口后候选新增：`AccountSessionCharacterizationTest.kt`、`BksAppNavigationCharacterizationTest.kt`、`ReviewPersistenceCharacterizationTest.kt`、`SyncRuntimeCharacterizationTest.kt`。

## Planned Changes

- 先复用现有测试证明 Account restore/verify/offline/invalid、账户管理返回登录页、Review 持久化、Sync outbox/profile mismatch/resume gate 与 Backup validate 零写入。
- 只补下一 Phase 会修改且当前测试无法保护的具体行为。
- 新测试按业务边界落入对应 package，不创建 `BksAppLifecycleCharacterizationTest` 或其他跨系统 God Test。
- 记录准确的测试数量和基线结果；批准 Plan 中的 406 仅作为待重新确认的历史基准。

## State / Ownership Changes

无。此 Step 只建立行为保护，不移动状态或生命周期 ownership。

## Before

```text
现有分散测试
→ 未显式对应后续迁移风险
→ 重构时难以判断失败是回归还是测试缺口
```

## After

```text
现有专项测试 + 必要的小型 characterization tests
→ 对应 Account / Review / Sync / Navigation / Backup 边界
→ 为后续每个 Step 提供可定位的回归信号
```

## Invariants

- 当前代码、测试、当前提示词与 `AGENTS.md` 是事实来源，旧 PRD 不作为验收依据。
- 不修改业务行为、Room schema、API、sync protocol、backup format 或 Accessibility 采集范围。
- 不为了覆盖率或重构准备制造低价值测试。
- 未发现真实缺口时不新增测试类。

## Risks

Low。主要风险是新增测试绑定旧 God Layer 的内部实现，或把批准 Plan 当时的测试数量误当作执行时的固定事实。

## Tests

- 优先运行现有 Android JVM、Compose、Room 和 Robolectric 测试。
- 必要新增测试应分别覆盖成功、失败、空值、持久化或并发场景。
- 重点确认 Review stale action + concurrent capture、Sync resume gate 和 Backup validate 零写入是否已有足够保护。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
git diff --check
.\gradlew.bat --stop
```

测试-only Step 不运行 Detekt 或 `assembleDebug`；只有新增测试 seam 确实修改生产 Kotlin 时，才补运行必要 Detekt。Debug 构建基线仅按当前 `AGENTS.md` 和执行时实际需要记录，不为文档拆分运行。

## Stop Conditions

- 现有关键测试失败，且根因尚未定位。
- 需要修改业务行为才能让 characterization test 通过。
- 拟新增测试必须跨多个业务边界、依赖 God Layer 内部结构或没有明确保护对象。
- 基线与批准 Plan 记录不一致但尚未写清实际结果。

## Completion Criteria

- 当前 Android 测试基线和实际数量已记录。
- 下一阶段高风险行为已有现有测试或最小必要的边界测试保护。
- 没有新增跨系统 God Test。
- 文档与测试改动通过 `git diff --check`，Execution Record 已写回实际结果。

---

## Execution Record

### Actual Changes

- 复核现有 Account、Navigation、Review、Sync 与 Backup 测试后，确认 Account session restore/verify/offline/invalid、账户入口返回、Review stale snapshot 与 capture 交错、Sync outbox/profile switch 和 Backup validate 零写入已有边界测试保护。
- 新增 `SyncRuntimeCharacterizationTest`，补齐此前没有直接测试的两条 Sync runtime 行为：只有 `ON_RESUME + SignedIn + Verified + sync enabled` 才允许立即同步；远端 profile 与本机绑定不一致时，在 push/pull 前返回 `SYNC_ACCOUNT_SWITCH_REQUIRED`。
- 将现有纯函数 `shouldEnqueueLedgerSync` 从 `private` 放宽为 `internal`，仅作为同模块 characterization test seam；未移动 ownership、未修改业务分支。
- Android 测试源码从历史基线 406 个 `@Test` 增至 408 个。

### Deviations From Plan

- 批准 Plan 记录的 406 个测试与执行前源码计数一致；确认 Sync runtime 存在两个真实保护缺口后新增 2 个测试，因此最终基线为 408，而不是固定沿用 406。
- 因测试 resume gate 需要最小生产 seam，按 Validation Rules 额外运行了 Android Detekt。
- 未运行 `assembleDebug`：本 Step 未涉及 Manifest、Resources、Gradle dependency、BuildConfig 或平台配置；Android 测试任务已成功完成 Debug 与 DebugUnitTest Kotlin/Java 编译。

### Test Results

- `:apps:android:testDebugUnitTest --tests "com.bks.SyncRuntimeCharacterizationTest"`：通过，2/2。
- `:apps:android:detekt`：通过，0 issues。
- `:apps:android:testDebugUnitTest`：最终通过，74 suites、408 tests、0 failures、0 errors、0 skipped。
- 首次全量运行曾出现既有 `MainActivityTest.reportsFollowTheSelectedLedgerBook` 未及时显示“旅行账本”的单次失败（407/408）；该用例单独重跑通过，随后完整 408/408 重跑通过。
- `git diff --check`：通过。
- `gradlew --stop`：已执行。

### Remaining Risks

- `MainActivityTest.reportsFollowTheSelectedLedgerBook` 在首次全量运行中暴露一次 Compose/Room 异步可见性波动；当前无法稳定复现，单测和完整重跑均通过，后续若再次出现应单独治理其等待条件。
- 本 Step 按规则未运行 APK assemble、instrumentation 或真机验证；没有资源、Manifest、平台或可见行为改动要求这些验证。

### Final Status

Completed
