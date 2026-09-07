# 自动记账项目文档索引 (bks Docs)

> 当前产品基线已移除微信/支付宝专用解析、通知监听、手动补录和 OCR，改为基于 Assists 的通用活动窗口识别。下方旧 ADR、Phase 2 Issue、研究和审计材料可能保留当时的能力描述，均按历史快照阅读，不代表当前实现。
> 当前产品基线已移除微信/支付宝专用解析、通知监听、手动补录和 OCR，改为基于 Assists 的通用活动窗口识别。下方切片规划与研究材料可能保留当时的能力描述，均按历史快照阅读，不代表当前实现。

从这里开始阅读：

- [产品需求文档 (PRD)](./PRD.md)
- [系统架构设计草案](./ARCHITECTURE.md)
- [系统架构设计 (Current Architecture)](./ARCHITECTURE.md)
- [Android 系统性架构重构执行计划](./refactor/README.md)
- [Android 职责与命名重构审查及方案（2026-09-05，阶段 1-5 已完成）](./refactor/android-responsibility-refactor-plan-2026-09-05.md)
- [UI 设计规范](./UI-DESIGN.md)
- [合规与隐私评估草案](./COMPLIANCE.md)
- [开发切片规划 (Development Slices)](./DEVELOPMENT-SLICES.md)
- [阶段 2 切片规划 (Phase 2 Slices)](./PHASE-2-SLICES.md)
- [阶段 2 基线审计 (Phase 2 Baseline Audit)](./PHASE-2-BASELINE-AUDIT.md)
- [代码质量与性能审计报告 (2026-08-13)](./CODE-QUALITY-AUDIT-2026-08-13.md)
- [阶段 2 Issue 规划 (Phase 2 Issues)](./PHASE-2-ISSUES.md)
- [阶段 2 Issue 跟踪文件](./issues/phase-2/)
- [当前未完成事项](../todos.md)
- [内测发布与 QA 验证](./INTERNAL-BETA-RELEASE.md)
- [通用交易识别规则](./PAYMENT-RECOGNITION-RULES.md)
- [诊断日志操作手册](./DIAGNOSTIC-LOGS.md)
- [Termux 局域网内测部署](./TERMUX-DEPLOYMENT.md)
- [诊断日志操作手册](./DIAGNOSTIC-LOGS.md)
- [Android 性能审计与 Trace 分析](./performance-audit/)
- [当前未完成事项](./todos.md)
- [领域语言与术语表](../CONTEXT.md)
- [通用交易识别规则](./PAYMENT-RECOGNITION-RULES.md)
- [阶段 2 切片规划 (Phase 2 Slices 历史快照)](./PHASE-2-SLICES.md)
- [自动记账流程研究（2026-07-13 至 2026-07-17 历史快照）](./research/auto-bookkeeping-flow.md)
- [架构决策记录 (ADR)](./adr/)
- [K80 高刷渲染与锁帧排查记录](./手机K80无法锁定120hz刷新率WalkThoughts.md)

ADR 记录了文档对齐研讨过程中的关键技术与产品决策路径。PRD 及相关支撑文档描述了当前产品的基线；Phase 2 基线审计、切片规划、Issue 规划、研究和性能审计作为历史快照保留，当前实现以代码和现行 PRD/架构/UI/合规文档为准，跨 Issue 的外部阻断项汇总在根目录 `todos.md`。
PRD、架构设计、UI 规范与合规评估描述了当前产品的基线；重构执行计划、切片规划与历史研究文档作为开发与技术演进的追溯记录；外部阻断项汇总在 `docs/todos.md`。
