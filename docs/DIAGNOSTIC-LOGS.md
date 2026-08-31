# 诊断日志操作手册

## 1. 用途与边界

诊断日志用于在设备内排查应用异常、本地持久化故障和自动记账实际采集结果，不是云端崩溃上报。

- Debug 默认开启，并在“我的”总览的“合规与隐私”下方直接显示独立“诊断日志”入口和页面；Release 默认关闭且始终不显示该入口或页面。
- 自动记账每次实际采集批次只记录一条诊断事件。支付宝批次保存 session、采集来源、系统事件原始时间、callback 入口、Coordinator 接收与实际采集时间、framework/基类/Coordinator/采集队列分段延迟、触发与根窗口、根选择来源、根/节点数量、查询/遍历/识别/持久化耗时、现有采集上限内的完整可读正文和最终结果/原因；非支付宝批次只保存相同的元数据，不保存页面正文。
- Coordinator、无障碍 Service、自动记账开关和 App 前后台变化逐条记录，Service 生命周期包含 `processStartElapsedMs` 进程启动标识；每秒事件 wave 区分 all/eligible/filtered callback，并汇总窗口、实际采集、失败和最大分段延迟。独立工作线程与主线程双探针每 2 秒运行一次，正常时不写日志，仅当实际调度比预期晚至少 2 秒时记录异常延迟。
- 应用异常可以保留完整异常信息。密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥、微信 code/票据、OpenID 和 UnionID 等认证秘密或身份标识始终在写入前清除或替换为 `[REDACTED]`。
- 日志不上传后端，也不进入账本备份或 Android 系统备份。

自动记账准入边界见 [PAYMENT-RECOGNITION-RULES.md](./PAYMENT-RECOGNITION-RULES.md)，合规口径见 [COMPLIANCE.md](./COMPLIANCE.md)。

## 2. 应用内操作

以下应用内操作仅在 Debug 构建的诊断页提供。页面默认加载最新 1000 条，并将每条事件直接以明文显示。自动记账批次使用以下单行格式（字段始终在同一物理行，正文换行显示为 `\n`）：

```text
yyyy-MM-dd HH:mm:ss | stage=capture | session=4 | event=WINDOW_STATE_CHANGED×1 | trigger=leading | eventAtFirst=yyyy-MM-dd HH:mm:ss | eventAtLast=yyyy-MM-dd HH:mm:ss | callbackEnterAtFirst=yyyy-MM-dd HH:mm:ss | callbackEnterAtLast=yyyy-MM-dd HH:mm:ss | receivedAtFirst=yyyy-MM-dd HH:mm:ss | receivedAtLast=yyyy-MM-dd HH:mm:ss | deliveryDelayMs=3 | frameworkDeliveryMs=1 | superDurationMs=1 | coordinatorDelayMs=1 | queueDelayMs=2 | triggerPkg=com.eg.android.AlipayGphone | triggerWin=82 | rootPkg=com.eg.android.AlipayGphone | rootWin=82 | rootBy=event | allRoots=0 | activeRoots=0 | lookupMs=6 | collectMs=3 | recognizeMs=2 | persistMs=3 | captureAt=yyyy-MM-dd HH:mm:ss | captureEndAt=yyyy-MM-dd HH:mm:ss | captureMs=14 | nodes=94 | text="支付成功\n￥2.99" | result=成功:inserted=1
```

- `event` 汇总本批次实际触发的无障碍事件类型和次数；`trigger` 区分首采、尾沿、最迟补采和持久化后的延迟补采；`rootBy` 为 `event`、`active` 或 `none`，`allRoots` 固定为 `0`；`nodes` 达到上限时以 `+` 标记；`result` 显示新增成功、未新增原因或失败原因。
- `AccessibilityEvent.eventTime` 与 `SystemClock.uptimeMillis()` 使用同一单调时钟基准。`frameworkDeliveryMs` 是事件产生至 callback 入口，`superDurationMs` 是基类处理，`coordinatorDelayMs` 是基类返回至 Coordinator 接收；兼容字段 `deliveryDelayMs` 仍表示事件产生至 Coordinator 接收。`eventAtFirst/eventAtLast` 由 callback 墙上时间反推；`queueDelayMs` 从该采集来源的预定执行点计算，避免把 500 ms 静默等待误算成主线程排队。
- `runtime | event=DISPATCH_DELAY` 的 `main_thread_paused` 表示工作线程正常而主线程延迟，`process_paused` 表示工作探针本身也延迟，`device_sleep` 表示 elapsed realtime 延迟但 uptime 正常。双探针都正常而 capture/wave 的 `frameworkDeliveryMs` 很高时，延迟位于 Android Accessibility framework 投递 callback 之前。
- 支付宝批次的 `text` 直接显示完整采集正文；非支付宝批次的 `text` 为空。页面仍支持级别、组件以及事件、原因、关联 ID 和正文的统一文本筛选。
- 从进入诊断页到离开诊断页，窗口全程启用 `FLAG_SECURE`，禁止系统截图或录屏；页面不再通过遮罩或二次确认隐藏正文。
- 关闭开关只停止写入新事件，不删除历史。
- “清空”需要二次确认，会删除现行 `.log` 分段、遗留 `.aadlog` 分段和遗留 Android Keystore 诊断密钥；开关状态保持不变。
- 本机数据删除还会清除日志和 Release 开启偏好。已经导出到 Downloads 的 `.log` 文件不会自动删除。

## 3. 存储与明文导出

每条事件先清除认证秘密并限制为最多 256 KiB，再以一行 JSON 编码追加到 `noBackupFilesDir/diagnostics` 下的 UTF-8 明文 `.log` 分段。`record()` 只做非阻塞入队，单个 IO worker 在 150 ms 短窗口内按顺序批量写入；刷新或导出会先等待此前队列项处理完毕。每段最多 1 MiB，全部现行分段最多 10 MiB；只有超过总上限时才轮转最旧分段。

遗留 `.aadlog` 密文分段不读取、不计入列表或统计，也不迁移成新格式；执行“清空”或本机数据删除时会一并删除。现行 codec 使用 schema 2 记录扩展字段，并继续读取既有 schema 1 JSON 行。

“明文导出 .log”无需口令，直接在 Downloads 生成 UTF-8 文本文件。导出内容与应用内单行展示一致；文件离开应用私有目录后由用户自行保管或删除。诊断日志改为明文不改变账本备份：所有账本备份仍使用口令加密。

不要把设备内分段、Downloads 导出文件或其中的支付宝正文提交到版本库、粘贴到 Issue/聊天或放入共享目录。

## 4. 新增生产事件检查

新增事件前必须确认：

1. 每个实际自动记账采集批次只产生一条事件，session、采集来源、事件/callback/接收/采集时间、framework/基类/Coordinator/队列分段延迟、窗口、根选择、事件次数、根/节点数、阶段耗时和最终结果/原因准确。
2. 只有支付宝实际采集批次保存完整采集正文；其他应用批次只保存元数据。
3. 所有认证秘密在写入前清除或脱敏，生产 Logcat 只输出固定元数据，不输出正文或完整异常。
4. Service/App 生命周期、all/eligible/filtered 事件 wave 和双探针异常可区分进程重启、设备休眠、进程暂停、主线程暂停、framework 延迟投递、采集排队和节点扫描慢，且正常探针不写日志。
5. 记录失败或队列满不改变业务流程，并有对应的批量顺序写入、UTF-8 明文、轮转、旧 schema 兼容、旧格式忽略/清空、无口令导出和全程 `FLAG_SECURE` 测试。
