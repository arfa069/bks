# Android 系统性架构重构执行计划

## 重构总体目标

在不改变现有业务行为和外部数据契约的前提下，渐进拆除由 `BksAppRuntimeState`、`BksAppActions`、`BksAppEffects`、`BksAppPresentation` 与 `BksRouteContext` 共同形成的 Application-level God Layer，收敛为：

```text
Application Composition Root
        ↓
Application-level Coordinator（仅真正跨 Feature 的状态）
        ↓
Feature ViewModel / StateHolder
        ↓
Repository / Gateway
        ↓
Room / API / Android System
```

Route 最终只负责 Navigation、ViewModel 获取、lifecycle-aware state collection、平台 callback 和 Screen 装配；Screen 最终主要负责渲染、UI interaction、本地表单状态与平台 launcher/window effect。

## 全局不变量

- 当前代码、测试、当前提示词和实际生效的 `AGENTS.md` 优先于 Plan 中的假设；旧 PRD 不作为本轮重构的事实依据。
- 不修改业务行为。
- 不修改 Room schema。
- 不修改 API、sync protocol 或 backup format。
- 不升级 Lifecycle、Coroutines、Compose、Room 或其他依赖；仅在确实缺少直接需要的 artifact 时添加最小依赖。
- 不扩大 Accessibility 采集范围、事件类型、过滤规则、上限或隐私权限边界。
- 不引入 Hilt、Koin、Gradle feature module 或机械 Clean Architecture。
- 不为了完成计划而强制创建不满足准入条件的 ViewModel、Coordinator、UseCase、Manager 或 Wrapper。
- 唯一 `BksAppContainer` 只代表唯一生产 Composition Root，不代表所有依赖都是 Application singleton；Container 同时持有合理 singleton 并提供 lifecycle-scoped factory。
- 持续状态使用 `StateFlow<UiState>`；one-shot UI effect 按 feature 选择 Channel、SharedFlow 或 callback，登录成功、Session 切换和关键导航必须由稳定 State 驱动。
- Review 正确性依赖 stable ID、Repository 原子操作、Room transaction、数据库约束和并发测试；ViewModel `Mutex` 只作 UI/operation 串行化辅助。
- 自动记账仍必须经过 Review Queue 才能形成已确认账目。
- 未经用户要求，不 commit、push 或创建分支。

## Lifecycle / Coroutine Rules

- Application-scoped runtime / coordinator 的启动必须幂等。
- 长生命周期 coroutine 不得依附 Compose scope、rememberCoroutineScope 或 Screen/Route scope。
- Composition recreation、Activity recreation 不得导致重复 collect、重复网络验证、重复 WorkManager 调度或重复 listener 注册。
- Application-scoped runtime 必须有明确的 start/stop 或一次性启动语义，并由测试证明重复调用安全。

## Phase / Step 列表

状态取值：`Pending`、`In Progress`、`Completed`、`Blocked`。

### Phase 0 — Baseline / Characterization

| Step | Title | Status |
|---|---|---|
| 0.1 | [Baseline](./phase-0/step-0.1-baseline.md) | Completed |

### Phase 1 — Application Composition Root

| Step | Title | Status |
|---|---|---|
| 1.1 | [Application Container](./phase-1/step-1.1-app-container.md) | Completed |

### Phase 2 — Account / Cloud Settings / Sync Runtime

| Step | Title | Status |
|---|---|---|
| 2.1 | [Account Session](./phase-2/step-2.1-account-session.md) | Completed |
| 2.2 | [Cloud Settings](./phase-2/step-2.2-cloud-settings.md) | Completed |
| 2.3 | [Sync Runtime](./phase-2/step-2.3-sync-runtime.md) | Completed |

### Phase 3 — Ledger / Categorization / Review / Automatic Bookkeeping

| Step | Title | Status |
|---|---|---|
| 3.1 | [Ledger](./phase-3/step-3.1-ledger.md) | Completed |
| 3.2 | [Categorization](./phase-3/step-3.2-categorization.md) | Completed |
| 3.3 | [Review](./phase-3/step-3.3-review.md) | Completed |
| 3.4 | [Automatic Bookkeeping](./phase-3/step-3.4-auto-bookkeeping.md) | Completed |

### Phase 4 — Account UI / Backup / Diagnostics

| Step | Title | Status |
|---|---|---|
| 4.1 | [Account Entry](./phase-4/step-4.1-account-entry.md) | Completed |
| 4.2 | [Account Management](./phase-4/step-4.2-account-management.md) | Completed |
| 4.3 | [Backup](./phase-4/step-4.3-backup.md) | Completed |
| 4.4 | [Diagnostics](./phase-4/step-4.4-diagnostics.md) | Completed |

### Phase 5 — Route / Screen / Feature Boundary Convergence

| Step | Title | Status |
|---|---|---|
| 5.1 | [Route](./phase-5/step-5.1-route.md) | Blocked |
| 5.2 | [Screen Boundaries](./phase-5/step-5.2-screen-boundaries.md) | Pending |

### Phase 6 — Accessibility Boundary Refactor

| Step | Title | Status |
|---|---|---|
| 6.1 | [Accessibility Characterization](./phase-6/step-6.1-accessibility-characterization.md) | Pending |
| 6.2 | [Accessibility Refactor](./phase-6/step-6.2-accessibility-refactor.md) | Pending |

### Phase 7 — Legacy God Layer Removal / Final Validation

| Step | Title | Status |
|---|---|---|
| 7.1 | [Legacy Cleanup](./phase-7/step-7.1-legacy-cleanup.md) | Pending |

## 执行规则

- 一次只执行一个 Step。
- 当前 Step 未达到 Completion Criteria 并写回 Execution Record 前，不得提前执行后续 Step。
- 每个 Step 先运行最相关的专项测试，再按实际改动运行必要 Detekt，并运行 `git diff --check`。
- 仅涉及 Manifest、Resources、Gradle dependency、BuildConfig、Android platform configuration 或确需 APK 验证的 Step 运行 `assembleDebug`。
- 每个 Phase 完成后运行 `:apps:android:testDebugUnitTest`；整轮完成后再运行完整 tests、Detekt、`assembleDebug` 和 `build`。
- 命中 Stop Conditions 时停止当前 Step，保留可回滚桥接并将状态写为 `Blocked`，不得用扩大范围绕过。

## AGENTS.md 优先级说明

执行某个 Step 前必须重新读取并遵循该文件路径实际适用的全部 `AGENTS.md`。优先级为：用户在当前对话中的最新明确指令 > 安全红线 > 当前文件路径下最具体的仓库级 `AGENTS.md` > 上级仓库级 `AGENTS.md` > 全局 `AGENTS.md` > 本 Plan 的历史假设。若当前代码、测试或规则与 Step 描述不一致，以当前事实为准，并把偏差写入该 Step 的 Execution Record。
