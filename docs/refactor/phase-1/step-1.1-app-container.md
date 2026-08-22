# Step 1.1 — Application Container

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
