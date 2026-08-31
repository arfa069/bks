# Android 三层包结构指南

## 目录边界

本目录的手写生产 Kotlin 只能位于以下三个顶层包：

- `model/`：业务真相、数据实现、跨数据源协调与非界面 Android adapter；
- `viewmodel/`：面向界面的状态、命令、结果和 AndroidX ViewModel；
- `view/`：Activity、Compose、导航、Dialog 与界面侧平台操作。

业务名称放在层级之后，例如 `model/domain/ledger`、`viewmodel/ledger`、`view/ledger`。禁止恢复 `feature/`、`data/local/`、`ui/` 或 `com.bks` 根包 Kotlin 文件。各构建变体的 `${applicationId}.wxapi.WXEntryActivity` 因微信 SDK 约束保留原包，是唯一例外。

## 全链路不变量

- 导入候选必须先进入待确认队列，经用户确认后才能成为账本记录；采集、去重和 AI 不得直接确认入账。
- Compose/UiState 不是持久化真相；账目、忽略记录、规则、设置、账号和同步状态必须验证重启恢复。
- Room、Shared API wire DTO 与平台 SDK 类型只能在所属边界内出现，跨层使用领域模型和领域接口。
- 测试目录镜像被测生产包；测试替身放在 `src/test`，不得进入生产源码。

## 验证

结构变更至少运行 `MvvmPackageArchitectureTest`、受影响层专项测试和 `compileBenchmarkKotlin`；整轮完成后运行 Android 全量单测、Detekt 与 Debug/Release 构建。
