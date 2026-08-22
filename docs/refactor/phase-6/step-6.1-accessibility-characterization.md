# Step 6.1 — Accessibility Characterization

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
