# 4.6 Chess 原教程至奇物商店的有限入口

本批实现独立的 Chess 教程运行：首次开始原始 `10111` 棋盘，完成 `201 → 202 → 203 → 204` 四场实际战斗，再进入 `10121` 原始起点 `Respite`，到 DLC 原 G35 的 NPC1013 查询和购买奇物。默认开关为关闭；物理房间路由、战斗碎片和价格属于本服可审计策略。完整 DLC 玩法、奇物4的后续战斗复活效果以及游戏客户端菜单操作仍未验收。

## 原件与客户端消费链

数据固定于 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。测试夹具保存 88 份完整原文件的字节和 SHA256，以及 23 张表中所需的完整原始行；选行表另记全表源 SHA，不能把选行夹具说成完整 Excel 表。生产源校验读取实际完整表，逐项要求原行唯一匹配，并记录磁盘实际字节摘要。没有导入参考项目运行时代码。

两份完整教程为 `Config/Gameplays/RogueDLC/RogueDLC_Tutorial1_1.json` 和 `RogueDLC_Tutorial1_2.json`。第一层实际允许的最短路径只有三格 `MonsterNormal` 与一格 `MonsterBoss`；第一层不存在 `Respite`。本批保留所有原始格子和坐标，使用四场结算后的下一层请求，未新增 `BlockCreateRespite` 格子。第二层 `10121/201` 本来就是 `Respite`。

实际商店来源为 `P80201_F80201001` 的 G35、NPC1013、实例400001，以及 `Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json`。本机对应的 133 字节原件完全消费后，与固定提交的整个 JSON 树相等：原件 SHA256 `2af6ceaf742a9a795fbb473d1e53c6d31be76b5ec5e7bdb543d08730bd45c752`，源 JSON SHA256 `63b71691dbc10756ce7d9a3794f22c998c95c503ac795b82c7c97a9f4d1358b9`。原选项 `Go_Miracle` 等待字符串后执行 task2304 `RoguePropOpenPage`，原枚举 `RoguePropPageType.MiracleShop=2`，另一选项 `Go_Exit` 结束对话图。经典首世界 G22 只有强化/退出，不能代替这个奇物菜单。

本机 `RogueMiracleShopPage` Lua 的完整 `_setup_view` 调用 `_send_get_miracle_data_req`；后者取 `RogueAdventureModule.RogueShopInfo.MiracleShop` 并调用 `SendGetRogueShopMiracleInfoCsReq`。确认按钮把选中商品的 ID 传给 `SendBuyRogueShopMiracleCsReq`。原生商店数据请求使用当前谈话 NPC 的实际实体 ID，服务器因此要求真实加载的 G35 actor，不能用 NPC 定义 ID 或实例400001代替实体 ID。这里是静态消费链证据，尚未在游戏 UI 点选验证。

首次 Start 与恢复请求区别由完整 native writer、调用原型和响应消费者共同核对。固定输入如下。

| 操作 | 已核对命令 | 输入字段 | 有限行为 |
| --- | --- | --- | --- |
| 首次开始 | 5426 | 13 area、9 aeon、重复7 avatar | 仅原教程 area101/aeon6，非空唯一 owned formal 队伍 |
| 查询 | 5547 | 空 | 返回当前独立运行及完整当前原棋盘 |
| 恢复 | 5437 | 8 area | 只恢复已有同一 record，不创建首次运行 |
| 选择格子 | 5534 | 2 CellID | 当前战斗完成后，只能选择原路径紧邻下一格 |
| 实际进入 | 5468 | 13 CellID；15 MonsterGroupID 默认0 | 只进入已选格子；普通原 Lua 路径只设置 CellID |
| 下一层 | 5447 | 空 | 四场绑定原怪物的真实 WIN 均保存后进入10121 |
| 奇物菜单 | 5675 | 12实际 NPC entity；13默认0 | 原 Respite、原 G34/G35 及完整来源一致才建菜单 |
| 奇物取得 | 5656 | 12实际 NPC entity、6 MiracleID | 对已保存报价支付一次，取得同一 UUID |

`5419` 未识别，未纳入夹具和运行时。普通 Enter 的 `MonsterGroupID=0` 来自原 Lua 默认值类型只设置 CellID 的调用路径和 native writer 的省略零值规则；服务器选池11/13901另列为策略，禁止把它们塞入客户端字段15。Lua 原件的完整结构/代码字消费已核对，opcode81/83 的前置 guard 尚未相对固定官方 Luau 头确认，不能由此宣称完整 Lua 语义或游戏 UI 接受。

## 本服路由策略

参考 MarchHoney 固定 AGPL 提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083` 只用于核对公开状态语义；公开树缺少外部 `ChessRogueRoomGen`。因此不能声称恢复了官方 `RoomID → physical floor/group/pool` 生成器。本服把有限原房间、原 `RoomSections=[0]` 和 `RogueSubMode.ChessRogue` 约束写入 `defaults/ChessRogueGuideRoutes.json`，不修改任何原场景、NPC、Act 或客户端文件。

| 原格子类型 | 本服选定原 RoomID | 原物理组 | 本服选池与战斗 |
| --- | --- | --- | --- |
| MonsterNormal | 2111141 | P80201/F80201001、G24+25，原怪物实例200005 | pool11 / RogueMonster111 / Stage80010011 |
| MonsterBoss | 1210602 | 同 floor、G31+32，原怪物实例200001 | pool13901 / RogueMonster139011 / Stage80139011 |
| Respite | 1310601 | 同 floor、G34+35，原 NPC1013/400001 | 原 Act 的 MiracleShop 入口 |

固定维度0、入口8020101。Level53或54必须来自原难度行；默认53。经济默认初始碎片0、每场实际胜利100、奇物基价200、最大库存3；当前兼容池仅原奇物4一件，未增加不存在的商品。初始行动点为明确服务器配置，本批不恢复骰面、位面倒计时或其它消耗规则。商品所有权、次数及 effectParameters 保存了该原奇物的已识别元数据，但本批不执行其未来战斗复活效果，也没有祝福商品菜单。

`enabled=false` 随默认文件安装。默认安装仅补缺失文件，不能覆盖运营配方。可配置范围只包括开关和限定经济/等级数值；原 source commit、完整来源清单、原棋盘、路径、房间/NPC 映射及兼容池必须保持声明一致。缺原件、原行、磁盘摘要变化，或实际 `FloorInfo.onLoad` 后的完整加载树不等，均拒绝入口/商店，不能忽略 `ValueSource` 或 NPC 图。

## 存档和实际场景边界

`GameplayState.chessRogue` 保存运行 ID、原棋盘进度、四场独立胜利收据、碎片、队伍 HP/SP/MP、商店报价、库存与奇物取得 UUID；普通队伍与经典 `RogueRun` 分开。首次开始会创建新运行，5437恢复不创建新 record。任务8013104必须已完成；本批恢复入口不自动完成该任务，也不包含其上游剧情流程。其它玩法占用、未 owned/formal 队伍和未知 Start/Enter 字段拒绝。

场景转换使用真实 `Player.loadScene` 和生产加载器，保留原 `MonsterInfo` 对象、原 actor 实例和来源关系。每场结算绑定实际 Battle、Scene、lineup、格子、波次、原 Stage 和怪物。有限 guide 的 `BattleService → finishReport` 在原怪物仍在当前权威场景时核对报告，把 HP/SP、碎片和胜利收据同文档保存后，才移除这个实际怪物并清战斗/同步；不借普通任务死亡事件、日常结算回调或通用库存掉落。同步保存失败恢复预报告运行、旧 Battle result/stats，活怪和同一 Battle/lineup 保持可重试。LOSE 只保存这个独立运行的失败和有限恢复体力规则，不移怪、不发普通掉落。重复收据不奖励，失败不清格，不允许直接移动至商店。该实现的统一 GREEN 待本轮完成，旧手工 `onBattleFinish` 路径不能替代实际服务证据。

场景候选写入与位置在响应前保存。失败恢复运行字段、真实 Scene 引用、位置/旋转/入口和 avatar entity ID；同 floor 的 Rogue 新场景也要显式保存。菜单报价先保存再暴露，付款、奇物 UUID 与库存同一玩家文档提交；同步保存失败原位回退，不能留出免费奇物或空响应。这个边界覆盖同步保存异常，未验证数据库在提交后才抛异常的事务语义。

同一运行的 `ChessRogueLineup` 保留对象身份，每次 MP/HP/SP 更新原位修改，保存失败原位恢复；1408 支付 UseID 后1460施放不得因更换 lineup 对象重拒或二次扣费。正式队伍、经典宇宙和普通 extra lineup 的 HP/SP 不共享。704 对当前有限教程队伍编辑明确拒绝。退出 Chess 的原请求尚未识别，本批不伪造退出；原 `Go_Exit` 只是商店对话退出。

## 固定客户端依据与复核

只读本机4.6，不启动游戏/launcher，不执行参考二进制，不改客户端：

- GameAssembly.dll SHA256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`。
- global-metadata.dat SHA256：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- DesignData index SHA256：`2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`。
- Lua index SHA256：`619db3bdd4730d92ada44132b7badcdde7d11159df4d2fea10fae36bd8e50346`，14 chunks/19644 entries；所分析页分别保存整件摘要。
- 原 Luau 头参考 MIT 提交 `421cc8158a752c8933a3d73555fd79df631a90b6`，仅作已核对 opcode 的静态依据。

`build/universe-entry-draft` 保留原件、完整 PE 函数边界、metadata/type/field slots、完整 Lua prototype、whole-source tree 比较。主关键函数可用现有 `scripts/analyze-client-46.py --method-indices` 重新定位；字段号依据完整 WriteTo/reader/cctor 和调用链，不能依据 metadata 字段顺序猜测。

| 固定 metadata index | 类型/方法 | 完整 RVA 区间 |
| --- | --- | --- |
| 163659 | AECEEKDICHA.WriteTo / Start | 1ddba480..1ddba6b4 |
| 163722 | MBJCCOCANEJ.WriteTo / Select | 1e9b8420..1e9b849f |
| 163475 | FPFBPLPIADI.WriteTo / EnterCell | 1e3dadf0..1e3daec5 |
| 163517 | KFFNAEDMDOD.WriteTo / ReEnter | 1e7eb040..1e7eb0bf |
| 615384 | ChessRogueModule.SendReEnterRogueReq | cafb180..cafb346 |
| 615017 | ChessRogueAreaInfo.SyncChessRogueLevelInfo | cadd310..cadd443 |
| 615204 / 615207 | Board.Sync / SyncLayout | cae3f50..cae3fdf / cae4b80..cae4db2 |
| 615128 | BoardCell.SyncAll | cae1c90..cae1f04 |

5447 的 LACJDDFOPHD.WriteTo index164136 是没有 PE unwind entry 的叶函数；`1e89b6c0..1e89b6cf` 完整终止控制流及下一 metadata 方法 `1e89b6d0` 明确界限，只读取 UnknownFieldSet。连同一个 padding NOP 的16字节 SHA256 为 `ae2f3faadc40156a0f3abff2ea813dedad2d1489719795bdf9cf7f97776ec2b8`。不能把它伪称有 unwind 边界。

实际通知已核对 money5424 field14、cell5580 board field1/重复cell field7、allowed5551 board field4/重复allowed field9；cell 的额外 Reason/ModifierSource 保留0，不猜其动画语义。层序采用从0计数；原 `GetLayerByIndex` 使用直接数组下标，其与 UI 当前层序的完整调用耦合仍待游戏验收。

## 本批验证记录

第一轮因另一个新请求的 raw6514 注册错误产生的 ProtocolException 不算 Chess RED，错误日志保留。修正该无关 dispatch 后，`build/ammo-loader-chess-red-tests` 和 `build/ammo-loader-chess-red-verification.json` 确认49项中48项纯 `AssertionFailedError`，无 errors/skips；唯一绿项为原件完整性。源码输入2515项稳定，首次5426和已有记录5437等实际未处理，才构成本批行为 RED。

首轮 Java 编译成功，54项中33项同因失败：误把只记录 properties/state 的 `Scene.getGroups()` 当作所有已加载组。真实 registry 是 `getLoadedGroups()`；已以这个 registry 保留完整组集相等门槛，未修改原图、中央 Scene 或来源校验。失败 XML 归档在 `build/chess-ammo-first-green-tests`。

实际服务用例第一次编译错误是误用 `BattleAvatar` 构造报告，正确字段类型为 `AvatarBattleInfo`；该次 `testExecutionCompleted=false`，不能使用旧 XML 计为 RED。修正夹具后，`build/chess-ammo-service-corrected-red-tests` / `build/chess-ammo-service-corrected-red-verification.json` 确认55项中仅这项失败，errors/skips为0，2523项源码输入稳定。三处真实断言为：完整运行快照中的普通 `dailyDay` 泄漏、同一个 Battle 的合法重试被拒、普通 drop callback 已调用一次。这一轮 HP/SP 的独立断言实际没有失败，不能把代码风险或后来加强的断言冒充当时已观察到的体力条泄漏。

当前独立类 `ChessRogueMerchantEntry46Test` 为58项：原49项上增加1408→1460原始请求/同对象和重放、1408保存失败重试、四类HP/SP隔离、704明确拒绝、实际同 floor Enter 保存失败 Scene/entity 回退，以及真实服务保存失败与同报告重试。后者进一步锁住活怪 entity ID、旧 result/stats、settling 状态和唯一成功 save。新增三项只覆盖必要实际服务边界：四场原怪物的报告结算后才能换层至 G35 商店、错误战斗/Stage/重复或越界 bars/波次或完整原件变化不消费战斗、LOSE 保存失败恢复后同 Battle 重试只提交 Chess 失败状态。普通库存 drop callback 必须始终为0。

`build/chess-ammo-integrated-green-verification.json` / `build/chess-ammo-integrated-green-tests` 确认实际 Java GREEN：1032项、fail/error/skip为0，2523项源码输入稳定；其中本类58项全绿。这个 GREEN 包含报告原位回退与实际四场服务路径，尚未包含后来增加的权威队伍通知断言，不能复用为通知屏障或最终 jar 证据。

后续只读核对发现，专用报告保存了队伍体力条，却缺少原结算使用的 `PacketSyncLineupNotify`。`PVEBattleResultScRsp` 自身没有 lineup，主动查询能看到存档不能替代结算时的权威队伍通知。在实际 WIN/LOSE、保存失败及重复报告用例增加断言：失败不发通知，成功仅在 save 后发一次，生产 bridge 翻译后的4.6通知 HP/SP 与同文档存档一致。`build/chess-report-notify-red-verification.json` 确认58项/3处纯断言失败、2523项源码稳定，三处均为成功报告预期1次通知实际0；保存失败零通知屏障通过。最小修复只在成功提交、移除实际怪物并清 Battle 后发送既有队伍通知，新增通知 GREEN 和新产物原生验证待本轮统一执行。

冻结 jar/KCP 首次开始至四场结算/商店、两次独立 H2 重读与正常停止记录待本轮完成后填入。测试内 Gson 同文档重载只是单元恢复边界，不能计为 H2 或真实游戏 UI。

前批经典七站、原休息商店、Cavalry/Phone 的全量和原生结果各归其对应源码/产物，本批不得借用前批 13444 项全量或其它 jar 的 Native 报告。本批功能目录由主代理在实际验证后统一更新。仍待完成：真实游戏客户端点击首次入口/棋盘/商店，完整随机房间生成器与三层区域，骰子/命途战斗/事件/行动点消耗、商店之外的奇物效果和真实退出。
