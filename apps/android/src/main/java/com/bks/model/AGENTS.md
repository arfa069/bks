# Android Model 层指南

## 子层职责

- `domain/` 只保存纯 Kotlin 领域模型、规则、验证、命令、结果和 Repository/Gateway 接口；禁止依赖 Android、AndroidX、Compose、Room、ViewModel、View、`com.bks.api` wire DTO，也不得通过 `java.io`、`java.nio.file` 或 `kotlin.io.path` 执行实际文件 I/O。
- `application/` 保存 Session、Cloud Settings、Bill Sync、Review、Ledger Sync 等跨数据源协调器；只能面向领域接口，不直接依赖具体 Room/HTTP 实现、平台 SDK、View 或 ViewModel。
- `data/` 保存 Room、Repository/Gateway 实现、HTTP/Shared API 映射、备份、文件与加密实现；Entity/DTO 必须显式映射为领域类型后向上返回。
- `platform/` 保存 Application/容器、无障碍 Service、Worker 和微信 SDK 等非界面 Android 入口；通过领域接口或 application coordinator 连接业务，不得依赖 View/ViewModel。

## 数据与流程不变量

- `pending_entries`、`ignored_entries`、`ledger_entries` 是不同生命周期；确认、忽略、恢复和撤销必须通过 Repository 完整事务执行。
- 金额使用最小货币单位整数；持久化枚举和同步字段是稳定契约。同步范围内写入与 outbox 同事务，远端应用器不得反向生成 outbox。
- Room schema 变化必须递增版本、补连续 Migration 并提交 schema；数据库名保持 `bks.db`，禁止用破坏性迁移掩盖缺口。
- 初始分类规则是真实可编辑记录；升级不得覆盖用户编辑或复活已删除规则。AI 与本地规则只提供建议。
- 备份恢复先完整解密校验再事务替换；失败不得部分修改。口令、Token、Cookie、验证码和密钥不得落日志或代码。
- 诊断记录是最佳努力旁路，失败不得阻断业务；只有用户开启时的支付宝实际采集批次可保存正文，其他包仅保存元数据，认证秘密始终先脱敏。
- 无障碍 Service 只做薄入口，保持 `exported=false`；不截图、不点击、不滚动、不启动其他应用。捕获结果固定进入待确认队列。
- WorkManager 使用 `account-ledger-sync-now-v2` 与 `account-ledger-sync-periodic-v2`；Runtime 首次启动取消旧唯一名称后再按 eligibility gate 调度。

## Composition Root

`platform/app/BksAppContainer` 可以创建具体 data 实现，但只向外暴露领域接口、application coordinator 和必要 platform gateway；Database、DAO、`Local*`、`Http*` 等具体实现保持私有。
