# Android ViewModel 层指南

## 职责与依赖

- 保存 AndroidX ViewModel、`UiState`、`UiModel`、UI command/result、reducer 及 Domain → UI mapper。
- 只能依赖领域类型、领域接口和必要 application coordinator；禁止依赖 `model/platform`、Compose、Activity、Context、Intent、URI、Room Entity/DAO、具体 Repository 实现或 Shared API wire DTO。
- ViewModel 接收稳定 command/action，不接收 View 私有 DTO 或平台对象；耗时与并发操作保留取消、串行化、generation guard 和 undo 时序。

## 状态规则

- UiState 只投影领域状态，不成为第二持久化真相；Session、Review、Ledger、Sync 与备份结果不得复制出互相竞争的 owner。
- Ledger/Review/Sync mapper 必须从领域模型生成界面摘要；不得把 Entity、HTTP 合同或数据库 ID 结构泄露给 View。
- 分类规则 Flow、规则变更和 AI 设置由 `CategorizationViewModel` 统一拥有；Screen 与根组合不得直接读写 Preferences Repository。

## 验证

覆盖成功、失败、空值、取消、并发、重启回流和陈旧结果隔离；测试放在镜像的 `src/test/java/com/bks/viewmodel/...` 包。
