# Android 模块指南

## 作用域与结构

本文件适用于 `apps/android/`。通用规则继承仓库根目录 `AGENTS.md`。

- `src/main/java/com/bks/model/`：领域模型、application coordinator、data 实现和非界面 Android 平台适配。
- `src/main/java/com/bks/viewmodel/`：AndroidX ViewModel、UiState、UiModel、UI command/result 与 UI mapper。
- `src/main/java/com/bks/view/`：Activity、Compose、Route、Navigation、Dialog 与界面侧平台回调。
- `src/main/res/` 与 `AndroidManifest.xml`：应用资源、权限、Activity 及系统 Service 声明。
- `src/test/java/`：按生产包路径镜像组织 JVM、Robolectric、Compose 与 Room 测试。
- `src/androidTest/java/`：运行在真机或模拟器上的 Instrumentation 测试；用于验证真实 Android SQLite 或系统边界。

## 实现约束

- 生产源码在 `com.bks` 下只能有 `model`、`viewmodel`、`view` 三个顶层包；微信 SDK 强制的各构建变体 `${applicationId}.wxapi.WXEntryActivity` 是唯一例外。
- 依赖方向固定为 `View → ViewModel → Model Domain`；`model.data`、`model.application` 与 `model.platform` 实现领域接口或协调领域能力，不得反向依赖 View/ViewModel。
- 保持 `MainActivity` 只负责系统生命周期、外部 Intent 转换与 `setContent`；依赖创建由 `model.platform.app.BksAppContainer` 承担，ViewModel factory 与根 UI 组合留在 `view.app`。
- UI 状态与持久化状态必须明确区分。涉及账目、待确认队列、设置或账号状态时，验证进程重启后的恢复行为。
- 修改 Room 表或字段等 schema 时，递增 `SCHEMA_VERSION`，补充连续 Migration，在 `BksDatabaseProvider` 注册，并提交新的 `schemas/.../<version>.json`。DAO 或 Converter 行为变化应补充对应持久化测试。禁止使用破坏性迁移掩盖缺失 Migration。
- 修改自动记账无障碍 Service 时，保持 `exported=false`，自动采集不截图、不操作其他应用；用户显式开始的补录会话可在无障碍文字不足时对所选应用的安全账单详情页进行本机 OCR，结束、超时、断连后停止，截图仅在内存中处理。页面原文不得进入账本、备份、同步或上传；自动采集仅可在用户开启诊断日志时按 `model` 层规则保存支付宝实际采集批次。覆盖开关/授权分离、拒绝、空输入、重复事件、陈旧补录结果及采集上限。
- Compose 界面沿用现有 Material 3 与 `view.common` 组件风格；可见行为变化同步维护受影响的 UI 测试，已有验证足够的文案或样式小改动不强制新增测试。

## 验证

先运行与改动最相关的单个测试类或层级包：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.<layer>.<area>.<TestClass>"
```

- 每个开发阶段只运行专项测试与必要的 Detekt；
- 同一轮连续改动期间，不重复运行 Android 全量测试；
- 整轮代码改动涉及跨层、共享状态或较广行为影响时，运行一次 `.\gradlew.bat :apps:android:testDebugUnitTest`；局部改动的专项验证已覆盖风险时不默认扩大；
- 代码跨模块或影响较大时运行完整 `build`；准备本地提交本身不扩大测试范围，发布检查按发布流程执行；
- 除排查缓存、竞态或用户明确要求外，不使用 `--rerun-tasks`；
- Gradle Daemon 只在整轮最终验证后停止；
- 涉及资源、Manifest、权限或构建配置时，运行 `.\gradlew.bat :apps:android:assembleDebug`；涉及 Release 特有代码、混淆或签名时再验证对应 Release 产物。
- Compose 行为测试放在 `src/test` 并通过 Robolectric 运行；可恢复 UI 状态使用 `StateRestorationTester` 验证；窗口适配使用 `DeviceConfigurationOverride`，至少覆盖 400、610、900 dp 宽度以及 1.5 倍字体。

当前仓库未跟踪设备端 Room Instrumentation 测试。新增时放入 `src/androidTest/java/com/bks/model/data/local/database/`，并通过 `connectedDebugAndroidTest` 指定新的完整类名运行；不得恢复已删除的旧 `com.bks.data.local` 测试包。连接多台设备时先设置当前 PowerShell 进程的 `ANDROID_SERIAL`，不得把网络或设备失败误报成测试通过。

生成 Android JVM/Robolectric 覆盖率：

```powershell
.\gradlew.bat :apps:android:jacocoDebugTestReport
```

HTML 报告位于 `apps/android/build/reports/jacoco/jacocoDebugTestReport/html/index.html`，XML 报告位于同级任务目录。根目录的 `.\gradlew.bat coverageReport` 会同时生成 Android、后端和共享 API 三个模块的报告。
