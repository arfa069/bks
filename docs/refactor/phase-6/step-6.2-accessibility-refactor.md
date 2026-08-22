# Step 6.2 — Accessibility Refactor

## Status

Pending

## Goal

将 `BillSyncAccessibilityService` 收敛为非空壳 Android Accessibility adapter，把 Database、Repository、Processor 创建、持久化和非 Android-specific pipeline 编排迁入 Service-scoped `BillSyncCaptureCoordinator`。

## Current Problem

Service 当前直接创建/访问 Database、Repository、Preferences、`ReviewQueuePersistence` 与 `BillSyncCaptureProcessor`，并承担 IO/persistence failure 和业务 pipeline 编排。平台 callback 与数据层生命周期耦合，难以独立测试和取消。

## Scope

- Service callback/lifecycle、Assists window/root、节点遍历、基础 filtering/sanitization 的保留。
- Service-scoped capture coordinator 的创建、scope 和取消。
- Processor/Review persistence/Repository 的业务 handoff。
- 旧 capture path 的同步移除，防止重复入队。
- APK 与授权真机 Accessibility 链路验证。

## Files

- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncAccessibilityService.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncAccessibilityService.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureProcessor.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureProcessor.kt)
- [`apps/android/src/main/java/com/bks/feature/billsync/BillSyncStateCoordinator.kt`](../../../apps/android/src/main/java/com/bks/feature/billsync/BillSyncStateCoordinator.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistence.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistence.kt)
- [`apps/android/src/main/java/com/bks/BksApplication.kt`](../../../apps/android/src/main/java/com/bks/BksApplication.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureCoordinator.kt` 及对应测试。
- 候选新增：`SanitizedBillCapture`，仅在简单 data class 能明确隔离 Android 类型时创建。

## Planned Changes

- Service 从 `BksApplication.appContainer` 获取 `createBillSyncCaptureCoordinator(serviceScope)` factory。
- Service scope 在 `onCreate` 建立，在 `onDestroy` 取消；`onServiceConnected`、`onAccessibilityEvent`、`onUnbind` 等 Android lifecycle callback 保留在 Service。
- Service 保留 Assists window/root 获取、事件窗口选择、自身 package 过滤、成功窗口 suppression/release、`AccessibilityNodeInfo` BFS、可见/密码/可编辑过滤、512 节点、24 层、16KiB 上限、基础 sanitization 和 Android-specific 异常防护。
- Service 生成 sanitized capture 后交给 Coordinator，不创建或 import Database、Repository、Preferences、Review persistence 或 Processor。
- Service-scoped Coordinator 观察 enabled intent，调用 Processor，切换 IO，处理持久化 failure/cancellation；它不是 Application singleton。
- Processor 继续调用 Review persistence/Repository，并遵守 Step 3.3 的 stable-ID/Room transaction 边界。
- 同一步删除旧 in-Service capture/persistence path，不同时保留新旧两条路径。
- Manifest、事件类型、采集范围、上限、日志和隐私规则保持不变。

## State / Ownership Changes

- Android Accessibility callback/window/root/node/sanitization：继续由 Service 持有。
- enabled observation、非 Android-specific pipeline、IO、Processor 调用、persistence failure：Service → Service-scoped `BillSyncCaptureCoordinator`。
- Database/Repository/Review persistence：由 Application Container 提供给 coordinator factory，不由 Service 创建。
- capture session/coroutine：Service lifecycle-scoped，不提升为 Application singleton。

## Before

```text
BillSyncAccessibilityService
→ Android callbacks / roots / traversal / sanitization
→ Database / Repository / Preferences creation
→ Processor / Review persistence / Room IO
```

## After

```text
BillSyncAccessibilityService
→ Android Accessibility adapter
→ sanitized capture
→ BillSyncCaptureCoordinator (Service-scoped)
→ BillSyncCaptureProcessor
→ Review persistence / Repository / Room
```

## Invariants

- Service 要薄但不是空壳，必须保留全部 Android/Assists 平台适配职责。
- 不改变 Manifest、事件类型、采集范围、过滤、512/24/16KiB 上限、日志和隐私权限。
- Service 不 import/create Database、Repository、Preferences、Review persistence 或 Processor。
- 旧、新 capture path 不并行，不重复入队。
- 自动识别仍只写 pending Review；数据一致性由 Repository/Room 保证。
- Xiaomi/MIUI 验证禁止使用 `uiautomator dump`。

## Risks

High。错误 lifecycle 可能漏事件、重复 capture、scope 泄漏或取消正在处理的合法事件；错误迁移也可能扩大采集范围或削弱隐私过滤。

## Tests

- 运行全部 `com.bks.feature.billsync.*` JVM/Robolectric tests。
- 新增 `BillSyncCaptureCoordinatorTest`：enabled/disabled、Processor success/failure、cancel、并发 handoff。
- 验证旧新路径不重复调用、Service destroy 取消 scope、Service adapter limits/filter 保持原样。
- 在用户授权且设备前提满足时，按仓库 `AGENTS.md` 完成 APK/真机 `dumpsys accessibility` 与脱敏 logcat 链路验证。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.*"
.\gradlew.bat :apps:android:detekt
.\gradlew.bat :apps:android:assembleDebug
git diff --check
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat --stop
```

经用户授权的真机验证按仓库 `AGENTS.md` 执行，核心证据包括：

```text
adb devices -l
dumpsys accessibility
脱敏 logcat：event → capture → roots → collected → processed
```

Xiaomi/MIUI 不运行 `uiautomator dump`。

## Stop Conditions

- 需要扩大采集范围、修改事件类型、过滤、上限或隐私规则才能继续。
- Service 被缩成不再拥有 callback/lifecycle/window/root/node traversal/sanitization 的空壳。
- Service 仍创建/import Database、Repository、Preferences、Review persistence 或 Processor。
- 新旧 capture path 并行、出现重复 pending entry 或 lifecycle scope 泄漏。
- Coordinator 被提升为无必要的 Application singleton。
- billsync tests、Debug APK 或授权真机链路失败；真机验证不可用时按批准门禁标记 Blocked，不跳过到 Step 7.1。

## Completion Criteria

- Service 完整保留 Android/Assists adapter 职责并只输出 sanitized capture。
- Database、Repository、Processor 创建、业务 pipeline、IO 和 persistence failure 已迁出 Service。
- `BillSyncCaptureCoordinator` 是 Service-scoped，随 Service lifecycle 创建和取消。
- 新旧 capture path 不并行，采集/隐私规则无变化。
- billsync 专项测试、必要 Detekt、Debug APK、Phase 全量 Android JVM tests 与经授权真机链路通过。
- Execution Record 写明实际真机证据或明确的阻塞状态。

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
