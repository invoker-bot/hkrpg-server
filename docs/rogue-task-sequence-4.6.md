# 4.6 任务序列普通调度与回调连接

Java 新增 `RogueTaskSequence46`，接通明确提供的子任务 Begin/Tick/Reset/Dispose 和[事件回调接口](rogue-event-lifecycle-4.6.md)。它实现已核对的无全局插件普通路径；子任务由调用方提供，不自动把原始 TaskConfig 当作已执行的游戏动作，不绑定在线世界。

状态取自回合任务 TaskState（54773）：Ready `0x7777`、Executing `0x8888`、Success `0x9999`、Fail `0xaaaa`。另一同名枚举（37007）的 0/1/2/3 不能混用。Begin 以零 delta 推进一次；每次扫描从首个子任务开始，成功任务跳过，Ready 任务先 Begin。等待任务在同一次 Begin 收到 delta 为 0 的 Tick；已完成就立即继续后续任务，仍未完成则返回父状态。Tick 只在父 Executing 状态推进，并把实际 float delta 传给子任务。

失败传播保留原始分支顺序。Begin 后直接 Fail 的子任务使父 Fail；只向其后方、仍 Ready 且实现匹配接口的任务发出未启动失败通知。Tick 中才变为 Fail 的子任务不会在当次自动把父 Executing 改为 Fail；下一次扫描才处理。Tick 后 Success 分支先继续扫描，再检查后续 Begin 返回后的父终态；子任务同时把父置 Fail 且自身成功时，后续 Ready 任务可先 Begin，或者全部完成后父改为 Success。不能把这些分支改写成统一的立即取消。

演出即时完成使用 TaskContext 的 `FinishImmediatelyInPerformanceFlag +0x9a`；标志开启且子任务实现 AJGHLJDPNMP（54779）对应投影接口时走即时入口，否则正常 Tick。后续未启动失败通知接口槽 `0x93c4e60` 对应 BHEFPMFGNBK（54780）。Java 的 `ImmediateTask`、`UnstartedFailureTask` 只是已核对接口的明确投影，不为普通任务补造支持。

Reset 依原顺序重置全部子任务，全部成功后才把父置 Ready；失败前已发生的变化保留。`runOnce` 对应无插件的普通序列辅助路径：Begin、保存结果、若 Executing 则报告等待诊断、Reset、返回重置前结果。诊断目标已从元数据确认是 SuperDebug.LogError；Java 由明确 Consumer 提供诊断，不假定等待结果必然抛错。Begin、诊断或 Reset 抛错不增加隐含 finally。回调适配器在事件帧压入期间执行该序列；任务闭包使用自己明确绑定的上下文，适配器不自动生成世界或实体变量容器。

Dispose 已核对按原顺序释放子任务、异常停止和未完成绑定保留。原生最后写入数组字段的全局指针值尚未核对，不能推定其是空数组或 null。Java 成功释放后清空明确绑定并拒绝再运行/重置，是项目的有限防错规则；`nativeDisposedArrayReplacementMatched` 保持 false。最多 512 子任务、非空不可变列表和等待时非负有限 delta 也是项目规则。

冻结夹具 `rogue-task-sequence-4.6.json` 的换行规范化 SHA 为 `140d2f3df95a52c2c67fed0a3343089fdd8e64c905db99b1b8a7f40e0173778a`。来源均为指定本地 4.6 客户端的只读分析，没有导入参考项目代码。八个完整 PE 方法共 5,278 字节：509750 Dispose、509751 Begin、509752 Reset、509755 Tick、509761 Step、509763 子任务 Reset、509764 后续通知、513995 单次辅助执行。读取器重新核对方法、状态常量、继承链、实例字段、两类接口类型槽和诊断方法身份；不把片段作为完整方法证明。

三项真实断言先失败（错误数 0），再通过修复。33 项新增 Java 回归覆盖零 delta、失败时序、后续接口通知、显式父状态、即时分支、未知状态、正常/异常 Reset、runOnce、嵌套序列、事件实体帧适配、释放及有限输入；10 项 Python 来源检查覆盖完整方法、字段/类型和边界。

全量 `scripts/build.ps1`、9,041 项 Java（375 个测试类）和 514 项 Python 均通过；2,795 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-task-sequence-verification.json`，封存 JAR 为 `build/rogue-task-sequence-9041-certified.jar`，SHA `06a994f04a8e23b074fd3cbf39b896350e56bf9233a6270c007d9cf57bbd7c01`。

原始 TaskConfig 工厂/动作分派、全局插件拦截、别名配置身份缓存/100 项 LRU/全局深度、真实实体工厂及 Ability/Modifier、全部剧情/小游戏和官方客户端 UI/动作/伤害仍未完成或验收。显式 Java 子任务和回调通过不表示整条原始能力已执行。
