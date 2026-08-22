# Step 3.3 — Review

## Status

Pending

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

尚未执行

### Deviations From Plan

无

### Test Results

尚未执行

### Remaining Risks

尚未执行

### Final Status

Pending
