# Step 1.1 — Application Container

## Status

Completed

## Goal

建立唯一生产 `BksAppContainer`，将生产依赖创建从 Compose 移到 Application Composition Root，同时明确 Application-scoped singleton 与 lifecycle-scoped factory 的边界。

## Current Problem

`rememberBksAppDependencies()` 当前在 Compose 生命周期内创建或装配 Database、Repository、Gateway、Store 和 Coordinator。依赖生命周期与 Composition 混用，也容易把“唯一 Composition Root”错误实现为“所有对象都是全局 singleton”。

## Scope

- 建立 `BksApplication → BksAppContainer` 的唯一生产依赖图。
- 保留手动 DI，不引入 Hilt/Koin。
- Application scope 仅包含 Room Database、Repository、Secure/Local Store、HTTP Gateway、Diagnostic Repository，以及确实需要跨 UI 生命周期的 owner。
- Container 为 ViewModel、Feature StateHolder、Service capture session 和临时 operation object 提供 factory。
- 用临时 facade 维持尚未迁移消费者，后续 Step 删除。

## Files

- [`apps/android/src/main/java/com/bks/BksApplication.kt`](../../../apps/android/src/main/java/com/bks/BksApplication.kt)
- [`apps/android/src/main/java/com/bks/BksApp.kt`](../../../apps/android/src/main/java/com/bks/BksApp.kt)
- [`apps/android/src/main/java/com/bks/BksAppDependencies.kt`](../../../apps/android/src/main/java/com/bks/BksAppDependencies.kt)
- [`apps/android/src/main/java/com/bks/data/local/BksDatabaseProvider.kt`](../../../apps/android/src/main/java/com/bks/data/local/BksDatabaseProvider.kt)
- [`apps/android/build.gradle.kts`](../../../apps/android/build.gradle.kts)
- 计划新增：`apps/android/src/main/java/com/bks/BksAppContainer.kt`、`BksViewModelFactory.kt` 及对应测试。

## Planned Changes

- 由 `BksApplication` 持有生产 `BksAppContainer`。
- 将 Database、现有 Repository、Store、HTTP Gateway 和 Diagnostic Repository 的创建迁入 Container。
- Container 同时暴露 singleton dependencies 与明确的 `createXxxFactory(...)`/`createBillSyncCaptureCoordinator(...)` 类 factory；不得通过统一 `lazy` 把全部对象提升为 Application singleton。
- `BksAppOverrides` 作为同一依赖图上的测试 overlay，不创建第二套 Database。
- `BksAppDependencies` 暂时保留为旧消费者 facade，明确在 Step 7.1 删除。
- 只有 Compose `viewModel()` 确实需要且现有依赖图缺少时，添加与当前 Lifecycle 版本匹配的最小 `lifecycle-viewmodel-compose` artifact；不升级版本或整理依赖目录。

## State / Ownership Changes

- 生产依赖创建 ownership：Compose → `BksApplication` 持有的 `BksAppContainer`。
- ViewModel/Feature owner：不进入 Application singleton，由 Container factory 按 lifecycle 创建。
- Service capture coordinator：Service-scoped，由后续 Accessibility Step 使用 Container factory 创建。
- Screen/Composition 本地状态仍留在 Compose。

## Before

```text
BksApp Composition
→ rememberBksAppDependencies()
→ Database / Repository / Gateway / Store / Coordinator
```

## After

```text
BksApplication
→ BksAppContainer
   ├─ Application-scoped singleton dependencies
   └─ lifecycle-scoped factories
→ BksApp / Feature owners / Service / Worker
```

## Invariants

- 只存在一个生产 Composition Root，但不是所有依赖都是 Application singleton。
- 不引入 Hilt、Koin、机械 DI wrapper 或第二套数据库。
- 不修改业务行为、Room schema、API、sync protocol 或 backup format。
- 不升级 Lifecycle、Compose、Coroutines、Room 或其他依赖版本。
- 临时 facade 只能单向兼容旧消费者，并在 Step 7.1 删除。

## Risks

Medium。主要风险是错误 scope 导致状态跨生命周期泄漏、测试 override 创建第二套数据库，或在依赖迁移中改变初始化顺序。

## Tests

- `BksAppContainerTest`：生产 scope、singleton/factory 边界和同一 Database graph。
- `BksViewModelFactoryTest`：每次创建与生命周期隔离。
- `MainActivityTest`：Application/Activity/Compose 装配行为不变。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.BksAppContainerTest" --tests "com.bks.BksViewModelFactoryTest" --tests "com.bks.MainActivityTest"
.\gradlew.bat :apps:android:detekt
.\gradlew.bat :apps:android:assembleDebug
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

本 Step 只有实际修改 Gradle dependency 时才因依赖变更强制运行 `assembleDebug`；若执行时确认无需新增 artifact，应按 `AGENTS.md` 重新判断 APK 验证必要性并记录偏差。

## Stop Conditions

- Container 或测试 override 会创建第二套 Room Database。
- ViewModel、Service session 或临时 operation 只能通过全局 singleton 才能工作。
- 继续需要升级依赖版本、修改 Room schema 或引入第三方 DI。
- Compose 仍是生产 Database/Repository 的创建 owner，且没有明确迁移路径。
- 专项测试或 Application 启动装配失败。

## Completion Criteria

- `BksApplication` 持有唯一生产 `BksAppContainer`。
- Compose 不再创建生产 Database/Repository/Gateway/Store。
- Container 明确区分 Application-scoped dependencies 与 lifecycle-scoped factories。
- Test override 复用同一依赖图，不创建第二套 Database。
- 仅添加实际缺失的最小 artifact，且没有依赖版本升级。
- 专项测试、适用 Detekt、必要的 Debug 构建、Phase 全量 Android JVM tests 与 diff 检查通过。

---

## Execution Record

### Actual Changes

- 新增唯一生产 `BksAppContainer`，由 `BksApplication` 持有；Room Database、现有 Repository、Secure/Local Store、HTTP Gateway、头像缓存和 Diagnostic Repository 改为 Application-scoped lazy dependencies。
- `BksApp` 不再创建生产 Database、Repository、Gateway 或 Store，只从 Application container 请求临时 `BksAppDependencies` facade；`BksAppOverrides` 仅覆盖指定测试接口并复用 container 的同一 Database graph。
- Container 通过 factory 创建 composition-scoped `LedgerSyncCoordinator`、Activity-scoped `BillSyncStateCoordinator` 和 Service-scoped `BillSyncCaptureProcessor`，未把这些 owner 提升为 Application singleton。
- `MainActivity`、`BillSyncAccessibilityService`、`LedgerSyncWorker` 与 `DiagnosticLogsScreen` 改为消费 Application container；生产代码中的 Database、Repository、Gateway 和 Store 组装入口已收敛到 container。
- 保留 `BksAppDependencies` 作为旧消费者 facade，未提前执行后续 feature ownership 迁移；该 facade 仍按计划在 Step 7.1 删除。
- 新增 `BksAppContainerTest`，覆盖 Application 持有的 container 稳定性、singleton Database graph、override overlay 和 factory 生命周期隔离。

### Deviations From Plan

- 未新增 `BksViewModelFactory`、`BksViewModelFactoryTest` 或 `lifecycle-viewmodel-compose` artifact：当前代码没有 ViewModel 或 Compose `viewModel()` 消费者，强行创建空壳 factory 会违反本 Plan 的非强制准入规则。现有 lifecycle-scoped owner 的 factory 边界改由 `BksAppContainerTest` 直接验证。
- 未修改 `BksDatabaseProvider.kt` 或 `apps/android/build.gradle.kts`：现有 Provider 已提供唯一 Room instance，且当前依赖足够完成本 Step，没有 schema、migration、dependency 或版本变更。
- `DiagnosticLogsScreen.kt` 不在 Plan 的候选文件列表中，但执行时确认它仍从 Compose 直接获取生产 Diagnostic Repository；为满足本 Step completion criterion，将其最小改为消费同一 Application container，未修改诊断行为、数据格式或采集范围。
- 虽然没有 Gradle dependency、Manifest 或 Resource 改动，仍运行了 `assembleDebug`，因为实际迁移了 `BillSyncAccessibilityService` 的装配路径，适用的 `feature/billsync/AGENTS.md` 明确要求 Debug 构建验证。

### Test Results

- 专项命令（`BksAppContainerTest`、`MainActivityTest`、`BillSyncAccessibilityServiceHealthTest`、`com.bks.feature.diagnostics.*`）：通过，9 suites、52 tests、0 failures、0 errors、0 skipped。
- `:apps:android:detekt`：通过，0 issues。
- `:apps:android:assembleDebug`：通过。
- `:apps:android:testDebugUnitTest`：通过，75 suites、411 tests、0 failures、0 errors、0 skipped。
- 首次最终全量运行中，既有 `MainActivityTest.firstLocalModeSelectionNavigatesToHome` 在进入页动画期间仅能从 unmerged semantics tree 找到 `agreement-toggle`，单次失败（410/411）；该用例单独重跑通过，随后完整 411/411 重跑通过。
- `git diff --check`：通过。
- `gradlew --stop`：已执行。

### Remaining Risks

- `BksAppDependencies`、`BksAppRuntimeState` 与现有 Route/Screen God Layer 仍是有意保留的兼容边界，后续 Step 才迁移和删除；本 Step 未提前处理。
- 本 Step 未运行 instrumentation、真机或真实后端同步；没有业务、Room schema、API、Manifest、Resource 或可见 UI 行为变更要求这些验证，Service 注册壳由现有 Robolectric 测试和 Debug APK 构建覆盖。
- Factory 测试证明对象 identity 与同一 Database graph，但后续 feature owner 迁移仍需分别验证其 start/stop、重复启动和并发语义。
- `MainActivityTest.firstLocalModeSelectionNavigatesToHome` 暴露一次 Compose transition/merged semantics 可见性波动；当前无法稳定复现，专项运行、单测和最终全量重跑均通过，若后续再次出现应单独收紧测试等待条件。

### Final Status

Completed
