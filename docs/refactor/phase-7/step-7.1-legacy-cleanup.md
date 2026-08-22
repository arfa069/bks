# Step 7.1 — Legacy Cleanup

## Status

Pending

## Goal

在 Application、Feature presentation、Route/Screen 与 Accessibility 边界全部稳定后，删除旧 Application-level God Layer 和所有临时桥接，完成整轮验证与现行架构说明同步。

## Current Problem

渐进迁移期间仍会保留 `BksAppRuntimeState`、`BksAppActions`、`BksAppPresentation`、`BksAppEffectsContext`、`BksRouteContext`、legacy state projection 与 dependency facade。若长期共存，会重新形成两套 source of truth 和依赖入口。

## Scope

- 删除旧 Root runtime/actions/presentation/effects/context 类型和创建函数。
- 删除所有 `BksLegacy*Bridge`、只读 projection 和过渡 overload。
- 保留唯一 Container、Navigation state、满足准入条件的 feature owner 与非空壳 Accessibility Service。
- 全仓库旧符号和禁止依赖搜索。
- 从最终代码/测试反向同步 Android 架构说明；旧 PRD 不作为行为来源。
- 运行最终 Android 与全仓库验证。

## Files

- [`apps/android/src/main/java/com/bks/BksApp.kt`](../../../apps/android/src/main/java/com/bks/BksApp.kt)
- [`apps/android/src/main/java/com/bks/BksAppRuntime.kt`](../../../apps/android/src/main/java/com/bks/BksAppRuntime.kt)
- [`apps/android/src/main/java/com/bks/BksAppEffectsContext.kt`](../../../apps/android/src/main/java/com/bks/BksAppEffectsContext.kt)
- [`apps/android/src/main/java/com/bks/BksAppDependencies.kt`](../../../apps/android/src/main/java/com/bks/BksAppDependencies.kt)
- [`apps/android/src/main/java/com/bks/BksRouteContext.kt`](../../../apps/android/src/main/java/com/bks/BksRouteContext.kt)
- [`apps/android/src/main/java/com/bks/BksRouteHost.kt`](../../../apps/android/src/main/java/com/bks/BksRouteHost.kt)
- [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md)
- 所有执行期间明确标记为 temporary legacy bridge/projection/overload 的文件或符号。

## Planned Changes

- 删除 `BksAppRuntimeState`、`BksAppActions`、`BksAppPresentation`、`BksAppEffectsContext`、`BksRouteContext`、`BksRouteContentContext` 与 `rememberBksAppDependencies`。
- 删除所有 `BksLegacy*Bridge`、Sync legacy state projection、迁移期 dependency facade 和旧 Screen overload。
- `BksApp` 最终只保留主题、session gate、Navigation、lifecycle forwarding、RouteHost 和真正全局 Dialog。
- 保留 `BksAppContainer`、`BksAppState`、满足准入条件的 Coordinator/ViewModel/StateHolder、现有 Repository/Room/Parser/Pipeline/Reducer/Sync/Backup/Diagnostics core。
- 保留承担完整 Android adapter 职责的 `BillSyncAccessibilityService`。
- 搜索旧符号和 Screen/Route 的直接数据层访问，逐条消除禁止命中。
- 检查 Git diff，确认 Room schema、`shared/api`、backend、API/sync protocol、backup format、依赖版本和 Accessibility 采集范围无变化。
- 仅依据最终代码和测试更新 Android 架构说明；不顺带改写旧 PRD 的产品行为。

## State / Ownership Changes

- 删除 Root 对 Account、Ledger、Review、Categorization、Automatic、Sync、Backup、Diagnostics 状态的镜像。
- Application-level 只保留真实跨 Feature/生命周期 Coordinator state。
- Feature state 由对应 ViewModel/StateHolder 直接从 Repository Flow 形成。
- Navigation 继续由 `BksAppState` 持有；Android Accessibility adapter 继续由 Service 持有。

## Before

```text
New Container / Coordinators / ViewModels
↔ temporary legacy bridges
↔ BksApp Runtime / Actions / Effects / Presentation / RouteContext
```

## After

```text
BksApplication → BksAppContainer
→ Application coordinators / lifecycle factories
→ Feature ViewModel / StateHolder
→ Repository / Gateway
→ Room / API / Android System

Route → owner + state + platform callback → Screen
```

## Invariants

- 只有全部前置 Step 达到 Completion Criteria 后才能删除桥接。
- 不删除 `BksAppContainer`、`BksAppState`、有真实职责的 feature owner 或非空壳 Accessibility Service。
- 不修改业务行为、Room schema、API、sync protocol、backup format、依赖版本或 Accessibility 采集范围。
- 不引入 Hilt/Koin、Gradle feature module、机械 Clean Architecture 或微型 wrapper。
- 当前代码、测试、当前提示词和 `AGENTS.md` 高于 Plan 假设；旧 PRD 不作为验收标准。

## Risks

Medium。主要风险是遗漏隐藏消费者、过早删除临时桥接，或在最终清理中顺带改变数据契约和现有行为。

## Tests

- 运行完整 Android JVM tests、Android 模块 Detekt、Debug APK 和完整 `build`。
- 运行旧符号、Route/Screen 禁止依赖和 Git diff 结构检查。
- 复核 Phase 2–6 的高风险专项测试与真机 Accessibility 证据均已记录。
- 重新读取所有删除/修改文件，确认 import、source of truth 和 lifecycle 正确。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat :apps:android:detekt
.\gradlew.bat :apps:android:assembleDebug
.\gradlew.bat build
rg -n "BksAppRuntimeState|BksAppActions|BksAppPresentation|BksAppEffectsContext|BksRouteContext|BksRouteContentContext|rememberBksAppDependencies|BksLegacy" apps/android/src/main/java
git diff --check
git status --short
.\gradlew.bat --stop
```

旧符号结构搜索必须无禁止命中；历史文档命中不作为生产代码通过条件，但需确认没有被当作现役架构入口。

## Stop Conditions

- 任一前置 Step 未完成、被标记 Blocked 或仍有未验证高风险行为。
- 旧符号仍有生产消费者，或删除后专项/完整测试失败。
- Accessibility 真机门禁未完成且未按批准规则处理为 Blocked。
- Git diff 出现 Room schema、backend、`shared/api`、API/sync protocol、backup format、依赖升级或采集范围变化。
- 为清零搜索需要删除有真实职责的 owner/adapter，或引入新 wrapper。
- Detekt、Debug APK 或完整 build 失败且根因未解决。

## Completion Criteria

- 旧 God Layer、Route Context、dependency facade 和所有 temporary bridge/projection/overload 已无生产引用并删除。
- `BksApp` 只保留 App-level UI/Navigation/lifecycle 装配职责。
- Container、Coordinator、Feature owner、Repository 与 Android adapter ownership 与目标架构一致。
- 旧符号和禁止依赖搜索无禁止命中。
- Room/API/sync/backup/dependency/Accessibility 全局不变量无变化。
- Android tests、Detekt、`assembleDebug`、完整 `build`、diff 检查通过，Daemon 已停止。
- 架构说明从最终代码和测试反向同步，Execution Record 记录最终状态与剩余风险。

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
