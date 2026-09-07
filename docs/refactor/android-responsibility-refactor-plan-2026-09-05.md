# Android 职责与命名重构审查及实施方案

状态：阶段 1-5 已完成。审查日期：2026-09-05；阶段 1 完成日期：2026-09-05；阶段 2-4 完成日期：2026-09-06；阶段 5 完成日期：2026-09-07。

基线：`97c3560 feat: align primary button colors` 加当前工作区。数据库当前为 v13；不能沿用早期 MVVM 迁移方案中的 v12。

## 1. 范围与结论

本轮“全项目”按当前问题限定为 Android 模块：扫描全部主源码目录、声明与依赖，重点追踪账号、账本、待确认、同步、备份、自动记账以及根 UI 装配。同时核对测试、构建变体、宏基准与现行规则；不把本次审查表述为后端或 Shared API 的完整代码审计。

主源码共 194 个 Kotlin 文件：Model 91 个、ViewModel 30 个、View 73 个。按物理行统计，35 个文件不超过 40 行；文件数量本身不是缺陷，也不是重构验收指标。

当前三层目录已经成立，但部分职责仍沿用旧结构，再用适配器和页面 DTO 包装。主要问题是职责边界未完成、同义类型与重复映射、Screen 和 Route 职责混合，以及文件名不能准确定位主要声明。

本报告区分结构事实与风险推断：下面的源码关系已核实；维护风险不等于已复现的用户故障。未逐项执行业务功能或真机验收，不声称现有所有业务流程无缺陷。

## 2. 审查发现

### 2.1 高优先级：层级边界仍有缺口

- 阶段 3 前的 `model/platform/account/WechatAuthCoordinator.kt` 同时包含 `WechatAuthGateway`、授权结果/回调、协议同意检查、`AndroidWechatAuthGateway` 和 SharedPreferences 状态存储。`AccountEntryViewModel`、`AccountManagementViewModel`、`WechatLoginController` 直接依赖此 platform 包。领域契约与 Android 实现未分开，后续修改 SDK 容易牵动页面契约。
- [MvvmPackageArchitectureTest.kt](../../apps/android/src/test/java/com/bks/architecture/MvvmPackageArchitectureTest.kt) 的 ViewModel 禁止列表未包含 `model.platform`；依赖检查主要读取 import，不能覆盖全限定类型引用，也没有区分 Screen 渲染和 Route 装配。因此测试通过不能证明完整依赖边界正确。
- [InternalBetaHardening.kt](../../apps/android/src/main/java/com/bks/model/domain/beta/InternalBetaHardening.kt) 在领域层执行 `File.readText()` 和 `walkTopDown()`。仓库搜索发现 `scanForSecretLikeValues` 仅被测试调用；源码扫描工具不应随领域规则进入生产 APK。保留扫描覆盖，迁到测试侧，而不是删除测试。

### 2.2 高优先级：账本仓储仍是多职责入口

- 阶段 2 前的 `LocalLedgerRepository.kt` 共 652 行，同时聚合账本/账目/资金账户、初始化分类、处理 Pending/Ignored 确认与撤销、清空本机数据，并声明多组 Entity 状态与操作结果。
- 阶段 2 前的 `LocalLedgerDomainRepository.kt` 在外层做领域适配；`ReviewQueuePersistence.kt` 又依赖同一个 LocalLedgerRepository。现在两个领域入口实际上汇入同一个多职责实现。
- data 与 domain 都声明 `LedgerBookRepository`、`LedgerEntryRepository`、`FundingAccountRepository`。data 接口返回 Entity，注释却称其为 Domain repository；具体实现名为 `Room*Repository`，文件仍以接口名命名。查找接口、实现和调用时容易选错层。
- Review 的 Entity→Domain 映射在 `LedgerEntityMappers.kt`，反向映射部分藏在 `ReviewQueuePersistence.kt`；确认 receipt 又有 data/domain 两套。问题是映射归属和接口重复，不是应当取消 Entity→Domain 边界。

确认、忽略、恢复、撤销目前在 Room 事务内检查稳定 ID 与既有生命周期，并在确认/撤销时处理同步记录。拆职责必须整体搬移这些事务，不能变成 ViewModel 顺序调用多个可独立提交的 Repository 方法。

### 2.3 中优先级：UI 契约重复，命名不能准确表达用途

- [LedgerSyncUiState.kt](../../apps/android/src/main/java/com/bks/viewmodel/sync/LedgerSyncUiState.kt) 与阶段 1 前的 `DataAndBackupScreenModels.kt` 分别声明同步状态和冲突展示类型；阶段 1 前的 `BksScreenModelMappers.kt` 再做字段复制。设置投影少几个字段，因此不是整类完全相同；但冲突模型六个字段相同，且重复状态没有独立业务语义。
- domain 与 viewmodel 都有 `LedgerModels.kt`；`LedgerUiEntry`、`FundingAccountUiModel`、`ReviewQueueEntry` 命名风格不同。`LedgerScreenModels.kt` 混有页面状态、领域 mapper、页面内导航及格式化；`ReviewQueueState.kt` 混有状态、条目模型、证据解析和金额函数。
- 阶段 1 前的 `LocalDataPortability.kt` 同时承载删除确认 reducer、CSV 编码和导出文件名规则。“Portability” 无法帮助定位这三项具体职责。
- `PersistedLocalDataBackup.kt` 的主要执行类实际是 `LocalDataBackupRepository`；`BksRouteFactories.kt` 实际创建 Account ViewModel；`WechatLoginFlow.kt` 包含 `WechatLoginController`。这些名称可直接按实际职责纠正，不需要新增一层包装。
- `LedgerFormatting.formatMoney` 与 Review 的 `formatAmount` 有相同实现；Review 状态还包含固定的 `SAMPLE_*` 时间默认值。应统一相同格式化语义，并让样例时间属于测试；生产时间仍由现有 nowProvider/clock 提供。尚未证明样例默认值造成当前页面错误。

### 2.4 中优先级：页面已拆文件，但生命周期装配仍留在 Screen

- [AccountScreen.kt](../../apps/android/src/main/java/com/bks/view/account/AccountScreen.kt) 通过 `AccountScreenDependencies` 接收 ViewModel，并收集状态、处理微信回调；[AccountManagementScreen.kt](../../apps/android/src/main/java/com/bks/view/account/AccountManagementScreen.kt) 又把 ViewModel 继续传入微信管理面板。依赖对象并没有让 Screen 真正收敛为 state/actions。
- [DiagnosticLogsScreen.kt](../../apps/android/src/main/java/com/bks/view/diagnostics/DiagnosticLogsScreen.kt) 同时支持注入 ViewModel 和 factory 回退，收集状态及导出请求，管理导出 Job、窗口安全标志和展示内容。`DiagnosticLogsScreenOverrides` 是额外的装配路径。应只有一个 Route 装配入口，Screen 只渲染；窗口和 writer 仍留在 View。
- [BksApp.kt](../../apps/android/src/main/java/com/bks/view/app/BksApp.kt) 共 535 行，组合导航、owner 获取、factory、状态收集和 notices。Account factory 在 `BksRouteFactories.kt`，其余部分在 `BksViewModelFactories.kt`，部分仍内联在 BksApp，归属不一致。
- `LedgerManagementScreens.kt` 定义两个薄 Screen 包装，但具名 `LedgerBookManagementScreen.kt`、`FundingAccountManagementScreen.kt` 实际定义的是 Content。文件跳转无法直接找到同名入口。
- 备份 factory 通过函数引用连接 LedgerViewModel 的数据替换状态、ReviewViewModel 的 idle/reset 与现有同步串行器。这里只是显式暴露了跨页面协调，不能仅因参数多就包成新 Coordinator；必须保留当前 owner 和执行顺序。

### 2.5 中优先级：长类集中多种变化原因，但不能机械拆散状态

- [BillSyncCaptureCoordinator.kt](../../apps/android/src/main/java/com/bks/model/platform/billsync/BillSyncCaptureCoordinator.kt) 共 1,227 行。除了会话、取消和调度，还包含节点选择/遍历、可读文本采集、后台持久化交接、窗口去重状态、诊断波次聚合和运行时探针。它与 application 的 `BillSyncCaptureProcessor` 职责不同，不能按名字相近直接合并。
- [AccountManagementViewModel.kt](../../apps/android/src/main/java/com/bks/viewmodel/account/AccountManagementViewModel.kt) 共 649 行，涵盖资料编辑、身份绑定/合并/解绑、注销、Session 失效及多个 generation guard。优先提取无状态转换和可独立验证的业务分支，保留 Job、token、generation 的唯一 owner。
- 现有小文件有合理边界：`ReviewQueueCaptureCoordinator` 的共享互斥锁、领域 Repository/Gateway、平台权限/健康读取和同步排序均不能因“代码短”删除。备份各版本 Codec 与同步 Entity/DTO 映射也不应回并成大文件。

## 3. 统一规则

### 3.1 命名与文件归属

- 领域接口使用业务名 `*Repository` / `*Gateway`；Room 实现统一 `Room*Repository`；只在 data 内操作 Entity 的协作对象使用 `Room*Store`，不再复制一套同名公开 Repository 接口。HTTP、SDK、文件实现分别使用已有 `Http*`、`Android*`、`Local*` 前缀。
- 页面持续展示状态使用 `*UiState`，列表/卡片使用 `*UiModel`；局部输入草稿使用 `*FormState`，动作集合使用 `*Actions`。局部展开/滚动/Dialog 状态可留 View，不能把所有 State 都迁到 ViewModel。
- `*Route` 负责获取 ViewModel、收集状态、生命周期与平台回调；`*Screen` 负责整页渲染；`*Content` 只用于真正被组合的展示区块。账号密码草稿不因命名调整而进入 SavedStateHandle 或可恢复存储。
- 一个主要类/入口用同名文件；紧密关联且共同变化的契约允许放同文件。mapper 使用业务与边界明确的名字；不新增 `Utils`、`Helper`、`Manager`、`CommonModels` 等杂物文件。
- 合并的依据是同一职责、重复类型或无语义转发；拆分的依据是独立变化原因、外部依赖或副作用边界。新增文件必须说明独立职责，不能为了 Detekt 阈值把同一状态机拆成多个 owner。

### 3.2 必须保持的行为

- `model/viewmodel/view` 三顶层包、单一 Android 模块、现有依赖版本、applicationId，以及 Activity/Application/Service/Worker 与 wxapi FQCN 保持不变。
- Room v13、数据库文件、SQL/索引/identity hash、HTTP/JSON、Shared API、同步协议、备份版本/字段顺序、偏好 key、Keystore alias 不变；不改枚举的持久化名称。
- Review 稳定 ID、确认/忽略/撤销原子性、同步 outbox 同事务、失败回滚与重开数据库回流不变；本机替换保留同步串行化、Review idle、采集锁和 UI 遮挡状态顺序。
- Session generation、回调 state/relay token 的过期和单次消费、取消传播、敏感草稿清理、导出 writer 的生命周期不变。
- 不改变无障碍事件类型、读取范围、延迟、上限、诊断采样语义或授权状态；不新增自动确认入账能力。本轮只是审查，不执行设备操作。

## 4. 分阶段实施

以下五个阶段分别验收；不把所有重命名、事务搬移和生命周期调整混在一个不可定位的改动中。五个阶段均已实施并分别记录验收结果。

### 阶段 1：统一 UI 命名，消除重复契约

状态：Completed（2026-09-05）。

目标文件与处理：

- viewmodel 下 `LedgerModels.kt` → `LedgerEntryUiModel.kt`，`LedgerUiEntry` → `LedgerEntryUiModel`；`LedgerScreenModels.kt` 收敛为 `LedgerUiState.kt`。已有领域 mapper 合入 `LedgerEntryUiMapper.kt`；`LedgerPage/LedgerView` 回到唯一消费它们的 `view/ledger/LedgerScreen.kt`，保留保存/恢复行为。
- `ReviewQueueState.kt` → `ReviewQueueUiState.kt`；条目统一为 `ReviewEntryUiModel` / `IgnoredReviewEntryUiModel`。真正独立的金额展示/解析归入 `viewmodel/ledger/LedgerFormatting.kt`，证据解析留在明确的 Review 展示职责文件。保持解析舍入规则，格式化重命名不夹带金额行为修复。
- `CategorizationAiUiState.kt` → `CategorizationUiState.kt`；`AccountState.kt` 和 `AccountIdentityState.kt` 按实际输入草稿命名为 `AccountFormState.kt`、`AccountIdentityFormState.kt`，操作状态另由现有 ViewModel 持有；`WechatLoginFlow.kt` → `WechatLoginController.kt`。
- 同步设置直接消费 `viewmodel/sync` 的一套 UI 契约。新增/迁入 `LedgerSyncPreviewUiModel`，ViewModel 内完成 application 结果与领域冲突到 UI 的转换；复用领域 `LedgerSyncConflictChoice`，删除重复 Settings conflict/choice/state 及 View 中逐字段映射。保留 `profileKey` 和账号切换状态的现有内部用途，不误删真实状态。
- `DataAndBackupScreenModels.kt` 去除同步类型后按实际导出职责改名；`LocalDataPortability.kt` 分为删除确认契约与 CSV 编码两项。`ProfileAccountSummaryUiModel` 及其 mapper 迁到 `viewmodel/account`，不为一段摘要新增 ProfileViewModel。清空后删除 `BksScreenModelMappers.kt`。

验收：不再存在两套同步冲突 UI 类型或 View 内同步状态/结果转换；测试与所有消费点一起迁移；不遗留兼容 typealias。新增或强化同步预览、冲突、失败、空列表映射测试，以及金额/CSV 原契约测试。

先跑 `viewmodel.sync.*`、`viewmodel.review.*`、`viewmodel.settings.*`、`viewmodel.ledger.*` 与对应 settings/ledger/review Compose 专项，再跑架构测试和必要编译；不要求此阶段重做业务真机验收。

### 阶段 2：账本与待确认各自拥有持久化职责

状态：Completed（2026-09-06）。

目标文件与处理：

- 合并 `LocalLedgerDomainRepository` 和旧 LocalLedgerRepository 的账本聚合部分为 `model/data/local/ledger/RoomLedgerRepository.kt`，直接实现 domain `LedgerRepository` 并返回 `LedgerSnapshot`。不保留“领域适配器包一层旧仓储”的结构。
- 原 data `LedgerBookRepository.kt`、`LedgerEntryRepository.kt`、`FundingAccountRepository.kt` 改为对应 `Room*Store.kt`，作为 internal Entity 协作对象。领域接口保持唯一；资金账户默认设置已有领域接口的用途保留，不误删设置能力。
- `ReviewQueuePersistence` 与旧仓储的 Review 事务合并到 `model/data/local/review/RoomReviewQueueRepository.kt`。它直接拥有 Pending/Ignored 观察与流转事务，复用账本/资金账户必要的 data 协作函数；不再依赖整个账本 Repository。
- Entity↔Domain 转换集中到现有 `LedgerEntityMappers.kt` 与 Review 自身的映射职责，字段使用具名参数。领域 command/result 已够用时取消重复的 `LedgerEntryInput`、删除结果或 receipt；确实需要 Entity 快照的内部类型明确标注用途与可见性。
- 更新 BksAppContainer、benchmark 造数与测试 Harness。只供测试造数的旧便捷入口迁到测试；生产清空本机数据的原入口与完整事务暂保留在账本聚合实现，不为了单一方法再造 Repository/Coordinator。

验收：只有 domain 定义业务 Repository 接口；Review Repository 不依赖账本聚合实现；领域返回值不含 Entity。确认和撤销的数据库更新及 outbox 不被拆开提交；默认账本和资金账户复用规则不变。

重点测试：`LocalLedgerEntryRepositoryTest`、`LocalLedgerManagementRepositoryTest`、`ReviewQueuePersistenceTest` 随实际归属迁移；补充完整映射、null、空列表、事务失败回滚、重复确认/忽略/撤销、并发捕获、重开数据库和备份回流。运行本地 sync 与 backup 相关专项，确认 schema 未变化。

### 阶段 3：账号边界与页面装配收口

状态：Completed（2026-09-06）。

目标文件与处理：

- 从 `WechatAuthCoordinator.kt` 提取纯契约到 `model/domain/account/WechatAuthGateway.kt`；同意检查协调归 `model/application/account/WechatAuthCoordinator.kt`；OpenSDK 与 Android 状态存储分别留 `model/platform/account/AndroidWechatAuthGateway.kt`、`WechatAuthStateStore.kt`。已有 fingerprint/purpose 函数按是否依赖平台归位，保留计算及持久化格式。
- 保留 `AccountManagementViewModel` 的唯一 Job/generation/Session owner；身份页面转换归 `AccountIdentityReducer.kt`，其余分支优先同文件私有函数。不新增并列账号 ViewModel 或持有独立 Session 的 Controller。
- Account、AccountManagement、DiagnosticLogs 的 state 收集、激活/关闭、微信回调消费与 factory 移到 `view/app` 中各自 Route；Screen 只接收 state/actions 和必要平台输入。诊断窗口保护和 writer 归 Route 的 View 平台处理，测试直接注入 writer/state/actions，移除双装配路径。
- `BksRouteFactories.kt` 合入 `BksViewModelFactories.kt`，BksApp 内剩余内联 factory 一并归位。不引入通用 factory 框架。BksApp 保留根导航与组装；现有 Route 文件按实际页面归并，避免每一个短 Route 再单独建立文件。
- 将 `LedgerManagementScreens.kt` 的两个薄入口合并到同名 Screen 文件，共用稳定测试标识集中管理；保留本地 Dialog、Avatar URI/reader、MediaStore、Snackbar 和保存恢复相关 View 状态。

验收：ViewModel 的 platform import/全限定引用清零；Screen 不接收 ViewModel、factory 或 Repository/Gateway。AccountSessionCoordinator 仍是 Session owner；数据替换仍使用现有 Ledger/Review/Sync 协调顺序，不能借整理 factory 新建重复协调器。

重点测试：账号登录/管理 ViewModel、微信 state/relay token、账号管理与微信 UI、诊断导出及 FLAG_SECURE、根导航和页面重建。覆盖旧响应、重复回调、切换账号、取消、失败后清理及文件选择回来后的状态。

### 阶段 4：采集职责拆分与剩余文件清理

状态：Completed（2026-09-06）。

目标文件与处理：

- `BillSyncCaptureCoordinator.kt` 保留会话生命周期、generation、事件调度与取消，仍是调度状态的唯一 owner；节点选择、遍历和读取归 `AccessibilityPageReader.kt`，窗口去重及 in-flight/deferred 状态归 `CaptureWindowTracker.kt`。
- 后台交接函数归 `BillSyncCaptureHandoff.kt`，运行时探针归 `BillSyncRuntimeProbe.kt`；波次记录尽量并入已有 `BillSyncCaptureDiagnostics.kt`。不建立第二个 scheduler，不改变 dispatcher/锁顺序；若共同状态必须拆开才能搬移，先保持原位置并说明具体阻碍。
- 将源码密钥扫描和相关模型迁入 `src/test/.../model/domain/beta` 的测试支持文件，保留原扫描测试；生产内测报告模型继续留领域层。移除 Review 生产契约里的样例时间默认值，测试显式提供固定时钟。
- `PersistedLocalDataBackup.kt` 按主要执行类改名为 `LocalDataBackupRepository.kt`，保留版本 Codec/验证/加密的职责分离；`LocalPreferencesRepository` 按 Room 命名规则迁为 `RoomPreferencesRepository`。其他 remote/sync mapper 按真实边界保留，不能混合 wire 与领域模型以减少文件。
- 对未列入重点的 categorization、reports、compliance、home/profile、theme/components、security、HTTP 与 sync runtime 保持现有职责。只迁移已列出的命名/消费点和确证无用 import；无新增证据时，不因文件超过某个行数再拆一轮。

验收：协调器可说明“只调度什么”，reader 可独立验证“读取什么”，tracker 可独立验证“何时接受/释放窗口”；generation、采集锁、诊断旁路和 Service FQCN 不变。Domain 不执行文件遍历或磁盘读写。

重点测试：`BillSyncCaptureCoordinatorTest`、`BillSyncCaptureHandoffTest`、`BillSyncCaptureDiagnosticsTest`、`BillSyncCaptureProcessorTest`，并覆盖上限、空节点、异常读取、会话重连、持久化失败/取消后的窗口释放、并发采集与数据替换。拆分后对应测试镜像迁移，不删除旧覆盖。

### 阶段 5：规则、构建资产与最终验收

状态：Completed（2026-09-07）。

- 强化现有架构测试：ViewModel 禁止 platform；Domain 禁止实际文件 I/O；补全全限定类型引用检查，避免注释/字符串误报。对规则检查器本身提供违规和合法样例。Screen 检查优先覆盖入口参数与装配调用，不用“所有 View 文件都禁止 State/Actions”的粗暴规则。
- 同步三层 AGENTS 命名职责规则与现行 ARCHITECTURE，历史 phase 文档不批量重写。新增规则只放所属目录；不把本方案的目标目录提前写成已实现事实。
- Detekt 仅迁移仍存在的既有 finding，移除已解决项；不得重新生成 baseline 或新增 suppression 来隐藏拆分问题。所有源码集、测试、benchmark 与 JVM 文件类名引用一起更新。
- 主源码/JVM 文件类名改变会影响已跟踪 Baseline Profile；实现完成后用隔离模拟器或届时明确授权的测试设备重新生成，再做性能旅程验证。不能全局替换 profile 文本冒充生成，也不能沿用之前设备连接/验收结果宣称本轮通过。
- 汇总增删文件、删除的重复契约、剩余职责例外和实际测试结果。文件数允许增加；完成标准是重复接口/状态减少、边界明确、状态 owner 唯一、测试和外部契约保持。

整轮最终验证一次执行，阶段内只跑相关专项：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat :apps:android:detekt :apps:android:compileBenchmarkKotlin :apps:android:assembleDebug
.\gradlew.bat :apps:android:assembleRelease
.\gradlew.bat build
git diff --check
.\gradlew.bat --stop
```

Profile 使用仓库 `:apps:android:generateReleaseBaselineProfile` 任务和现有 BaselineProfileGenerator。签名材料齐全时验证 Release APK 签名，但不得输出凭据。最终记录未运行的检查和原因，不能把部分专项通过表述为全量验收。

## 5. 方案审查时的验证与工作区

以下记录描述 2026-09-05 方案审查结束时的状态，不代表后续阶段 1 实施结果。

- 已执行 `:apps:android:testDebugUnitTest --tests "com.bks.architecture.MvvmPackageArchitectureTest"`：5 项通过，0 失败；现有检查的缺口见 2.1。
- 已执行 `:apps:android:detekt`：BUILD SUCCESSFUL，任务为 UP-TO-DATE，复用了当前输入对应结果，不是重新分析全部源码。
- 本轮仅新增本方案并在 docs 索引添加入口；保留原有 CONTEXT、README、docs 规则/规划等未提交文档改动。没有修改生产代码、测试或设备。
- 未运行全量业务单测、Debug/Release APK 构建、根 build、Baseline Profile 生成或真机验收：本轮为静态审查与方案阶段。实施时按各阶段门槛补齐。
- 方案源码链接及 UTF-8 检查通过，`git diff --check` 通过；最终已停止 Gradle Daemon。新方案仍为未跟踪文件，本轮未提交。

推荐实施起点：阶段 1。先取得可评审的命名与契约去重结果，再进入涉及事务和 Session 时序的阶段；不提前提交、推送或创建分支。

## 6. 阶段 1 Execution Record

状态：Completed。仅实施“统一 UI 命名，消除重复契约”，未进入阶段 2 的 Room Repository/事务拆分，也未进入阶段 3-5 的账号边界、采集或规则强化。

### 6.1 实施结果

- `LedgerUiEntry`、`ReviewQueueEntry`、`ReviewQueueIgnoredEntry`、`AccountUiState`、`AccountIdentityUiState` 分别迁为 `LedgerEntryUiModel`、`ReviewEntryUiModel`、`IgnoredReviewEntryUiModel`、`AccountFormState`、`AccountIdentityFormState`；文件与测试类同步按主要声明命名，没有兼容 `typealias`。
- `LedgerPage`/`LedgerView` 回到唯一消费它们的 `LedgerScreen.kt`；Ledger 的领域到 UI mapper 集中到 `LedgerEntryUiMapper.kt`。Review 金额展示与 HALF_UP 解析集中到 `LedgerFormatting.kt`，证据分段/合并独立到 `ReviewEvidenceUi.kt`。
- 同步设置直接消费 `viewmodel.sync` 的 `LedgerSyncUiState`、冲突和预览模型；`LedgerSyncViewModel` 负责 application 结果到 UI 结果/预览的转换，冲突选择直接复用领域 `LedgerSyncConflictChoice`。View 内逐字段同步映射及 `BksScreenModelMappers.kt` 已删除，`profileKey` 与账号切换状态保留。
- CSV 导出输入与编码分别归入 `LedgerCsvEntryUiModel.kt`、`LedgerCsvEncoder.kt`，删除确认契约归入 `LocalDataDeletionConfirmation.kt`。Profile 账号摘要模型及 mapper 迁入 `viewmodel/account`；benchmark 与所有测试消费点同步迁移。
- Detekt baseline 只迁移本阶段更名导致失配的既有标识；未重生成 baseline，未新增 suppression。

### 6.2 验证

- 基线专项：`LedgerSyncViewModelTest`、`ReviewQueueStateTest`、`LocalDataPortabilityTest`、`LedgerModelsTest`、`AccountStateTest`，通过。
- 阶段专项：`viewmodel.sync.*`、`viewmodel.review.*`、`viewmodel.settings.*`、`viewmodel.ledger.*`、`viewmodel.account.*`、`viewmodel.categorization.*` 及对应 settings/ledger/review/account/categorization/profile Compose 测试，`BksDialogsTest`，通过。
- `MvvmPackageArchitectureTest`、`:apps:android:detekt`、`:apps:android:compileBenchmarkKotlin`、Debug 主源码/测试源码编译与 `git diff --check`，通过。

### 6.3 偏差、风险与未执行项

- `DataAndBackupScreenModels.kt` 没有保留为单个改名文件，而是按主要声明和 CSV 编码两项职责拆成两个文件；这是为满足当前文件命名规则，并未改变 CSV 字段、顺序、转义或金额格式。
- 已跟踪 Baseline Profile 仍包含旧 `LedgerScreenModelsKt` 名称；按阶段 5 约定，本阶段不手工替换生成内容，待最终阶段在隔离设备重新生成。
- 未运行 Android 全量单测、Debug/Release APK、根 `build`、Baseline Profile 生成或真机验收；阶段 1 按方案只要求专项、架构检查和必要编译。
- 未修改 Room v13 schema、API/同步协议、备份格式、持久化枚举或业务时序；未 commit、push 或创建分支。

## 7. 阶段 2 Execution Record

状态：Completed（2026-09-06）。仅实施“账本与待确认各自拥有持久化职责”，未进入阶段 3-5 的账号边界、采集拆分或规则强化。

### 7.1 实施结果

- 新增 `RoomLedgerRepository`，直接实现 domain `LedgerRepository` 并发布 `LedgerSnapshot`；删除 `LocalLedgerDomainRepository`、旧 `LocalLedgerRepository` 及 data 层重复的业务 Repository 接口。
- 原账本、账目与资金账户 data 文件分别收敛为 internal `RoomLedgerBookStore`、`RoomLedgerEntryStore`、`RoomFundingAccountStore`。领域 `LedgerEntryCommand`、删除结果和 Review receipt/result 成为唯一业务契约，不再保留重复 `LedgerEntryInput` 或 data receipt/result。
- 新增 `RoomReviewQueueRepository`，直接拥有 Pending/Ignored 观察、捕获去重、编辑、确认、忽略、恢复和撤销事务；它只复用账本与资金账户 Store，不依赖 `RoomLedgerRepository`。确认、撤销和同步 outbox 仍在同一个 Room transaction 内。
- Entity↔Domain 映射集中到 `LedgerEntityMappers.kt`，全部改用具名参数；`BksAppContainer`、Benchmark 造数和测试消费点已迁移。旧 Entity 造数入口仅保留在测试目录的 `TestLedgerRepository` fixture，生产清空本机数据事务仍由 `RoomLedgerRepository` 提供。
- `docs/ARCHITECTURE.md` 已同步当前账本/Review 持久化边界；历史阶段文档保持历史记录，不批量改写。

### 7.2 验证

- 阶段专项：`RoomLedgerRepositoryTest`、`RoomLedgerRepositoryManagementTest`、`RoomReviewQueueRepositoryTest`、`LedgerEntityMappersTest`，通过；覆盖 null/空列表映射、事务失败回滚、重复与并发终态、数据库重开及默认账本/资金账户规则。
- 关联专项：`BillSyncCaptureProcessorTest`、`LedgerViewModelTest`、`ReviewQueueViewModelTest`、`AccountCloudSettingsCoordinatorTest`、`LocalDataBackupRepositoryTest`、`LedgerSyncOutboxTest`、`AccountLedgerIsolationTest`，通过。
- Android 全量 `:apps:android:testDebugUnitTest` 通过。
- Debug 主源码/测试源码、Benchmark 源集编译与 `detekt` 通过；架构扫描确认 data 层无业务 Repository 接口、Review 实现不依赖账本聚合、领域 Repository 契约不含 Entity。Room schema 文件无差异，数据库版本仍为 v13。

### 7.3 偏差、风险与未执行项

- 计划中的旧三组测试按实际聚合职责迁为四组新测试；为让非本阶段测试继续以 Entity 精确造数，新增仅位于 `src/test` 的 fixture，没有恢复生产兼容层。
- `RoomLedgerRepository` 与 `RoomReviewQueueRepository` 分别是领域接口规定的聚合入口，函数数超过通用 Detekt 阈值；仅对这两个聚合类保留定点 `TooManyFunctions` suppression，没有拆出第二个状态 owner，也没有修改 baseline。
- 未修改 Room v13 schema、SQL/索引、API/同步协议、备份格式、持久化枚举或业务时序；未执行 Debug/Release APK、根 `build`、Baseline Profile 或真机验收，这些不属于阶段 2 的专项门槛。
- 未 commit、push 或创建分支；阶段 1 与用户已有工作区改动继续保留。

## 8. 阶段 3 Execution Record

状态：Completed（2026-09-06）。仅实施“账号边界与页面装配收口”，未进入阶段 4 的采集拆分/剩余文件清理，也未进入阶段 5 的规则强化、Baseline Profile 与最终发布构建。

### 8.1 实施结果

- 微信授权纯契约、purpose、launch result 与 callback 已迁至 domain `WechatAuthGateway.kt`；协议同意检查迁至 application `WechatAuthCoordinator.kt`。OpenSDK 实现、SharedPreferences state/relay token 存储和 callback Intent 分别归入 platform 同名职责文件，fingerprint 与 Session 匹配保留原 SHA-256/Base64 URL 计算。ViewModel 的 platform import 与全限定引用已清零。
- `AccountManagementViewModel` 继续唯一持有账号/身份 Job、operation generation、management generation 与 active Session token；纯身份页面转换和标识输入判断迁至 `AccountIdentityReducer.kt`，没有新增并列 ViewModel、Session owner 或 Controller。
- Account、AccountManagement 与 DiagnosticLogs 的 lifecycle-aware 状态收集、激活/关闭、微信回调单次消费和平台 effect 已归入 `view/app` Route。三个 Screen 只接收 state/actions 与必要 View 平台输入；诊断 writer、导出 Job、后台取消和 `FLAG_SECURE` 位于唯一诊断 Route，旧 `DiagnosticLogsScreenOverrides` 双装配路径已删除。
- 账号 factory 与 BksApp 中原内联的诊断、分类和同步 factory 已统一并入 `BksViewModelFactories.kt`，`BksRouteFactories.kt` 删除；没有引入通用 factory 框架或新的跨页面协调器。
- 账本与资金账户管理的薄入口已分别并入同名 Screen 文件；共用测试标识集中到 `LedgerTestTags.kt`。Avatar URI/reader、MediaStore writer、Dialog、Snackbar 与保存恢复状态仍保留在 View。
- `docs/ARCHITECTURE.md` 已同步当前账号授权、Route/Screen、诊断平台 effect 与管理 Screen 边界；历史阶段文档不批量改写。

### 8.2 验证

- 阶段专项：`viewmodel.account.*`、application/platform 微信认证、`view.account.*`、`DiagnosticLogsViewModelTest`、`DiagnosticLogsScreenTest`、`BksDiagnosticLogsRouteTest`、`MainActivityTest`、`BksAppStateTest`、`LedgerManagementScreenTest` 与 `MvvmPackageArchitectureTest`，通过；覆盖旧响应、重复回调、账号切换、取消/失败清理、诊断导出、后台取消、安全窗口和页面重建。
- Android 全量 `:apps:android:testDebugUnitTest` 通过（108 个测试套件、617 项测试，0 失败、0 跳过）；`:apps:android:compileBenchmarkKotlin` 通过。
- `:apps:android:detekt` 通过；未新增 suppression，也未修改 Detekt baseline。静态扫描确认 ViewModel 无 platform 引用、目标 Screen 无 ViewModel/factory/Repository/Gateway 参数、BksApp 无内联 ViewModel factory。

### 8.3 偏差、风险与未执行项

- 诊断 Route 的平台处理足以形成独立职责，因此新增 `BksDiagnosticLogsRoute.kt`；其 Screen 测试直接注入 state/actions，Route 测试注入 writer，并保留 request-id 与旧 writer 身份防护。其余短 Route 仍按 Profile/Entry 文件归并，没有机械建立单文件 Route。
- 阶段前基线批量专项中，`DiagnosticLogsScreenTest.backgroundingCancelsExportAndAllowsFreshDirectExport` 曾出现一次 Compose 时序失败；未改代码单独复跑即通过，阶段后专项和 Android 全量测试也通过。该测试现仍验证后台取消后新导出的行为。
- 未修改 Room v13 schema、API/同步协议、备份格式、Session/微信 state 持久化 key、SDK 回调 FQCN 或业务时序；未执行 Debug/Release APK、根 `build`、Baseline Profile 或真机验收，这些不属于阶段 3 的专项门槛。
- 未 commit、push 或创建分支；阶段 1-2 与用户已有工作区改动继续保留。

## 9. 阶段 4 Execution Record

状态：Completed（2026-09-06）。仅实施“采集职责拆分与剩余文件清理”，未进入阶段 5 的架构规则强化、Detekt baseline 清理、Baseline Profile、APK/Release 或根构建验收。

### 9.1 实施结果

- `BillSyncCaptureCoordinator` 继续唯一持有开关观察、Service session、generation、事件聚合/调度、capture dispatcher 与取消；节点根选择、遍历、文本上限和读取迁至 `AccessibilityPageReader.kt`，窗口接受/释放及 in-flight/deferred 状态迁至 `CaptureWindowTracker.kt`。
- 识别后的持久化交接、取消/失败窗口语义与支付来源映射迁至 `BillSyncCaptureHandoff.kt`；专用 dispatcher、延迟探针与关闭语义迁至 `BillSyncRuntimeProbe.kt`；波次 accumulator 并入 `BillSyncCaptureDiagnostics.kt`。没有新增第二个 scheduler，也没有改变 dispatcher、session 身份检查或锁顺序。
- 源码密钥扫描器、pattern 与 finding 模型已从生产 `InternalBetaHardening.kt` 迁至 `src/test` 的 `SourceSecretScanner.kt`；生产 beta 指标/报告模型保持纯领域职责，原扫描测试继续覆盖 Android、Backend 与 Shared 主源码。
- `ReviewQueueUiState` 与 ignored-entry factory 不再提供样例时间默认值；生产 ViewModel 通过真实时钟生成空状态，Compose 测试 helper 显式使用固定时间。排序、今日新增与忽略恢复期限语义不变。
- `PersistedLocalDataBackup.kt` 已按主类改名为 `LocalDataBackupRepository.kt`，Codec、验证、加密、V2/V3/V4 格式及恢复事务未合并或改写；`LocalPreferencesRepository`/测试已改名为 `RoomPreferencesRepository`，调用方同步迁移。
- `docs/ARCHITECTURE.md` 已同步采集 owner、Room preferences 与 backup 主文件名；历史阶段文档不批量改写。

### 9.2 验证

- 阶段前基线专项通过；阶段后 15 个直接相关测试套件、109 项测试通过，覆盖 Reader 上限/空节点/异常读取、窗口接受/释放与 deferred 合并、session 重连、持久化失败/取消、并发采集/数据替换、源码扫描、备份、偏好仓储和 Review 固定时间。
- Android 全量 `:apps:android:testDebugUnitTest` 通过（109 个测试套件、622 项测试，0 失败、0 错误、0 跳过）；`:apps:android:compileBenchmarkKotlin` 通过。
- `:apps:android:detekt` 通过；初次检查发现的 4 个本阶段新增结构问题均通过收紧参数/声明归属消除，未新增 suppression，也未修改 Detekt baseline。
- 静态扫描确认生产 domain 无 `java.io`/`java.nio`/`File` 读写，旧 `AcceptedWindowMemory`、`LocalPreferencesRepository`、`SAMPLE_*` 生产/测试引用清零；Room schema 无差异，`git diff --check` 通过。

### 9.3 偏差、风险与未执行项

- `captureCurrentWindow` 保留在 Coordinator 内：它需要在既有 session 身份锁、诊断计时与异步 handoff 之间编排，继续外移会拆出共同状态或第二个调度 owner。Reader 与 Tracker 已形成可独立测试的纯职责边界，满足本阶段验收且不改变锁顺序。
- `BillSyncCaptureCoordinator` 的 generation、采集锁、诊断旁路和 `BillSyncAccessibilityService` FQCN 保持不变；未修改 Accessibility 范围/事件类型/节点与字符上限、Room v13 schema、API/同步协议、备份格式或依赖。
- 未执行 `assembleDebug`、`assembleRelease`、根 `build`、Baseline Profile 或真机验收；这些属于阶段 5 的规则/构建资产与最终验收范围。
- 未 commit、push 或创建分支；阶段 1-3 与用户已有工作区改动继续保留。

## 10. 阶段 5 Execution Record

状态：Completed（2026-09-07）。仅实施“规则、构建资产与最终验收”；没有继续拆分生产职责、修改业务行为或引入新的架构抽象。

### 10.1 实施结果

- 新增 `KotlinArchitectureRuleChecker` 及其单元测试。架构扫描现在同时检查 import 与代码中的全限定引用，并在扫描前屏蔽行注释、嵌套块注释、普通/原始字符串和字符字面量；ViewModel 禁止 `model.platform`，Domain 禁止 `java.io`、`java.nio.file` 与 `kotlin.io.path`。
- Screen 边界规则只扫描 `*Screen.kt` 中的 Screen 入口参数和 owner/factory 装配，禁止 ViewModel、Repository、Gateway 与 `BksAppContainer`，同时允许 `UiState`、`UiModel`、Actions 及 View 平台输入。规则测试同时覆盖违规和合法样例。
- `com/bks`、`model`、`viewmodel`、`view` 四级 `AGENTS.md` 与 `docs/ARCHITECTURE.md` 已同步现行边界。Detekt baseline 按实时扫描结果从 54 项收敛到 32 项，只移除已解决 finding 并迁移仍存在的改名 finding；未重新生成 baseline、未新增 suppression。
- 已在隔离的 `Pixel_10_Pro_XL` 模拟器重新生成 Baseline Profile，共 22,671 行。生成内容包含现行 `RoomLedgerRepository`、`RoomReviewQueueRepository`、`RoomPreferencesRepository` 与 `LocalDataBackupRepository` 等类，不再包含已删除类的 FQCN；没有手工全局替换生成文件。
- 首次生成暴露宏基准返回账目列表时仍等待旧标题“默认账本”；`BenchmarkApp.returnHomeFromLedgerDetail` 改为等待基准造数条目后，限定 `BaselineProfileGenerator` 的生成任务 1/1 通过。性能旅程以 Macrobenchmark 规则在同一隔离模拟器运行，6/6 通过。
- 阶段 5 直接新增 2 个架构测试文件，没有新增或删除生产源码文件。当前工作区相对 HEAD 共 199 个路径（108 个修改、33 个删除、58 个未跟踪），包含用户原有改动及阶段 1-5 的文件迁移；各阶段的职责增删与重复契约清理见对应 Execution Record。

### 10.2 验证

- 架构专项共 10 项测试通过；`:apps:android:detekt` 通过，`:apps:android:compileBenchmarkKotlin` 与 `:apps:android:assembleDebug` 通过。
- Android 全量 `:apps:android:testDebugUnitTest` 最终通过（110 个测试套件、627 项测试，0 失败、0 错误、0 跳过）。首次全量运行中 `AccountSessionCoordinatorTest.deletionPersistenceFailure...` 得到 `StaleSession` 而非 `PersistenceFailed`，报告同时提示主线程队列仍有任务；未改代码，完整类 12 项复跑及随后全量复跑均通过，记录为一次测试隔离/并发抖动。
- `:apps:android:assembleRelease` 通过；`android-release.apk` 经 `apksigner verify --verbose` 验证为 v2 签名、1 个 signer。根 `build` 通过（286 个任务路径，57 executed、24 from cache、205 up-to-date）。
- `:apps:android:generateReleaseBaselineProfile` 的 `BaselineProfileGenerator` 1/1 通过；冷启动（无/有 profile）、账目滚动与详情（无/有 profile）、报表 1k/10k 共 6 个性能旅程通过。最终 `git diff --check` 通过。

### 10.3 偏差、风险与未执行项

- 性能旅程使用隔离模拟器，并显式设置 `androidx.benchmark.suppressErrors=EMULATOR`；结果只能证明旅程可达和 profile 配置可执行，不能作为真实设备性能数字。用户未授权使用已连接真机，因此未执行真机业务或性能验收。
- Baseline Profile 首次生成还被同一 connected test 任务中的非生成器 `Batch9CriticalPathsTest` 影响；最终以类过滤只执行仓库约定的 `BaselineProfileGenerator`，没有把其他仪器测试结果表述为 profile 验收。
- Release/R8 仍报告微信 SDK 非线性控制流缺少 stack map table 的第三方警告，并有既有 Clipboard API 弃用提示；均未导致构建或签名校验失败，本阶段未升级依赖或扩大清理范围。
- 未修改 Room v13 schema、Shared API、同步协议、备份格式、持久化枚举、Accessibility 行为或依赖；未 commit、push 或创建分支。
