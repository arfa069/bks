# Step 3.1 — Ledger

## Status

Completed

## Goal

将 Ledger、Reports、Manual Entry、账本和资金账户 CRUD 收归同一个自然 `LedgerViewModel` owner，使 Room Flow 直接进入 Feature presentation，并清除 Route 的 Repository/Cloud 操作。

## Current Problem

Ledger 数据和操作目前经 Root runtime/presentation 镜像进入多个 Route 与 Screen；Route 直接访问 Repository 或 Cloud settings，Screen 还可能接收 Room Entity。同一领域的多个页面没有统一 lifecycle-aware owner。

## Scope

- Ledger entries、books、funding accounts 的多个 Room Flow 和 CRUD。
- Ledger list、manual entry、management、reports 的共享 presentation state。
- Entity → UI model 映射。
- 默认资金账户本地写入后对 Step 2.2 Cloud Settings Coordinator 的调用。
- 不单独创建 Reports ViewModel。

## Files

- [`apps/android/src/main/java/com/bks/BksLedgerRoutes.kt`](../../../apps/android/src/main/java/com/bks/BksLedgerRoutes.kt)
- [`apps/android/src/main/java/com/bks/feature/ledger/LedgerScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/ledger/LedgerScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/ledger/LedgerScreenModels.kt`](../../../apps/android/src/main/java/com/bks/feature/ledger/LedgerScreenModels.kt)
- [`apps/android/src/main/java/com/bks/feature/ledger/LedgerManagementScreens.kt`](../../../apps/android/src/main/java/com/bks/feature/ledger/LedgerManagementScreens.kt)
- [`apps/android/src/main/java/com/bks/feature/ledger/LedgerEntryEditorScreens.kt`](../../../apps/android/src/main/java/com/bks/feature/ledger/LedgerEntryEditorScreens.kt)
- [`apps/android/src/main/java/com/bks/feature/ledger/ReportsScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/ledger/ReportsScreen.kt)
- [`apps/android/src/main/java/com/bks/data/local/LocalLedgerRepository.kt`](../../../apps/android/src/main/java/com/bks/data/local/LocalLedgerRepository.kt)
- 计划新增：`apps/android/src/main/java/com/bks/feature/ledger/LedgerViewModel.kt` 及对应测试。

## Planned Changes

- 创建 `LedgerViewModel`；多个 Room Flow、多个 CRUD 和多个 Screen 消费者满足独立 owner 准入条件。
- ViewModel 直接观察现有领域 Repository Flow，并在 feature 内映射为 UI model。
- Ledger list、manual entry、book/funding management 和 Reports 复用该 owner；Reports 只使用派生 state，不创建单独 ViewModel。
- 默认资金账户先完成本地写入，再调用 `AccountCloudSettingsCoordinator`，保持本地成功/云失败语义。
- Route 删除 Repository/Cloud write 和业务 coroutine，只获取 owner、collect state、传递 action。
- Screen 保留表单草稿、Dialog、列表滚动和其他纯 UI 状态。

## State / Ownership Changes

- Ledger entries/books/funding accounts 的 UI projection：Root runtime/presentation → `LedgerViewModel.StateFlow`。
- 持久数据 source of truth：继续是 Room + 现有领域 Repository。
- Reports：由 Ledger state 派生，不建立第二 owner。
- 表单草稿与 Dialog：继续由 Screen/Composition 持有。

## Before

```text
Room / Repository Flow
→ BksAppRuntimeState copy
→ BksAppPresentation / Route callbacks
→ Ledger Screens
```

## After

```text
Room / LocalLedgerRepository
→ LedgerViewModel
→ LedgerUiState + Actions
→ Ledger / Entry / Management / Reports Screens
```

## Invariants

- Room/Repository 仍是唯一持久 source of truth。
- 本地 ledger 写入与 sync outbox 事务语义不变。
- 默认资金账户保持本地成功优先、云失败不回滚。
- 不修改 Room schema、sync protocol、backup format 或账务行为。
- 不创建 Reports VM、无状态 UseCase/Manager 或只做 Repository 转发的 wrapper。

## Risks

High。错误映射或写入顺序可能导致列表/报表不一致、默认资金账户语义变化，或漏写 sync outbox。

## Tests

- 新增 `LedgerViewModelTest`：多 Flow 聚合、CRUD、空值、错误和账户切换。
- 复用 Ledger entry/management/list/report 和 Local Repository 测试。
- 复用 `LedgerSyncOutboxTest` 证明重构未改变本地写入/outbox 原子性。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.ledger.LedgerViewModelTest" --tests "com.bks.data.local.LocalLedgerEntryRepositoryTest" --tests "com.bks.data.local.LocalLedgerManagementRepositoryTest" --tests "com.bks.feature.ledger.LedgerListScreenTest" --tests "com.bks.feature.ledger.LedgerManagementScreenTest" --tests "com.bks.feature.ledger.LedgerEntryEditorScreenTest" --tests "com.bks.feature.ledger.LedgerReportQueriesTest" --tests "com.bks.feature.sync.LedgerSyncOutboxTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- Route 仍需直接执行 Repository、Cloud 或 sync 操作。
- Screen 仍以 Room Entity 作为 presentation contract，且无法在 feature owner 映射。
- 默认资金账户本地成功/云失败或 outbox 行为发生变化。
- 需要修改 Room schema、sync protocol、backup format 或创建额外微型 owner 才能继续。
- 专项测试失败且根因未解决。

## Completion Criteria

- `LedgerViewModel` 是 Ledger/Reports/Manual Entry/management 的自然 presentation owner。
- Room Flow 直接进入 ViewModel，不再经 Root 镜像。
- Ledger Route 不执行 Repository、Cloud 或 sync 业务。
- Screen 接收 UI model/action，不接收 Room Entity。
- Reports 使用 Ledger 派生 state，没有独立微型 VM。
- 专项测试、必要 Detekt 与 diff 检查通过。

---

## Execution Record

### Actual Changes

- 新增 lifecycle-scoped `LedgerViewModel` 与稳定 `LedgerUiState`：直接收集现有 `LocalLedgerRepository.state`，在 feature 内完成 Ledger entry、category、funding account、ledger book 的 Entity → UI model 映射，并从同一 active-ledger entry projection 派生 Reports state；没有创建独立 Reports ViewModel。
- `LedgerViewModel` 统一承接默认账本/系统分类初始化、最近删除清理、Manual Entry、账目编辑/软删除/恢复/永久删除、账本 CRUD/切换、资金账户 CRUD，以及默认资金账户 Coordinator 委托。Repository/Room 继续负责持久化与 outbox transaction，ViewModel 没有复制第二份持久状态或增加 `Mutex`。
- `BksApp` 按 Phase 2 的现有模式创建该 lifecycle owner，并以 `collectAsStateWithLifecycle()` 收集稳定 state；`BksRouteContext` 向 Ledger、Reports、Manual Entry、Review 和 Data/Backup 消费者提供同一 owner/state。Root `BksAppRuntimeState`、`BksAppPresentation` 与 `BksAppEffects` 中的 Ledger repository 镜像和初始化业务 Effect 已删除。
- `BksLedgerRoute` 与 Manual Entry Route 不再访问 Ledger Repository 或 Cloud Coordinator，只装配 UI state、ViewModel action 与现有 navigation/snackbar callback。默认资金账户仍由 Step 2.2 Coordinator 先本地写入再尝试云端；云失败继续显示“默认账户已保存到本机，云端同步失败”。
- Ledger entry editor、账本/资金账户管理和 Review 的共享分类/资金账户输入改为 `LedgerCategoryUiModel` / `FundingAccountUiModel`，Screen presentation contract 不再接收 `CategoryEntity` 或 `FundingAccountEntity`。Review command、persistence、undo 和 concurrency 业务未改，仍留给 Step 3.3。
- 新增 `LedgerViewModelTest` 7 个用例，覆盖多 Flow 聚合、Reports 派生、entry/book/funding CRUD、空初始态、typed error、默认资金账户本地成功/云失败、账户数据替换后旧 projection 不残留，以及本机数据替换完成前拒绝新写入；同时收紧既有 `MainActivityTest.reportsFollowTheSelectedLedgerBook` 的等待条件，使其等待真实 active-ledger UI 后置状态。
- Review follow-up 修复了删除本机数据的异步窗口：`LedgerViewModel` 在 replacement 期间输出带 busy 标记的空 projection，并拒绝新的 Ledger/Manual Entry 写入；Review confirmation 在同一 gate 下拒绝持久化。删除 action 改为可等待且在调用方取消时继续完成，Data/Backup 确认 Dialog 在 Room 清理后的 projection 就绪前不可关闭、重复确认或修改输入，不再依赖已删除的 Root ledger mirror 充当安全门。

### Deviations From Plan

- 未修改候选文件 `LocalLedgerRepository.kt`：当前 repository 已提供 active book、entries、deleted entries、categories、funding accounts、book counts 与 default funding account 的组合 Flow，并由细分 Repository 保持 CRUD/outbox 事务语义；为本 Step 再拆 Flow 或创建 wrapper 会增加第二套投影边界。
- 未修改 `ReportsScreen.kt` 或创建 Reports ViewModel：该 Screen 已支持接收预先派生的 `LedgerReportUiModel`，生产 Route 现在直接传入 `LedgerUiState.report`；保留既有 `entries` fallback 供独立 Screen 测试/调用，不影响生产 owner 唯一性。
- 为迁移实际消费者，最小修改了候选清单外的 `BksApp.kt`、`BksAppRuntime.kt`、`BksAppEffectsContext.kt`、`BksRouteContext.kt`、`BksRouteHost.kt`、`BksProfileRoutes.kt`、Review presentation 文件及对应测试；没有进入 Step 3.2/3.3 的分类规则、Review command/persistence 或并发正确性改造。
- `LedgerViewModel` 的 13 个既定 CRUD/action 加 1 个本机数据 replacement action 均属于同一 Ledger 持久状态边界；为避免拆出只做 Repository 转发的无状态 wrapper，使用与现有 Room repository 相同的局部 `TooManyFunctions` suppression，没有新增 Manager/UseCase/额外 owner。

### Test Results

- 变更前基线专项（除尚不存在的 `LedgerViewModelTest` 外的 Step 指定 7 类）：通过。
- Step 指定专项命令：follow-up 后通过；其中 `LedgerViewModelTest` 7 tests、`LedgerSyncOutboxTest` 4 tests 全部通过。专项命令同时加入 `DataAndBackupScreenTest`，其 10 tests 全部通过，覆盖删除回调未完成时 Dialog 保持锁定。
- 受 UI model 契约影响的补充回归（`LedgerManagementScreenTest`、`LedgerEntryEditorScreenTest`、`ReviewQueueEditorTest`、`ReviewQueueAiSuggestionTest`）：通过，4 suites、32 tests、0 failures。
- `:apps:android:detekt`：通过，0 issues。首轮报告 `BksApp` LongMethod 和 `LedgerViewModel` TooManyFunctions；前者提取局部 lifecycle factory 后通过，后者按上述单 owner 原因使用局部 suppression。
- `:apps:android:testDebugUnitTest`：follow-up 后实际执行并通过，81 suites、444 tests、0 failures、0 errors、0 skipped。首次实施时既有 `MainActivityTest.reportsFollowTheSelectedLedgerBook` 曾在 Repository state 已切换但 Compose projection 尚未显示时过早断言一次失败；改为等待“旅行账本”真实显示后，该用例及后续完整测试均通过，业务断言未放宽。
- `git diff --check`：通过。

### Remaining Risks

- 未运行 instrumentation、真机、真实后端或 `assembleDebug`：本 Step 未修改 Manifest、Resource、Gradle dependency、Room schema、API、sync protocol、backup format 或发布配置；当前证据覆盖 Room/Repository、outbox、Compose/Robolectric 与完整 Android JVM 测试。
- Ledger owner 仍通过当前 Activity 的 `ViewModelStore` 存活并直接观察 Application container 中的 Room Repository；Activity recreation 由 ViewModel lifecycle 保持，进程死亡后的恢复仍依赖 Room 重新发射，未做单独设备端进程死亡验收。
- Review 目前只消费 Ledger owner 提供的分类/资金账户/active-book UI projection；其持久命令、并发 capture、undo 与 optimistic state 仍是既有实现，按计划由 Step 3.3 独立处理。
- `selectLedgerBook()` 的 Room transaction 返回与 active-ledger projection 发射之间仍可能存在短暂窗口；旧 Root mirror 方案已有相同时序，本 Step 未扩大范围修复该既有问题，也未增加“切换后不等待 projection 立即写入”的确定性回归测试。

### Final Status

Completed
