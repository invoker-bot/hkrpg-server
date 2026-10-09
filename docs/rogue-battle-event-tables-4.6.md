# 4.6 战斗事件原始表与波次准备

Java 新增 `RogueBattleEventTable46`，完整解析 `ExcelOutputGameCore/BattleEventConfig.json` 的 498 行和 `BattleEventData.json` 的 458 行，共 99,243 原始字节。九条经典共鸣的角色配置路径、技能、保留能量标记、队伍、子类、速度、覆盖属性和当前波次关卡要求均保留。这是显式调用的原始表和准备层，尚未连接在线实体工厂或客户端战斗。

配置反序列化按完整本机方法的 16 位掩码顺序读取；数据表按 8 位顺序读取。字段顺序不能直接照元数据声明顺序复制。覆盖属性另有两位掩码和 227 个已核对枚举常量。全表 820 个覆盖属性值及 488 个参数字均为标准定点原始数，不能先转浮点再生成。缺字段、显式 null 数组及空数组保持区别。文本 hash 保留完整无符号 64 位位型，Java long 可为负。

经典事件 41001–41009 均为 Neutral 队伍（3）、Assist 子类（3）、HardLevel 和 EliteGroup 均为 true，覆盖 BaseHP（2）为 `90 << 33`。41003 速度为 `60 << 33`，其余为 `100 << 33`。九条原始数据行的技能均为事件编号乘 100 加 1/2；角色路径使用来源 120/122/123/124/125/121/126/127/128 的明确映射。

`prepare` 先按事件编号查询原始配置。缺行返回空；两个关卡标记均为 false 时，准备成功而 Stage 行为 null，且不访问关卡提供方。任一标记为 true 时，必须按明确提供的 **CurrentWaveStageID** 查询原始 Stage 行；缺失返回空，不以 HardLevelGroupID、默认 Stage 或其他表回退。来源链为 GameWorld 的 BattleInstanceRef +0x98、BattleInstance 的 TurnBasedGameMode +0x18、该对象 CurrentWaveStageID +0x2a0。调用方提供的行必须与查询编号一致，提供方异常保留。

读取器的非负 Java int 身份、8 MiB 记录、200,000 行、512 项数组、64 KiB 字符串、规范 varint、严格 UTF-8、已核对枚举及标准定点数限制是有限项目规则。拒绝未知掩码、重复身份、越界、截断或尾字节；不可变结果不改运营资源，不在运行时读取游戏目录。

来源是指定本地 4.6 客户端的只读分析，未导入参考项目代码。冻结夹具 `rogue-battle-event-tables-4.6.json` 的换行规范化 SHA 为 `2b0987ec82d7405bc25aed170eb6a4e536433c6acfb33b6099f46ce1f4702a05`。读取脚本重新提取两份完整表并核对索引、范围、SHA、字节、全部行和经典子集；同时核对八个完整 PE 方法共 5,391 字节，涵盖配置两种读取、数据两种读取、覆盖属性读取、事件表查询、Stage 表查询和 PrepareCommonBattleEventCreate。片段不计入完整方法证据。

三项真实断言先失败（错误数 0），再通过实现。新增 Java 回归覆盖原始九事件、完整行范围、不可变结果、掩码/null/空数组、宽文本 hash、原始定点数、缺行、当前波次查询、提供方异常和有限输入；10 项 Python 检查核对完整本机身份、原件与解析边界。

全量 `scripts/build.ps1`、8,978 项 Java（373 个测试类）和 494 项 Python 均通过；2,783 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-battle-event-tables-verification.json`，封存 JAR 为 `build/rogue-battle-event-tables-8978-certified.jar`，SHA `cfb80cfa5520c20cc0a9a98f13e30561b7b4852910ecbd3955fdbc9a2ebac460`。

完整事件实体创建、前后回调、Ability/Modifier 执行、持有祝福资格、在线世界上下文、IFix 分支、全部剧情和小游戏，以及官方客户端 UI/动作/伤害仍未完成或验收。既有[九条目录](rogue-resonance-classic-events-4.6.md)、[74 项查询](rogue-resonance-maze-query-4.6.md)和[参数转交](rogue-resonance-event-params-4.6.md)的隔离验证不代表完整共鸣战斗效果。
