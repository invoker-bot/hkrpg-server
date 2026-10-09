# 4.6 战斗事件创建任务与回调生命周期

Java 新增 `RogueBattleEventLifecycle46`，把创建任务的标准数编号求值、当前波次准备、参数快照、显式创建接口、前后回调参数栈和释放顺序接在一起。调用方仍须提供实体工厂和可执行回调序列；本批没有把原始 TaskConfig 自动当作已执行动作，没有创建在线事件实体或接入真实战斗世界。

任务起始状态为 `0x7777`。普通 OnTaskBegin 在编号求值前置为 DONE `0x9999`；随后按标准字算术右移 33 位取得编号，读取原始事件行及需要的 CurrentWaveStageID。准备不成立就不读取参数、不调用创建接口，也不运行回调。准备成立后逐项从明确来源上下文求值，生成不可变参数快照，再交给创建接口。前回调的调用时点由实体工厂负责；只有非空返回实体才运行后回调。DONE 是该创建任务的状态，不能表示战斗、能力或回调成功。

原始配置为 CreateBattleEvent（22975），运行类型为 NAPIKMAGDDI（58043）。运行字段 +0x30 保存后回调序列，+0x38 保存前回调序列；委托方法槽 `0x949f6e8` 的 usage kind 3 明确指向 536336，而不是按附近指令猜测。Dispose 顺序为后序列释放/清空、前序列释放/清空、委托清空；释放抛错时保留尚未完成步骤，不自动跳过或执行剩余释放。Native Begin 本身没有一次性调用保护，调用方须保持自己的任务调度和授权规则。

世界 ParamRegister +0x48 对应 AELMAJAHPCP（56185）。完整方法核对 push/pop/count/空栈 current 行为。Java `Frames` 只投影回调帧的事件实体身份：有实体、序列及寄存器才压入新帧；序列正常返回后弹出；嵌套调用按栈恢复。前后帧彼此独立。两个完整回调方法的 PE unwind flags 均为 0，没有 exception 或 termination handler，也没有代码中的 finally 清理。因此序列异常保留已压入帧并向上传递，创建或既有操作不被当作自动回滚；这是明确的普通分支边界。项目的 255 帧限制、输入不可变副本和嵌套恢复身份检查属于有限防错规则。

九条经典 OnAdd 的 CreateBattleEvent 原件均没有 OnBeforeCreateCallback 或 OnBattleEventCreate 字段，Java 不添加虚构回调。九条集成回归使用完整原始事件/祝福表，先装载 StageParamEntry 和执行原始 74 项查询，再把全部 155 项事件参数及明确当前波次交给隔离创建接口；接口使用测试对象，未宣称它是真实客户端或在线战斗实体。原始角色配置的后续 Ability/Modifier 仍须分别实现。

冻结夹具 `rogue-event-lifecycle-4.6.json` 的换行规范化 SHA 为 `654e100e4ee858173d8ce13c0ac19c6592611cfe576ecf980691bf6b7b2eb435`。全部来自指定本机 4.6 客户端，只读分析未导入参考项目代码。读取脚本重新核对 DLL、元数据、12 个完整 PE 方法共 4,499 字节、字段、委托方法槽及六个运行方法的 unwind 原件，同时重新提取完整父能力与两条追加选中能力以检查九条原始空回调。片段不计入完整方法证据；IFix 替代分支尚未执行或验收。

三项行为断言先失败，错误数 0，再通过实现。新增 Java 检查覆盖九条原始数值链、状态先置、准备和参数失败、快照、空实体/寄存器、正常/嵌套/异常回调、Dispose 各中断位置和有限输入；10 项 Python 来源检查核对本机身份、完整方法、原始字段、unwind、原始空回调和验收边界。

全量 `scripts/build.ps1`、9,008 项 Java（374 个测试类）和 504 项 Python 均通过；2,789 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-event-lifecycle-verification.json`，封存 JAR 为 `build/rogue-event-lifecycle-9008-certified.jar`，SHA `371183e86ea5ad99de91d175bc283322bee3e8835a9598564a26234459fb92f0`。

在线实体工厂、目标/施法者属性投影、Team/TotalDamageTeam、前后原始 TaskConfig 调度、真实 Ability/Modifier、持有祝福资格、IFix、官方客户端 UI/动作/伤害及全部玩法仍未完成或验收。已有[原始表](rogue-battle-event-tables-4.6.md)、[九条定义](rogue-resonance-classic-events-4.6.md)和[查询](rogue-resonance-maze-query-4.6.md)证明沿用各自边界。
