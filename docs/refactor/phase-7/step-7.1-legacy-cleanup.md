# Step 7.1 — Legacy Cleanup

## Status

Completed

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

### Prior Blocked Attempt

- 第一次执行在生产代码改动前按 Stop Conditions 停止：当时 Step 6.2 仍为 `Blocked`，且没有连接设备可补齐强制真机证据。
- 恢复本 Step 前重新核对了 Step 6.2 的最终记录：Xiaomi Android 16 真机已完成 Accessibility Enabled/Bound 与同一次 `event → capture → roots → collected → processed recognized=true created=1` 链路，重复事件未重复落库；Step 6.2 已更新为 `Completed`，前置门禁解除。

### Actual Changes

- 删除 `BksAppRuntime.kt`、`BksAppEffectsContext.kt`、`BksAppDependencies.kt` 及旧 `BksAppRuntimeTest`，移除 Root runtime/actions/presentation/effects 和 Local/Account/Sync dependency facade；`BksRouteContext`、`BksRouteContentContext`、`rememberBksAppDependencies` 与 `BksLegacy*Bridge` 在恢复执行前已不存在，未为符合旧 Plan 创建替代类型。
- `BksApp` 直接 lifecycle-aware 收集 `AccountSessionCoordinator`、`AccountCloudSettingsCoordinator`、Feature ViewModel state 与 `LocalPreferencesRepository.categorizationRules`；AI 设置直接使用现有 owner 的有效快照，规则写入直接调用 Repository，`ON_RESUME` 直接转发给匹配的 `LedgerSyncRuntime`。
- 子代理复审发现分类规则在 Room Flow 回流前连续操作时，两个整表快照可能发生 last-write-wins 覆盖；规则回调现改为传递纯变换，并由 `LocalPreferencesRepository.updateCategorizationRules` 在同一 Room transaction 内读取最新规则、应用变换、全表写入和记录 outbox。规则管理页与 Review 创建规则共用该入口，没有恢复 Root/Compose 镜像，也没有新增微型 ViewModel。
- 手工新建规则 ID 改为基于事务内最新规则的最大 `rule-N` 生成下一个编号，避免删除中间规则后因 `rules.size + 1` 与既有 ID 冲突；`cloudWritesPaused` 与 `settingsSyncInFlight` 则保留为 account feature 内的纯派生函数并补回直接单测。
- `BksAppContainer` 保留唯一生产 graph，并以三个 lifecycle resolver 保持测试 override 的 Session、Cloud Settings、Sync owner 来自同一 coordinator graph；`BksRouteFactories` 改为接收明确的 `AccountRepository`、`WechatAuthGateway` 和 Session owner，不再接收聚合 facade。
- 将旧 Root presentation 的跨账号、注销冷静期与登出行为测试迁到 `feature/account/AccountAiSettingsTest`；更新 Container 测试，证明生产 owner 仍复用 Application singleton，账号 override 仍创建相互隔离的 Session/Cloud/Sync owner，并继续复用生产 Room graph。
- 最终 `build` 发现 Benchmark CSV 调用仍传入旧 Ledger UI 模型；调用方已显式映射为当前 `BackupLedgerEntryUiModel`，CSV 字段与备份实现不变。
- 最终 Lint 发现 `assists-base` 传递引入的 `utilcodex` 中未被调用的 `NotificationUtils` 触发权限误报。字节码依赖检查确认 `assists-base` 没有引用该类后，新增只匹配其完整类名的 `lint.xml` ignore；没有声明通知权限、关闭整个规则或改变 Manifest/运行时行为。
- `docs/ARCHITECTURE.md` 已按最终代码更新 Composition Root、Repository/Coordinator ownership、Route/Screen 边界与 Accessibility adapter 说明。

### Deviations From Plan

- Plan 列出的 `BksRouteContext.kt`、`BksRouteContentContext`、`rememberBksAppDependencies`、`BksLegacy*Bridge` 和 Sync legacy projection 在当前代码中已不存在，因此本轮只用结构搜索确认，没有强行创建或删除替代物。
- 未删除 Room/Backup/Secure Session/Review 中承担真实旧数据兼容职责的 migration、codec 和清洗逻辑；这些不是临时架构 bridge，删除会改变数据兼容行为。
- 未创建 `CategorizationViewModel` 或新的聚合 composition data class；分类规则仍以 Repository Flow 为唯一持久真实源，符合 Step 3.2 的 owner 准入决定。
- 复审修复没有采用原建议的 Feature 乐观镜像；当前调用链改为把规则变换送入 Repository transaction，直接从最新持久化快照计算结果。这同时覆盖规则管理页和 Review 页，并继续保持 Room Flow 是唯一 UI/持久化 source of truth。
- 为通过本 Step 强制的完整 `build`，额外修复了一个已存在的 Benchmark source-set 类型漂移，并加入一个精确到未使用第三方类名的 Lint 过滤；两项均为验证期间暴露的最小收口，不修改依赖、权限、业务或数据契约。

### Test Results

- 专项：`BksAppContainerTest`、`MainActivityTest`、`AccountAiSettingsTest`、`LocalPreferencesRepositoryTest`、`CategorizationRulesScreenTest` 与 `:apps:android:detekt` 通过。复审新增用例覆盖事务内连续规则变换、删除后的唯一规则 ID，以及 `cloudWritesPaused` / `settingsSyncInFlight` 派生。
- Android 全量：复审修复后的 `:apps:android:testDebugUnitTest` 最终 547 项通过、0 failed、0 skipped。此前首轮曾有 `SecureAccountSessionStoreTest.corruptedCiphertextIsClearedAndDowngradesSafely` 受 Robolectric 未执行主线程任务影响返回 `Empty`；该类独立复跑通过，随后完整测试持续通过。
- 静态与产物：`:apps:android:detekt`、`:apps:android:lintDebug`、`:apps:android:assembleDebug`、`:apps:android:compileBenchmarkReleaseKotlin`、`:apps:android:compileNonMinifiedReleaseKotlin` 均通过。
- 全仓库：复审修复后的 `:apps:android:assembleDebug build` 无排除项通过；Android、Macrobenchmark、Backend 与 Shared API 的编译、测试、Detekt/Lint、Release/Benchmark 打包聚合成功。构建仅保留既有微信 SDK D8/R8 stack-map warnings 与既有 deprecated API warning。
- 结构：旧符号与 dependency facade 搜索零命中；所有 `*Route*.kt` / `*Screen*.kt` 对 Database、DAO、Container 的禁止依赖搜索零命中；`git diff --check` 通过。
- Scope：diff 未触及 Room schema、`services/backend`、`shared/api`、API/sync protocol、backup format、依赖版本、Manifest 或 Accessibility 采集范围；本 Step 未重复执行真机操作，复用了已记录并完成门禁的 Step 6.2 真机证据。

### Remaining Risks

- `BksApp` 仍是较长的根组合函数，但只承担 Composition、Navigation、lifecycle forwarding、Route callback 与全局 Dialog；现有 `LongMethod`/`CyclomaticComplexMethod` suppression 未增加，且没有 Root 镜像 state 或 dependency facade。
- `lint.xml` 的精确过滤依赖当前 `assists-base 3.5.5` 未调用 `NotificationUtils` 的事实；未来升级 Assists/utilcodex 时应重新做字节码依赖检查，若实际引入通知能力则必须设计权限与运行时申请流程。
- 微信 SDK 在 D8/R8 阶段继续输出既有 stack-map warnings，当前所有 APK/Release 构建成功；本 Step 未升级或改写第三方依赖。

### Final Status

Completed — 前置真机门禁已解除，旧 Application-level God Layer、dependency facade 与临时 projection 已删除；最终架构说明、旧符号/禁止依赖搜索、Android 全量、Detekt、Lint、Debug APK 和完整 `build` 均达到 Completion Criteria。停止于 Step 7.1，不进入后续工作。
