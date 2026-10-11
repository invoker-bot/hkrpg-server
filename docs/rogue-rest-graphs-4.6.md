# 4.6 原始休息区脚本的完整只读解码

本轮补齐已有 Python 固定读取器，使本机国服 4.6 的四份原始休息区脚本完整消费：G34 主图 8 字节、Transferred 条件包 178 字节，以及两份各 13 字节的休息区关卡图。转换后的四棵完整 JSON 树逐份等于固定来源中的完整 Git blob；没有裁掉不认识的字段、补默认值或把条件改成固定完成。

这是脚本解析基础。没有启动客户端、安装恢复资源、执行这些任务、修改 Java 玩法或验证实际菜单。G34 的原始选项仍指向祝福强化流程；本轮没有证明经典奇物商店入口可由客户端触发，相关玩法状态继续保持 `partial`。可关闭的第六站本服商店配方及其原生协议/H2 验证仍见 [原七站本服休整商店](rogue-rest-merchants-4.6.md)，两项证据分别记录。

## 固定来源

数据来源为 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。测试夹具 [client46-rogue-rest-graphs.json](../scripts/tests/fixtures/client46-rogue-rest-graphs.json) 保存四份完整原始客户端字节、对应完整 Git blob，以及两个后续动作图的完整 Git blob，均记录 SHA-256。读取器由本项目编写，未导入外部运行代码或将参考仓库设为运行依赖，保留原 LunarCore AGPL 来源。

本机客户端目录为 `C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current`，只读核对以下固定输入：

| 输入 | SHA-256 |
| --- | --- |
| `GameAssembly.dll` | `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3` |
| `global-metadata.dat` | `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130` |
| DesignData 索引 | `2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89` |
| 本轮字段/工厂 profile（CRLF 转 LF） | `3661c886777d497695bae42cd4386cc33547c80a96337c2f0cf2568da80d22ba` |

固定 profile 为 [client46-rogue-rest-graphs.json](../scripts/data/client46-rogue-rest-graphs.json)。它同时固定既有 mission profile 的 SHA，不会向既有 profile 写入新字段或把新任务自动推广到所有脚本。

| 完整逻辑路径 | 索引 entryKey | 字节数 | 原始字节 SHA-256 |
| --- | --- | ---: | --- |
| `Config/Level/NPCDialogue/P80001/F80001002_G34/DialogueMain_F80001002_G34_N400001.json` | `655f93fea15e870e` | 8 | `e1cbd6d63d93abcdf1ec182595ac1438bf28cc757b616b14799b40f65aa1f240` |
| `Config/Level/NPCDialogue/P80001/F80001002_G34/DialoguePack_Transferred_F80001002_G34_N400001.json` | `388e9b7a7d663be9` | 178 | `3c423df9b1ccbbb3fef72e3cc7d4a4b762e27e4917fe54338300cc79ea32e0f7` |
| `Config/Level/Maze/MazeRogue/Maze_Rogue_Tutorial_RestRoom_S1.json` | `cfb03f9d124878e7` | 13 | `1c8928b268a61cd3d8dbeeca55ffe23648c09c3c263abd401cd0d7c136b483c4` |
| `Config/Level/Maze/MazeRogue/Maze_Rogue_Group_RestRoom.json` | `a27881af41fd7abc` | 13 | `1c8928b268a61cd3d8dbeeca55ffe23648c09c3c263abd401cd0d7c136b483c4` |

四份原件来自同一实际 DesignData chunk：`7707e972d9afb983df824274f7ae0ce1.bytes`，完整 chunk SHA-256 为 `3f8c0a2375be589b337a5733250d30c531b86ef98c521c5a2c34547d6be59a4e`；读取时沿用已有索引/完整 chunk 的长度、MD5 和 SHA 核对。

## 原生工厂与 Reader 核对

每次只读导出先验证既有 mission 字段与原生工厂，再核对本轮七个实际类型、五个新增 Task 分支、一个新增 Predicate 分支，以及十三个完整原生函数。十三个函数包括两个工厂初始化器、六个分支工厂和五个 Reader；函数起止取 PE exception table 的完整边界，每个完整函数均有固定 SHA-256。没有只截取几十字节来推断后面的任务，也没有执行 DLL。

注册点实际写入 `tag * 8 + 0x20` 的工厂数组位置；读取器同时核对前一条 RIP-relative 槽位加载、实际方法槽位、factory 方法编号、分配类型槽位和 metadata 字段/继承/实例偏移。全部地址及完整函数字节 SHA 在固定 profile 中，统一校验入口为 [recover-client-rogue-rest-graphs.py](../scripts/recover-client-rogue-rest-graphs.py) 的 `verify_native`。

| 类型/编号 | 实际 methodSlot | factory RVA | Reader 或实际无载荷结果 |
| --- | --- | --- | --- |
| `CollectDataConditions` / Task1082，继承已有证据 | `0x9533260` | `0x1ceddf00` | `0x1d59c460`，保留原 TaskIDList / PerformanceIDList |
| `ShowMenu` / Task3259 | `0x95378c0` | `0x1d61b210` | `0x1dab40f0` |
| `WaitPredicateSucc` / Task3875 | `0x9538d08` | `0x1d625ae0` | 工厂分配并启用任务，无 Reader 载荷 |
| `SetAllRogueDoorState` / Task2697 | `0x9536628` | `0x1d41ce30` | 工厂分配并启用任务，无 Reader 载荷 |
| `SetRogueRoomFinish` / Task3067 | `0x9537290` | `0x1d4230e0` | 完整工厂包含 metadata 初始化和分配，无 Reader 载荷 |
| `AddMenuItem` / Task48 | `0x9531170` | `0x1ccd5080` | `0x1d35b640` |
| `ByCompareSubMissionState` / Predicate268 | `0x951e4b8` | `0x1d192430` | `0x1d4ad1d0`；复用已核对的子任务状态枚举 |
| `JsonEnum.ParseFromBinary` / method75371 | metadata 方法表核对 | 无独立 Task 编号 | `0x1d7724a0`，两个 ZigZag int32；没有对象字段掩码 |

`JsonEnum` 的 metadata 对象字段不能当作本次二进制格式。完整 `ParseFromBinary` 实际读取两个整数并做 ZigZag 转换；原 AddMenuItem 中 `28 14` 解为 `EnumIndex=20 / Value=10`。公共读取器只有在固定 profile 明确声明 `binaryEncodings.JsonEnum=zigzag32_pair` 时启用这五行分支，既有 profile 的行为保持不变。两个整数沿用原有 int32 溢出、非规范 varint 和截断拒绝规则。

未知 Task、Predicate、字段掩码、枚举值和未核对结构继续拒绝；AddMenuItem 原件没有出现的 `PerformanceType` / `TagContainer` 载荷不会被猜成字符串或空对象。无 Reader 工厂不消耗下一个任务的字节，输出 JSON 也不插入工厂默认的 `TaskEnabled=true`。

## 完整脚本与菜单边界

G34 主图只有 `RPG.GameCore.ShowMenu`。Transferred 包先收集子任务 `403000304`，然后保留两个独立 `PredicateTaskList`：

| 子任务状态 | MenuItemID | 原 DialoguePath |
| --- | --- | --- |
| `Started` / 1 | `403000304` | `Config/Level/Mission/4030003/Talk/Talk_403000304.json` |
| `Finish` / 2 | `403000399` | `Config/Level/Mission/4030003/Act/Act403000399.json` |

两项都保留 `MissionID=403000304`、`ExtraInfo=2` 和原图标 `20 / 10`。固定来源的后续 `Act403000304.json` 与 `Act403000399.json` 中 `ShowUI` 都是 `Ui.Rogue.Buff.RogueEnhanceBuffPage`。本轮只完整解码上表四份客户端原件；后续动作图作为完整固定 Git blob 核对，未宣称那些动作图已由本轮客户端 Reader 全部解析或执行。

两份 13 字节休息区图完整保留 `WaitPredicateSucc → SetAllRogueDoorState → SetRogueRoomFinish` 三个 Task，以及空的 `ValueSource.Values`。知道这些任务的实际编号与载荷格式，不等于服务器或客户端已经执行了等待谓词、开门和房间完成流程。

## 实际验证与复现

实现前先用已有公开 mission 读取器解码四份完整原件。初始五项测试中四项真实断言失败、一项来源自检通过，errors/skips 均为 0：G34 主图拒绝未知 Task3259，两份 13 字节图拒绝未知 Task3875，Transferred 拒绝未知 Predicate268。RED 日志保留为 `build/universe-event-draft/rest-ui-decode-red.log`；没有将 import 或初始化错误计为行为失败。

实现后专项 22 项全部通过，日志为 `build/universe-event-draft/rest-ui-decode-green-boundaries.log`。覆盖完整 Git blob 全树相等、212 个截断前缀、四份尾字节、真实原件中的未知任务/谓词编号和 AddMenuItem 掩码、JsonEnum 非规范/越界编码、固定 profile 变化、未知/重复/父目录路径、客户端目录输出拒绝、只生成报告、相同内容重跑、运营报告保留，以及客户端/索引/记录身份/读取后原始文件变化时不生成报告。导出边界测试只在隔离临时目录使用固定原件，并明确 mock 原生核对；它们不算本机 DLL 验证。

公共 Reader、mission、runtime group、DesignData/fallback 和本轮专项合批 97 项全部通过，errors/skips 为 0，日志为 `build/universe-event-draft/rest-ui-reader-regressions.log`。本机实际只读核对独立完成，报告 `build/universe-event-draft/rest-ui-complete-local-client.json`、日志同名 `.log`：四份原件全部消费，七个实际类型、五个新增 Task 分支、一个新增 Predicate 分支、十三个完整函数及固定文件/索引/chunk SHA 全部相符。

专项与相关回归命令：

```powershell
python -X utf8 -m unittest scripts.tests.test_client46_rogue_rest_graphs -v
python -X utf8 -m unittest scripts.tests.test_client46_level_graphs scripts.tests.test_client46_missing_missions scripts.tests.test_client46_runtime_groups scripts.tests.test_client46_design_data scripts.tests.test_client46_design_fallback scripts.tests.test_client46_rogue_rest_graphs -v
```

完整原生证据与四份原件的统一只读复现入口：

```powershell
python -X utf8 scripts/recover-client-rogue-rest-graphs.py --client-dir C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current --output build/universe-event-draft/rest-ui-complete-local-client.json
```

命令只有报告输出参数，没有资源安装参数；只接受四份固定规范路径，报告必须位于客户端目录外。不同内容的已有报告会被拒绝，相同内容可复用。原始字节只导出到自动清理的临时目录，未更改客户端、运营 `resources`、配置或数据库。

报告中 `clientExecutableStarted`、`clientModified`、`clientAccepted`、`scriptsExecuted`、`jsonInstalled`、`completeGameplay`、`clientMenuEntryVerified`、`classicMiracleMenuRestored` 均为 `false`。本轮没有 Java 运行变化或新 jar，既有七站、商店包 A/B 的构建、原生请求及 H2 证据不被这次静态解析扩大。
