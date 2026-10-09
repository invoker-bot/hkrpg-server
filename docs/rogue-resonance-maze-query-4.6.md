# 4.6 经典共鸣祝福参数查询

本批将 `SetDynamicValueByMazeBuffConfig`（任务 2847）的有限标准数执行投影接入 Java，并编入九条经典共鸣的 74 项原始查询。调用方显式提供原始祝福行和变量上下文；尚未把此层接入在线战斗、持有祝福或官方客户端动作。

## 原始规则与实现

`MazeBuffParamKey` 的字段为 `MazeBuffID +0x10`、`Level +0x14`、`Index +0x18`。完整反序列化方法 `110276 / 0x1d8350b0–0x1d8351ec` 在读掩码前把等级置为 1；只有存在 `Level` 字段时才覆盖。因此缺省等级为 1，显式 0 保持 0。九条经典任务均省略等级，不能把缺省误解为 0 或依据玩家已强化等级回退。

执行方法 `512714 / OnTaskBegin` 在进入时先置状态 `0x9999`，随后按查询表的顺序处理。解析方法 `512715` 使用精确 ID/等级查找 `MazeBuffExcelTable.GetData`，再读取 `ParamList[Index - 1]`；行、数组或索引不成立会被该解析方法捕获并返回失败。失败条目不写变量，循环继续。成功条目才调用独立写方法 `512716`，按完整输出名称哈希与配置的 `ContextScope` 选择变量容器，再执行已有单元的写入规则。写入阶段的失败没有该解析捕获保护；先前写入保留，后续条目不再查询。

`RogueResonanceMazeQueries46.sourceKey` 对缺省等级做上述初始化；`classicQueries` 返回不可变原始列表；`Task` 保留 NEW/DONE 状态顺序；`execute` 按顺序读取、写入并返回项目诊断用的失败列表。`writeRequests` 表示已向目标容器提交写入，既不表示值发生变化，也不表示战斗效果成功。`Rows` 是显式来源接口，不自动合并运营表，不按未核对的加载顺序覆盖重复行，也不推断持有祝福资格。

有限项目边界为非负 Java int 的 ID/等级/索引、最多 255 条查询，以及既有变量名称与标准定点数范围。同名条目、来源身份不符或未知输入被拒绝；这些输入限制和诊断分类是项目规则。Java `Error` 不当作来源查询失败吞掉。原始定点字直接保留，例如 `ADF_612626_1` 为 `4294967300`，不能用 `ONE / 2` 的 `4294967296` 替代。

| 事件 | 能力 | 原始查询数 |
| --- | --- | ---: |
| 41001 | StageAbility_612020 | 9 |
| 41002 | StageAbility_612220 | 5 |
| 41003 | StageAbility_612320 | 12 |
| 41004 | StageAbility_612420 | 6 |
| 41005 | StageAbility_612520 | 9 |
| 41006 | StageAbility_612120 | 9 |
| 41007 | StageAbility_612620 | 7 |
| 41008 | StageAbility_612720 | 14 |
| 41009 | StageAbility_612820 | 3 |

74 项均能在固定原始三表中按 1 级和原始索引解析。41009 仅查询 `ADF_612821_1`、`ADF_612822_1`、`ADF_612823_1`，没有为另两个输出变量增加查询。九条隔离回归将原始基础行经 `StageParamEntry` 写入模板，再执行查询、转交事件参数；首次战斗标记、固定卵层零值和未查询的智识变量保持原始语义。

## 来源与验证

全部内容来自指定本地 4.6 客户端的只读分析，未导入参考项目代码。`rogue-resonance-maze-query-4.6.json` 固定完整本机方法、原始配置、字段及类型槽；规范化换行 SHA 为 `8fb8344a248dee77c3924ff5288b11e2028dad697b45ffb225ac7cfb7fb57685`。读取脚本重新核对 GameAssembly、元数据、PE 方法边界、七个完整方法共 17,371 字节、任务配置类型槽 `0x9378870` 和运行类型槽 `0x93a24d8`；不把构造方法附近的一段指令当作完整方法证明。等级初始化证据取自完整配置反序列化方法。

完整方法包括 517210 注册表、517294 工厂、512714/512715/512716 执行链、110276 参数配置反序列化、94604 精确表查询。工厂分配类型 `LPMEAGPFNHE`，并将配置与上下文保存为 `+0x18/+0x20`；配置类型为 `SetDynamicValueByMazeBuffConfig`。原始完整父能力表、另外两条选中能力与完整布局沿用[九条目录证据](rogue-resonance-classic-events-4.6.md)，原始三表沿用[定点表证据](rogue-maze-buff-table-4.6.md)。脚本先重新提取并核对原件，再使用冻结字节解析参数。

三项有意义的行为断言先失败，错误数为 0，再通过修复。32 项新 Java 回归覆盖原始目录、九条真实原始数值链、失败继续、写失败停止、部分结果保留、等级 0、空任务、显式作用域、容器裁剪与通知、输入限制和不可变输出；10 项 Python 检查覆盖本机身份、完整方法、原始字段/配置/数值及边界。

全量 `scripts/build.ps1`、8,937 项 Java（372 个测试类）和 484 项 Python 均通过；2,777 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-maze-query-verification.json`，封存 JAR 为 `build/rogue-resonance-maze-query-8937-certified.jar`，SHA `2c06b66a41955d3d75dea3df43f9ec84bb24b87e6254841b6732d3b35590948d`。

## 尚未完成

这是普通编译路径的显式服务端投影。对应本机方法还有 IFix 分支，本批未完整解码补丁执行，因此 `hotfixPatchDecoded` 保持 false。原始表加载/合并策略、实际持有祝福与能力绑定、事件创建/回调、DLC1 修饰器、在线任务调度和真实战斗上下文尚未接入；`nativeQueryTaskExecuted`、`heldBlessingsApplied`、`abilityExecutionVerified`、`clientGameplayVerified`、`completeResonanceRuntimeImplemented` 保持 false。全部参数能解析不代表九条玩法已完整。未启动或修改客户端，未改协议、命令号、版本声明和运营资源。
