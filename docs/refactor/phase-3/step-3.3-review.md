# Step 3.3 — Review

## Status

Completed

## Goal

以显式 `ReviewPersistenceCommand` 替换 UI state diff，并将 Confirm、Undo 和并发 capture 的正确性完整落到 stable ID、Repository 原子操作与 Room transaction。

## Current Problem

现有 `persistTransition(previous, next)` 通过前后 UI list/state 差异推断持久化命令，容易在 stale action 与并发 Accessibility capture 下覆盖不相关记录。若只在 ViewModel 加 `Mutex`，数据正确性仍依赖 UI lifecycle，无法保护 Service 并发写入。

## Scope

- 显式 command：`Edit`、`Confirm`、`Ignore`、`Recover`、`UndoConfirm`、`UndoIgnore`。
- `ReviewQueueViewModel` 的 Room projection、operation state、optimistic state 与 undo metadata。
- Repository/Room 层的 stable-ID 原子转换和 typed result。
- concurrent accessibility insert + stale UI action。
- 不修改 Room schema；约束不足时用事务内检查和 typed result。

## Files

- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistence.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistence.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistenceMappers.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueuePersistenceMappers.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueueState.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueueState.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueueScreen.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueueScreen.kt)
- [`apps/android/src/main/java/com/bks/feature/review/ReviewQueueListActions.kt`](../../../apps/android/src/main/java/com/bks/feature/review/ReviewQueueListActions.kt)
- [`apps/android/src/main/java/com/bks/data/local/LocalLedgerRepository.kt`](../../../apps/android/src/main/java/com/bks/data/local/LocalLedgerRepository.kt)
- [`apps/android/src/main/java/com/bks/data/local/LedgerEntryRepository.kt`](../../../apps/android/src/main/java/com/bks/data/local/LedgerEntryRepository.kt)
- [`apps/android/src/main/java/com/bks/data/local/LedgerDaos.kt`](../../../apps/android/src/main/java/com/bks/data/local/LedgerDaos.kt)
- 计划新增：`ReviewPersistenceCommand.kt`、`ReviewQueueViewModel.kt` 及对应测试。

## Planned Changes

- 创建带 stable ID 的 `ReviewPersistenceCommand` 六种显式命令。
- 创建 `ReviewQueueViewModel`，负责 Room projection、UI operation/optimistic state、undo metadata 和错误恢复。
- 删除 `persistTransition(previous, next)` 和整份 UI list 覆盖式持久化。
- Repository 为 Confirm、UndoConfirm 等提供原子操作与 typed result：`NotFound`、`AlreadyApplied`、`Conflict`。
- Confirm 在同一 Room transaction 内读取目标 pending、应用最新编辑、创建 ledger、删除 pending 并写 sync outbox。
- 实现 UndoConfirm 前必须检查当前 Review/Ledger Entity、DAO、Repository 和相关测试，以代码与测试证据确认恢复 pending 的真实数据来源；不得假设系统保存了完整原 pending snapshot。
- 只有确认现有模型能够提供正确恢复所需的数据后，UndoConfirm 才在同一 transaction 内从该真实来源重建 pending、定位对应 ledger、软删除 ledger 并写对应 outbox。
- 不得为了满足 Plan 静默修改 Room schema；如果现有模型不足以正确恢复 pending，立即停止本 Step 并报告缺失的数据来源与影响。
- ViewModel `Mutex` 只防 UI 重复点击；typed conflict 触发 Room reload，不成为正确性基础。
- 新 capture 只插入自己的 stable ID；Review command 只修改目标 ID。

## State / Ownership Changes

- Review UI projection、operation state 和 undo metadata：Root runtime/Screen → `ReviewQueueViewModel`。
- 持久 pending/confirmed/ignored state：继续由 Room/Repository 唯一持有。
- 数据一致性 owner：ViewModel list diff/Mutex → Repository atomic operation + Room transaction。

## Before

```text
previous UI state + next UI state
→ persistTransition diff
→ multiple Repository calls

Accessibility capture
→ concurrent Room insert
→ stale UI list may overwrite unrelated rows
```

## After

```text
Screen Action
→ ReviewPersistenceCommand(stable ID)
→ Repository atomic operation
→ Room transaction + constraints/checks
→ Room Flow reload

Accessibility capture
→ insert own stable ID only
```

## Invariants

- 自动识别候选必须先进入 pending Review，不能绕过 Review 形成 confirmed ledger。
- Confirm/UndoConfirm 与 sync outbox 保持单一 Room transaction。
- ViewModel `Mutex` 不是正确性前提。
- 并发 capture 与 UI command 不跨 ID 修改或整表覆盖。
- 不修改 Room schema；若现有约束不足，停止并申请独立迁移任务，而非静默改变 schema。

## Risks

High。错误事务可能丢 pending、重复 ledger、漏 outbox，或在 concurrent capture 时覆盖新记录。

## Tests

- 新增 `ReviewQueueViewModelTest`，覆盖 operation state、optimistic state、undo metadata、typed conflict 与 Room reload。
- 扩展 `ReviewQueuePersistenceTest` 使用 in-memory Room 证明原子性和并发安全。
- 必须覆盖 concurrent accessibility insert + stale confirm/ignore、edit+confirm、undo confirmed、persist failure rollback/reload。
- UndoConfirm 测试必须先依据当前 Entity、DAO、Repository 和既有测试确认的数据来源构造场景，并验证恢复后的 pending 字段确实来自该来源；不得用测试 fixture 虚构完整原 pending snapshot。
- 如果现有模型无法支持正确恢复，测试应暴露这一事实并阻断本 Step，而不是通过新增 schema 字段或降低断言让计划继续。
- 即使测试中不依赖 ViewModel `Mutex`，Repository 并发测试也必须不丢、不重、不跨 ID。

## Validation Commands

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.review.ReviewQueueViewModelTest" --tests "com.bks.feature.review.ReviewQueuePersistenceTest" --tests "com.bks.feature.review.ReviewQueueStateTest" --tests "com.bks.feature.review.ReviewQueueContentTest" --tests "com.bks.feature.review.ReviewQueueEditorTest" --tests "com.bks.feature.review.ReviewQueueGestureTest" --tests "com.bks.feature.review.ReviewQueueAiSuggestionTest" --tests "com.bks.data.local.LocalLedgerEntryRepositoryTest"
.\gradlew.bat :apps:android:detekt
git diff --check
```

## Stop Conditions

- Review 正确性只能依靠 ViewModel `Mutex` 或 UI list serialization。
- 当前 Entity、DAO、Repository 和测试无法证明存在足以正确恢复 pending 的真实数据来源。
- UndoConfirm 实现依赖未经代码证据确认的完整原 pending snapshot 假设。
- Confirm/UndoConfirm/outbox 无法在现有 Room schema 下实现原子事务。
- 继续需要静默修改 Room schema、用整份 list 覆盖 Room 或改变 Review 业务门槛。
- concurrent capture + stale action 测试出现丢失、重复或跨 ID 修改。
- typed result 不能安全触发 Room reload。
- 专项测试失败且根因未解决。

## Completion Criteria

- 六种持久转换都使用显式 `ReviewPersistenceCommand` 和 stable ID。
- `persistTransition(previous, next)` 已删除。
- Confirm、UndoConfirm 与 outbox 在 Repository/Room 层原子执行。
- ViewModel 只负责 UI projection/operation，`Mutex` 不参与正确性证明。
- concurrent capture、stale action、事务失败和 undo 的 Room 测试通过。
- Room schema 未变化，必要 Detekt 与 diff 检查通过。

---

## Execution Record

### Actual Changes

- 新增六种带 stable ID 的 `ReviewPersistenceCommand`：`Edit`、`Confirm`、`Ignore`、`Recover`、`UndoConfirm`、`UndoIgnore`；删除 `ReviewQueueAction`、`reduceReviewQueue`、`persistTransition(previous, next)`、UI `confirmedEntries` diff 和 Root `reviewState` / `reviewTransitionInFlight` mirror。
- 新增 lifecycle-scoped `ReviewQueueViewModel`：直接收集 Room projection，持有 operation、target-scoped optimistic change、真实 undo receipt 和 one-shot notice；typed `NotFound` / `AlreadyApplied` / `Conflict` 与持久化异常都会清除 optimistic state 并 reload。ViewModel `Mutex` 只拒绝 UI 重复操作，不参与 Repository 正确性。
- `LocalLedgerRepository` 新增 Review typed mutation result 及 stable-ID transaction：Confirm 在同一 Room transaction 内读取目标 pending、应用最新 edit、创建 ledger、删除 pending 并写 sync outbox；UndoConfirm 使用 Confirm 返回的完整真实 `PendingEntryEntity` snapshot 恢复 pending、校验 receipt 对应的唯一 active ledger 与完整 ledger snapshot、软删除 ledger 并更新 outbox。
- Review 生命周期检查只把 active ledger 视为当前 confirmed 状态；UndoConfirm 留下的软删除历史不会阻止恢复后的 Edit/Ignore/再次 Confirm。重复 Undo 返回 `AlreadyApplied`，旧 receipt 面对新的 active ledger 返回 `Conflict`。
- `PendingEntryDao.insertIgnore` 与 capture 按 stable ID 逐项插入替代整份 UI list 覆盖；`insertPendingIfAbsent` 在同一 Room transaction 内同时检查 pending、active ledger 与 ignored 生命周期，Confirm/Ignore 后迟到的同 ID capture 不会重建 pending，UndoConfirm 留下的 soft-deleted ledger 则不阻止合法恢复。并发 Accessibility capture 只写自己的 ID，stale Review command 只修改目标 ID。Room schema、Entity、API、sync protocol、backup format 和依赖均未改变。
- typed terminal result 按预期 command 区分同向幂等和反向冲突：重复 Confirm/Ignore 可返回 `AlreadyApplied`，Confirm-after-Ignore、Ignore-after-Confirm、Edit-after-terminal 与重复/畸形生命周期返回 `Conflict`；concurrent Confirm-vs-Ignore 最终恰有一个 `Applied`、一个 `Conflict` 和一种 terminal lifecycle。
- `BksApp` 改为创建并 lifecycle-aware 收集 Review owner；Route/Screen 只转发 command、undo、dismiss，Screen 继续只保存 editor/dialog 等纯 UI 状态。Review 成功后的显式 Room reload 返回后会再次校验 replacement generation，旧 receipt 不会从第二个 suspension point 重新发布 undo。
- `LedgerViewModel.runLocalDataReplacement` 统一拥有 clear/import replacement gate；替换开始后拒绝新的 Ledger/Review command，并等待已经持有 Review operation mutex 的命令完成和清除 transient state 后才执行 clear/import。备份格式与 import transaction 未改变。
- 新增 `ReviewQueueViewModelTest` 与 test-only `ReviewQueueScreenTestHarness`，扩展 in-memory Room persistence、Ledger gate、Screen gesture/editor、capture processor 和 Root 回归，覆盖 optimistic/operation、真实 snapshot undo、typed reload、事务回滚、并发 capture、迟到同 ID capture、反向终态冲突、重复 Confirm、软删除历史后的再次 Confirm/Undo、第二次 reload suspension 及整库替换时序。

### Deviations From Plan

- 当前 `PendingEntryEntity` 已包含 confidence、capture reason、captured-at、建议分类 label、资金账户 label、note 和 evidence 等恢复所需字段；Confirm transaction 返回应用最新 edit 后的真实 pending snapshot，因此没有命中 UndoConfirm Stop Condition，也没有修改 Room schema。
- 未修改候选文件 `ReviewQueuePersistenceMappers.kt`：既有 Entity/UI mapper 已能无损映射当前字段。未把 Review transaction 放入 `LedgerEntryRepository.kt`：该接口只拥有 ledger entry CRUD，涉及 pending/ignored/ledger/outbox 的跨表原子转换保留在现有 `LocalLedgerRepository` facade 是当前最窄正确 owner。
- 保留 `confirmPending`、`ignorePending`、`recoverIgnored` compatibility API 供既有非 Review consumer/测试使用，但其实现已委托 typed transaction；生产 Review 路径只使用显式 command。
- 为迁移真实 owner 与 consumer，最小修改了候选清单外的 `BksApp*`、Route、`LedgerViewModel`、`BillSyncCaptureProcessor`、backup 回调和对应测试。复审确认原先仅在 import 完成后失效 undo 仍存在排队 Review transaction 跨数据集执行的窗口，因此按用户要求在本 Step 将既有 clear gate 提取为 clear/import 共用 owner；没有进入 Phase 4 Backup 的格式/Repository 重构，也没有进入 Step 3.4 的 Automatic Bookkeeping/Accessibility service 架构重构或修改其他 Step 文档。
- UI gesture/snackbar 单测使用 `src/test` 下的轻量 harness 保留既有纯交互行为；它不参与生产持久化，也不作为 Repository 并发正确性证据。并发与原子性证据来自 in-memory Room tests。

### Test Results

- 变更前 Step 专项基线执行时，`ReviewQueueAiSuggestionTest` 首次因 Robolectric 创建 `C:\Users\Arfa\.robolectric-download-lock` 遇到 Windows `AccessDeniedException`；该 suite 单独复跑通过，确认是本机文件锁抖动，不是业务失败。
- subagent 复审确认 0 个 P1、3 个 P2：terminal 后同 ID capture 可重建 pending、成功 reload 的第二个 suspension point 缺少 generation 复查、typed result 把相反 terminal state 误判为 `AlreadyApplied`；同时指出 backup import 未复用 clear replacement gate。上述问题均已修复并加入直接回归。
- follow-up 最窄 3 suites 首轮共 28 tests，仅新增的 replacement preparation 测试因在 `StateFlow.combine` 传播前立即读取旧 `InFlight` 而失败；测试改为等待公开 `uiState` 收敛到 `Idle` 后复跑，28 tests 全部通过，生产实现未因该测试时序调整。
- 最终 Step 指定专项命令通过：`ReviewQueueViewModelTest`、`ReviewQueuePersistenceTest`、`ReviewQueueStateTest`、`ReviewQueueContentTest`、`ReviewQueueEditorTest`、`ReviewQueueGestureTest`、`ReviewQueueAiSuggestionTest`、`LocalLedgerEntryRepositoryTest`，共 8 suites、65 tests、0 failures、0 errors、0 skipped。
- 直接受影响补充回归通过：`LedgerViewModelTest`、`BillSyncCaptureProcessorTest`、`LocalDataBackupRepositoryTest`、`DataAndBackupScreenTest`、`MainActivityTest`，共 5 suites、51 tests、0 failures、0 errors、0 skipped。
- `:apps:android:testDebugUnitTest` 最终通过：83 suites、450 tests、0 failures、0 errors、0 skipped。
- `:apps:android:detekt` 最终通过，0 issues。首次在补强 UndoConfirm lifecycle 检查后报告一个 `CyclomaticComplexMethod`；提取同 owner 的 typed completed-undo 判定后复跑通过，没有新增 suppression；follow-up 修复后复跑仍为 0 issues。
- `git diff --check` 通过；生产代码搜索确认 `persistTransition`、`ReviewQueueAction`、`reduceReviewQueue`、`confirmedEntries`、`reviewTransitionInFlight`、`reviewState` 均无残留；`apps/android/schemas` 无 diff。

### Remaining Risks

- `ledger_entries.origin_pending_entry_id` 仍没有数据库 unique/partial-unique 约束；本 Step 按禁止 schema 变更的边界，以 Room transaction 内的 active-origin 列表检查和 typed `Conflict` 防止本地 Review command 产生重复。若旧备份或外部同步已经包含畸形重复生命周期数据，当前操作会拒绝继续而不会自动修复。
- Repository 成功后 ViewModel 会显式 reload，同时持续收集 Room Flow；若该次显式 reload 本身异常，optimistic state 会被清除，页面可能短暂显示上一次 projection，直到 Room Flow 下一次发射。本 Step 没有新增第二份持久真相。
- Clear 与 backup import 现已共用 Ledger lifecycle-scoped replacement gate，可阻止或等待当前 UI feature writer；Accessibility capture 仍按架构直接写 Repository 而不依赖 ViewModel gate，并会与 import transaction 在 Room 串行。Repository 的三生命周期检查可阻止导入 terminal stable ID 后重建同 ID pending，但当前 schema 没有跨整库 replacement generation，因此无法在数据库层标记 capture 属于哪一代数据集。
- 未运行 instrumentation、真机、`assembleDebug` 或真实 Accessibility capture：本 Step 未修改 Manifest、Resources、依赖或 Accessibility 事件/权限边界；当前证据覆盖 Room/Repository transaction、Robolectric/Compose UI 与完整 Android JVM 回归。

### Final Status

Completed
