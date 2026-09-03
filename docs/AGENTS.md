# 文档目录指南

## 作用域与文档职责

本文件适用于 `docs/`。

- `PRD.md`、`ARCHITECTURE.md`、`UI-DESIGN.md`、`COMPLIANCE.md`、`PAYMENT-RECOGNITION-RULES.md` 与 `DIAGNOSTIC-LOGS.md` 描述当前产品、系统架构、界面设计、合规要求、交易识别与诊断基线。
- `refactor/` 保存 Android 系统性架构重构执行计划与历史阶段快照。
- `todos.md` 汇总仍需外部服务、真实设备或生产环境的未完成事项。
- `README.md` 是文档入口索引。

代码行为发生变化时，更新描述该行为的现行文档；不要只在临时记录中提及已经落地的事实。

## 编辑约束

- 使用 UTF-8 和简洁 Markdown；标题层级连续，内部链接优先使用相对路径，且必须指向真实存在的文件。
- 新增顶层文档或文档集合时，同步更新 `docs/README.md`。
- 修改文档时保持与代码、测试及各模块 `AGENTS.md` 规范一致；架构与包名规则以现行 MVVM 三顶层包结构为准。
- 文档不得包含真实凭据、个人数据、完整交易样本或未脱敏日志。

## 验证

提交前重新打开包含中文的文件确认编码，检查相对链接和编号，并运行：

```powershell
git diff --check -- docs/
rg -n "TODO|TBD|待补充" docs/
```

`TODO` 类标记可以保留，但必须确认它们是明确的后续事项，而不是遗漏内容。
