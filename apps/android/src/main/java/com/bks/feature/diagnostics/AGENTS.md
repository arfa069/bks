# Android 诊断日志指南

## 职责

本目录负责敏感诊断事件合同、认证秘密脱敏、事件限流与截断、设备内明文分段、查询、清空和明文 `.log` 导出。

## 安全不变量

- 生产链路只能通过 `DiagnosticRecorder` 最佳努力写入；记录、格式化或轮转失败不得阻断采集、解析、去重或持久化。
- 生产 Logcat 只允许元数据、稳定原因、计数和关联 ID，不得输出敏感 payload、原始异常消息或完整堆栈。
- 聊天、无关页面和不支持包名只记录拒绝元数据，不得写入可见正文；截图在任何情况下都不得保存。
- 密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥等认证秘密必须在写入和导出前脱敏。新增 payload 字段不得绕过 `DiagnosticSanitizer`。
- 新日志以 UTF-8 compact JSONL 明文 `.log` 存放在 `noBackupFilesDir`；保持一事件一物理行、1 MB 分段、10 MB 总上限和 256 KB 单事件上限。旧 `.aadlog` 不读取、不迁移，仅在清空时一并删除。
- `bill_sync_capture_batch` 每个实际采集批次必须独立写入并保留正文，不参与 5 秒合并；其他诊断事件继续保持 5 秒合并语义。
- App 直接显示明文，导出为无需口令的 UTF-8 `.log`；时间固定为 `yyyy-MM-dd HH:mm:ss`。不得改变账本加密备份格式或共享加密原语。
- 仅支付宝包的实际采集批次可保存页面正文；聊天、无关页面和其他包只记录元数据。认证秘密始终在明文落盘和导出前清除。
- 关闭只停止新记录；清空必须同时删除分段和 Keystore key。本机数据删除还需重置 Release 开启偏好，但不得声称会删除 Downloads 中的导出文件。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.diagnostics.*"
```

涉及采集范围或合规界面时，同时运行 capture、billsync、compliance 定向测试，并复核 `docs/COMPLIANCE.md` 与 `docs/DIAGNOSTIC-LOGS.md`。
