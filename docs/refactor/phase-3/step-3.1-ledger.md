# Step 3.1 — Ledger

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
