# Step 6.2 — Accessibility Refactor

## Status

Blocked

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

- 新增 Application-level `BillSyncCaptureCoordinator`，由唯一 `BksAppContainer` 持有，并在 `BksApplication` 完成 `AssistsCore.init` 后幂等启动 enabled observer；Service 在先调用 Assists 基类实现后，直接把连接、事件与解绑 callback 委托给 Coordinator，不依赖全局 listener 列表的异常或并发迭代行为。
- Coordinator 对象保持 Application 生命周期，但每次 `onServiceConnected` 创建独立 capture session；`onUnbind`、Service `onDestroy` 或 Coordinator `stop` 都会取消 session、清空窗口抑制记忆并更新连接 health，避免断连后在途持久化或旧窗口状态泄漏到新连接。
- 将事件窗口选择、Assists `AllWindows`/`ActiveWindow` lookup、BKS 自身过滤、`AccessibilityNodeInfo` BFS、基础 sanitization、成功窗口 suppression/release、IO 切换、Processor 调用及 failure/cancellation 收口从 Service 迁入 Coordinator；原函数和 512 节点、24 层、16KiB、六类语义文字、不可见容器遍历规则原样迁移。
- `BillSyncAccessibilityService` 只保留 Android callback/lifecycle adapter：`onServiceConnected`、`onAccessibilityEvent`、`onUnbind`、`onDestroy` 均直接委托 Coordinator；Service 不再创建或 import Database、Repository、Preferences、Review persistence 或 Processor，也不存在并行的旧 capture path。
- 针对真机卡顿反馈，persistence in-flight 期间同一 package/window 的重复事件只保留最新 trigger，不再重复执行 root lookup、最多 512 节点 BFS 和 Parser；persistence 收口后立即补一次最新页面检查，继续保证未识别页面释放、识别恢复继续 suppression、failure 重试和下一笔交易可达。
- Coordinator 的 enabled state 使用可见性保证，session 生命周期操作和 `AcceptedWindowMemory` 全部同步；root lookup 后、handoff 前再次核对 session identity，避免 stop/destroy 与事件并发时把陈旧 suppression 写回已清空 session。
- `BksAppContainer` 继续使用既有单一 Database/Repository graph 创建 `BillSyncCaptureProcessor`，只把 `recognize`/`persist` 操作交给 Coordinator；未修改 Processor、Review Queue persistence、Room schema、Manifest、资源、事件类型、采集范围、日志或隐私权限。
- 新增 `BillSyncCaptureCoordinatorTest`，覆盖 enabled observer 幂等、enabled/disabled、10 个同窗事件合并为初始采集加一次 trailing check、延迟未识别页面释放、Processor failure 后重试、destroy 中断陈旧 handoff、Service destroy 取消在途持久化及新连接恢复；Service health 测试覆盖无显式 unbind 的 destroy 收口和 Assists listener 抛错时直接 callback 仍可达。

### Deviations From Plan

- Step 文档原假设 Coordinator 为 Service 实例创建的 service-scoped 对象；当前实现按 Application composition root 规则保留 Application-level Coordinator，但只把 enabled observer 提升到进程生命周期，实际 capture session 仍随每次 Service 连接创建和取消，因此未实现 `createBillSyncCaptureCoordinator(serviceScope)` factory。
- 初版按目录规则使用 `AssistsService.listeners` 分发 callback；subagent 审查和用户真机卡顿反馈确认全局列表存在异常/并发迭代脆弱性后，Service 改为在 Assists 基类 callback 之后直接委托 Coordinator，并同步更新 `feature/billsync/AGENTS.md`。Service 仍只承担 Android adapter，不重新接管 window/root/traversal、Processor 或 persistence。
- 为保留 Step 的 lifecycle invariant，Application-level 只指 Coordinator 对象与 enabled observer；实际 capture coroutine 和窗口抑制状态仍按每次 Service 连接创建，并在 unbind/destroy 时取消和清空。该形态替代了计划中的“Coordinator 对象本身 service-scoped”，未把 capture session 提升为 Application singleton。
- 未修改 `BillSyncCaptureProcessor`、`BillSyncStateCoordinator` 或 `ReviewQueuePersistence`：当前 Processor 已只负责识别结果到 Review Queue 的持久化，连接 UI health 仍由既有 State Coordinator 消费，继续改动会超出本 Step 的最小必要范围。

### Test Results

- 修改前 `:apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.*"` 基线通过。
- 最终 billsync 专项通过：9 suites / 36 tests，0 failures/errors/skipped；包括 `BillSyncCaptureCoordinatorTest` 6 tests、Service health 3 tests，并继续覆盖 adapter root/filter/limits、handoff suppression/release/cancellation、Processor、Pipeline、Parser、Permission 与 Manifest。
- `:apps:android:detekt` 通过，未新增 suppression；首次检查发现 handoff seam 参数超过阈值，已把 IO 切换收回 Coordinator 调用点后通过。
- `:apps:android:assembleDebug` 通过，生成 `apps/android/build/outputs/apk/debug/android-debug.apk`（80,382,369 bytes）。
- `git diff --check` 通过；新增未跟踪 Kotlin 文件另以 `git diff --no-index --check` 检查，无 whitespace error。
- 修复三项审查问题后，`:apps:android:testDebugUnitTest` 最终整轮通过：545 tests，0 failures/errors/skipped。此前初版第一次全量运行仅 `AccountSessionCoordinatorTest.restoredSessionIsVerifiedAndRepeatedStartDoesNotRepeatWork` 因 Robolectric 主线程残留 runnable 失败；该非 billsync 测试单独重跑和后续完整 suite 均通过，未修改账号模块。
- `adb devices -l` 返回空设备列表。未安装 APK、未修改系统授权、未清理日志、未运行 `uiautomator dump`，因此没有 `dumpsys accessibility` 或同一次 `event → capture → roots → collected → processed` 真机证据。

### Remaining Risks

- 当前没有连接且经用户授权的 Android 真机，无法复测卡顿改善、真实 Service 直接 callback、OEM Accessibility 重连、事件窗口可用性和最终 Review Queue 落库链路；这是本 Step Completion Criteria 与 Stop Condition 的唯一未满足门禁。
- 恢复执行时必须先确认唯一目标设备，再按仓库指南检查 `wm size`、包状态和 `dumpsys accessibility`；经用户授权完成目标完成页复现并取得脱敏的同一次日志链。Xiaomi/MIUI 仍禁止使用 `uiautomator dump`。
- JVM 测试已证明 enabled observer 幂等、capture session 随连接取消、in-flight 事件合并、trailing page check、失败可重试、陈旧 session 不写回且并发不重复持久化，但不能替代厂商系统对 Assists `AllWindows`、绑定/解绑顺序、真机性能和进程回收行为的证据。
- 改动保留在未提交工作区；未 commit、push 或创建分支。Step 7.1 不得开始。

### Final Status

Blocked — 代码、专项测试、Detekt、Debug APK、diff 检查和 Phase 6 Android JVM 全量测试均已完成；因无连接设备，强制真机 Accessibility 链路尚未验证。连接并授权目标真机后从本 Step 的真机门禁继续，不进入 Step 7.1。
