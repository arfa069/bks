# Step 6.1 — Accessibility Characterization

## Status

Completed

## Goal

在修改高风险 Accessibility 系统边界前，按 Android adapter 与业务 coordinator 边界锁定 Service callback、采集、过滤、上限、health 和 handoff 行为，不创建跨 App God Test。

## Current Problem

`BillSyncAccessibilityService` 同时包含 Android/Assists 平台适配与数据/业务 pipeline。直接拆分会难以区分 lifecycle、root selection、节点遍历或 persistence 回归；用一个巨大测试覆盖全部链路又会固定当前混合结构。

## Scope

- Service callback/lifecycle/health。
- Accessibility event window/root selection。
- BFS、节点/深度/文本上限。
- password/editable/visibility/own-package filtering。
- successful-window suppression/release。
- sanitized capture → fake coordinator handoff。
- Coordinator enabled/disabled、Processor failure/cancellation 的预期边界。

## Files

- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncAccessibilityService.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncAccessibilityService.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureProcessor.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureProcessor.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncPipeline.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncPipeline.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillPageParser.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillPageParser.kt)
- [`apps/android/src/test/java/com/bks/feature/billsync/`](../../../apps/android/src/test/java/com/bks/feature/billsync/)
- 仅在真实缺口存在时新增小型 Service adapter 或 coordinator characterization test。

## Planned Changes

- 复用现有 Service Health、Processor、Pipeline、Parser 和 Manifest tests。
- 按平台 adapter 与业务 handoff 边界补最小测试，不创建 `BksAppLifecycleCharacterizationTest` 或 Accessibility 全系统 God Test。
- 锁定 Service lifecycle callback、root selection、BFS、过滤、上限、sanitization 和 window suppression/release。
- 为 sanitized capture → fake coordinator handoff 建立 seam/测试，锁定旧新路径不能并行。
- 为未来 Service-scoped Coordinator 锁定 enabled/disabled、Processor failure 与 cancellation 行为。
- 如测试 seam 需要最小生产改动，必须不改变行为并在 Execution Record 说明。

## State / Ownership Changes

无最终 ownership 迁移。本 Step 只定义下一 Step 的边界合同：Service 负责 Android adapter，Coordinator 负责非 Android-specific 业务处理。

## Before

```text
Service platform callbacks + traversal + sanitization
→ in-Service business orchestration
→ Processor / persistence
```

## After

```text
独立 adapter characterization
→ Service lifecycle / roots / traversal / sanitization contract

独立 handoff characterization
→ Sanitized Capture / fake coordinator contract
```

## Invariants

- 不改变 Manifest、事件类型、采集范围、节点过滤、512 节点、24 层、16KiB 上限、日志或隐私规则。
- `BillSyncAccessibilityService` 最终仍保留 Android/Assists callback、生命周期、window/root、节点遍历和基础 sanitization。
- 自动记账候选仍只进入 Review Queue。
- Characterization tests 按边界组织，不绑定整个 App Root。
- Xiaomi/MIUI Accessibility 验证禁止使用 `uiautomator dump`。

## Risks

Low。主要风险是测试 seam 意外改变 Service 行为，或用过大的集成测试固化当前混合职责。

## Tests

- 现有 `BillSyncAccessibilityServiceHealthTest`、`BillSyncCaptureProcessorTest`、`BillSyncPipelineTest`、`BillPageParserTest`、`BillSyncManifestTest`。
- 仅补上述 Scope 中当前缺失的最小类；每类只保护一个明确 adapter/coordinator 边界。
- 覆盖成功、禁用、failure、cancel 和 limit/filter 边界。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.BillSyncAccessibilityServiceHealthTest" --tests "com.bks.feature.billsync.BillSyncCaptureProcessorTest" --tests "com.bks.feature.billsync.BillSyncPipelineTest" --tests "com.bks.feature.billsync.BillPageParserTest" --tests "com.bks.feature.billsync.BillSyncManifestTest"
git diff --check
```

若为测试 seam 修改生产 Kotlin，再运行必要的 `:apps:android:detekt`；本测试准备 Step 不默认运行 `assembleDebug`。

## Stop Conditions

- 测试必须扩大采集范围或改变 filtering/limits 才能通过。
- seam 会改变 Service lifecycle、Manifest、事件类型或生产 capture path。
- 拟新增测试跨 Root、Route、Screen 和 Service 多个业务边界。
- 旧、新 capture path 在测试中同时执行或可能重复入队。
- 现有专项测试失败且根因未解决。

## Completion Criteria

- Service Android adapter 与业务 coordinator 的预期边界分别有专项保护。
- callback/lifecycle/root/BFS/filter/limits/sanitization/window suppression 均由现有或最小新增测试覆盖。
- sanitized capture handoff、enabled/disabled、failure/cancellation 行为明确。
- 没有新的跨系统 God Test，采集和隐私规则未变化。
- 专项测试与 diff 检查通过；适用时 Detekt 通过。

---

## Execution Record

### Actual Changes

- 将原 `BillSyncAccessibilityServiceHealthTest` 收窄为 callback/lifecycle/health 专项测试；既有 root、遍历、过滤和上限断言迁入新增的 `BillSyncAccessibilityAdapterTest`，并补齐 window ID 优先、package fallback、无匹配返回 null、生产实际 `ActiveWindow.firstOrNull()` fallback 及按需 lookup、禁用/自身包 gate、全部六类语义文本字段、去重及不可见容器继续遍历的边界。
- 新增仅承载内存中净化结果的 `SanitizedBillSyncCapture` 与顶层 `handoffSanitizedCapture` 测试 seam；Service 原有 `recognize → successful-window suppression → IO persist → log/release` 代码改为委托给该单一路径，通过函数引用注入现有 Processor，没有并行旧/新 capture path。
- 新增 `BillSyncCaptureHandoffTest`，用 fake coordinator 证明 handoff 只接收 `collectReadableText()` 后的净化文本；锁定成功窗口只持久化一次、未识别页面释放、Processor failure 释放重试，以及 cancellation 继续传播且不按普通 failure 处理。
- 复审后将 `AcceptedWindowMemory` 从单纯 Set 收紧为最小 in-flight/release-requested 状态：persist 进行中出现未识别页面时延迟释放；若识别页面在同一 persist 完成前恢复，则取消延迟释放并继续抑制，避免同一成功页抖动启动第二次持久化；若未恢复，则在 persist 完成时释放。
- 把既有 enabled/own-package 判断收敛为无状态 `shouldCapturePackage` predicate，供生产路径和 adapter characterization 共用；未改变空 event package 的既有允许语义，也未改变 root 自身包过滤。
- 未修改 Manifest、Accessibility event type、Assists root lookup、采集字段与范围、512 节点/24 层/16KiB 上限、日志、隐私规则、Parser、Pipeline、Processor 持久化规则或 Review Queue 流程。

### Deviations From Plan

- 当前代码确实仍由 Service 混合平台读取与业务编排，而更具体的 `feature/billsync/AGENTS.md` 已描述下一阶段的 listener/coordinator 目标形态；按本 Step “只 characterization、不迁移最终 ownership”和用户“不执行后续 Step”的约束，本次没有提前注册 `AssistsService.listeners`、创建 Service-scoped Coordinator 或移除 Service callback。
- 为覆盖 sanitized capture handoff、failure 与 cancellation 的真实缺口，做了最小生产 seam 改动；没有新增 Coordinator/Manager/Wrapper，fake coordinator 仅存在于测试中。
- enabled/disabled 仍按当前事实由 Service gate 持有，并通过纯 predicate 锁定；没有为了匹配未来 Coordinator 描述而迁移 ownership。
- 首次完成后的独立代码审查发现完整 `ActiveWindow` fallback 未进入测试 seam，以及未识别 release 与 in-flight persist 的竞态；按用户“都修复”的最新明确指令补齐。后者是对极窄竞态语义的行为修复：顺序 release/success/failure/cancellation 合同不变，仅把 in-flight 期间的 release 延迟到 persist 收口。

### Test Results

- 修改前基线专项通过：Step 原指定的 5 suites（当时共 18 tests），0 failures/errors/skipped。
- 最终专项通过：`BillSyncAccessibilityServiceHealthTest`、`BillSyncAccessibilityAdapterTest`、`BillSyncCaptureHandoffTest`、`BillSyncCaptureProcessorTest`、`BillSyncPipelineTest`、`BillPageParserTest`、`BillSyncManifestTest`，共 7 suites / 26 tests，0 failures/errors/skipped；新增测试覆盖真实 ActiveWindow fallback、in-flight 延迟释放和识别恢复时继续 suppression。
- `:apps:android:detekt` 通过，未新增 suppression。
- `git diff --check` 退出码 0、无 whitespace error；新增未跟踪测试文件另以 no-index whitespace check 核对无输出。
- 未运行 `assembleDebug`、Instrumentation 或真机测试：本 Step 未修改 Manifest、Resources、Gradle dependency、BuildConfig 或平台配置，计划不要求 APK 验证；未使用 `uiautomator dump`。

### Remaining Risks

- Robolectric 锁定了 Service callback 壳、纯 root selection、节点遍历和 handoff 语义，但没有连接真实 Assists `AllWindows` / `ActiveWindow` 或系统 Accessibility lifecycle；真机窗口可用性和厂商差异仍需后续按专用日志链验证。
- Service 仍持有 preferences collect、root lookup、遍历与 handoff 编排；listener 注册/注销及最终 Service-scoped Coordinator ownership 属于 Step 6.2，本 Step 未执行。
- 当前 cancellation contract 是传播 cancellation 且保留该 Service 实例内的 successful-window suppression；后续迁移 coroutine owner 时必须用本次 characterization 防止把 cancellation 误当作可重试 failure。
- in-flight 窗口无法仅凭 package/window 判断识别恢复究竟是同一成功页抖动还是极快到达的下一笔交易；当前修复优先保证同一在途 persist 不重复入队。若后续真机证据表明确有上一笔 persist 未完成前到达下一笔交易的场景，Step 6.2 需要引入有证据的交易代次判定，而不能恢复无条件 release。
- 改动保留在未提交工作区；未 commit、push 或创建分支。

### Final Status

Completed
