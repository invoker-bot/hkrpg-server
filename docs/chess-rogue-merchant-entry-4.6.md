# 4.6 Chess 原教程至奇物商店的有限入口

本批实现独立的 Chess 教程运行：首次开始原始 `10111` 棋盘，完成 `201 → 202 → 203 → 204` 四场实际战斗，再进入 `10121` 原始起点 `Respite`，到 DLC 原 G35 的 NPC1013 查询和购买奇物。后续补齐完整资格Query以及健康记录5462保存离开、正常H2重启后5437恢复。默认开关为关闭；物理房间路由、战斗碎片和价格属于本服可审计策略。完整 DLC 玩法、Quit完成结算、奇物4的后续战斗复活效果以及游戏客户端菜单操作仍未验收。

## 原件与客户端消费链

数据固定于 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。测试夹具保存 88 份完整原文件的字节和 SHA256，以及 23 张表中所需的完整原始行；选行表另记全表源 SHA，不能把选行夹具说成完整 Excel 表。生产源校验读取实际完整表，逐项要求原行唯一匹配，并记录磁盘实际字节摘要。没有导入参考项目运行时代码。

两份完整教程为 `Config/Gameplays/RogueDLC/RogueDLC_Tutorial1_1.json` 和 `RogueDLC_Tutorial1_2.json`。第一层实际允许的最短路径只有三格 `MonsterNormal` 与一格 `MonsterBoss`；第一层不存在 `Respite`。本批保留所有原始格子和坐标，使用四场结算后的下一层请求，未新增 `BlockCreateRespite` 格子。第二层 `10121/201` 本来就是 `Respite`。

实际商店来源为 `P80201_F80201001` 的 G35、NPC1013、实例400001，以及 `Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json`。本机对应的 133 字节原件完全消费后，与固定提交的整个 JSON 树相等：原件 SHA256 `2af6ceaf742a9a795fbb473d1e53c6d31be76b5ec5e7bdb543d08730bd45c752`，源 JSON SHA256 `63b71691dbc10756ce7d9a3794f22c998c95c503ac795b82c7c97a9f4d1358b9`。原选项 `Go_Miracle` 等待字符串后执行 task2304 `RoguePropOpenPage`，原枚举 `RoguePropPageType.MiracleShop=2`，另一选项 `Go_Exit` 结束对话图。经典首世界 G22 只有强化/退出，不能代替这个奇物菜单。

本机 `RogueMiracleShopPage` Lua 的完整 `_setup_view` 调用 `_send_get_miracle_data_req`；后者取 `RogueAdventureModule.RogueShopInfo.MiracleShop` 并调用 `SendGetRogueShopMiracleInfoCsReq`。确认按钮把选中商品的 ID 传给 `SendBuyRogueShopMiracleCsReq`。原生商店数据请求使用当前谈话 NPC 的实际实体 ID，服务器因此要求真实加载的 G35 actor，不能用 NPC 定义 ID 或实例400001代替实体 ID。这里是静态消费链证据，尚未在游戏 UI 点选验证。

首次 Start 与恢复请求区别由完整 native writer、调用原型和响应消费者共同核对。固定输入如下。

| 操作 | 已核对命令 | 输入字段 | 有限行为 |
| --- | --- | --- | --- |
| 首次开始 | 5426 | 13 area、9 aeon、重复7 avatar | 仅原教程 area101/aeon6，非空唯一 owned formal 队伍 |
| 查询 | 5547 | 空 | 源约束的当前或已保存记录field8、完整资格profile field10；F2原缺口和后续分层证据见下文 |
| 恢复 | 5437 | 8 area | 只恢复已有同一 record，不创建首次运行 |
| 选择格子 | 5534 | 2 CellID | 当前战斗完成后，只能选择原路径紧邻下一格 |
| 实际进入 | 5468 | 13 CellID；15 MonsterGroupID 默认0 | 只进入已选格子；普通原 Lua 路径只设置 CellID |
| 下一层 | 5447 | 空 | 四场绑定原怪物的真实 WIN 均保存后进入10121 |
| 奇物菜单 | 5675 | 12实际 NPC entity；13默认0 | 原 Respite、原 G34/G35 及完整来源一致才建菜单 |
| 奇物取得 | 5656 | 12实际 NPC entity、6 MiracleID | 对已保存报价支付一次，取得同一 UUID |
| 保存离开 | 5462 | 原空请求 | 健康记录保存并实际返回普通scene；5419含stage14、HIM12、AEM6 |

最初首次入口夹具未使用5419，保存离开使用后续独立完整fixture与10项回归。完整WriteTo/Reader/消费者已确认5462→5419是保留记录返回；该有限运行时和KCP/H2验证见后文。普通 Enter 的 `MonsterGroupID=0` 来自原 Lua 默认值类型只设置 CellID 的调用路径和 native writer 的省略零值规则；服务器选池11/13901另列为策略，禁止把它们塞入客户端字段15。Lua 原件的完整结构/代码字消费已核对，opcode81/83 的前置 guard 尚未相对固定官方 Luau 头确认，不能由此宣称完整 Lua 语义或游戏 UI 接受。

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

场景转换使用真实 `Player.loadScene` 和生产加载器，保留原 `MonsterInfo` 对象、原 actor 实例和来源关系。每场结算绑定实际 Battle、Scene、lineup、格子、波次、原 Stage 和怪物。有限 guide 的 `BattleService → finishReport` 在原怪物仍在当前权威场景时核对报告，把 HP/SP、碎片和胜利收据同文档保存后，才移除这个实际怪物并清战斗/同步；不借普通任务死亡事件、日常结算回调或通用库存掉落。同步保存失败恢复预报告运行、旧 Battle result/stats，活怪和同一 Battle/lineup 保持可重试。LOSE 只保存这个独立运行的失败和有限恢复体力规则，不移怪、不发普通掉落。重复收据不奖励，失败不清格，不允许直接移动至商店。该实现及保存后队伍通知已有独立GREEN和F2网络/H2证据，分层记录见下文；旧手工 `onBattleFinish` 路径不能替代实际服务证据。

场景候选写入与位置在响应前保存。失败恢复运行字段、真实 Scene 引用、位置/旋转/入口和 avatar entity ID；同 floor 的 Rogue 新场景也要显式保存。菜单报价先保存再暴露，付款、奇物 UUID 与库存同一玩家文档提交；同步保存失败原位回退，不能留出免费奇物或空响应。这个边界覆盖同步保存异常，未验证数据库在提交后才抛异常的事务语义。

同一运行的 `ChessRogueLineup` 保留对象身份，每次 MP/HP/SP 更新原位修改，保存失败原位恢复；1408 支付 UseID 后1460施放不得因更换 lineup 对象重拒或二次扣费。正式队伍、经典宇宙和普通 extra lineup 的 HP/SP 不共享。704 对当前有限教程队伍编辑明确拒绝。原 `Go_Exit` 只是商店对话退出；5462保存记录与未恢复的5464 Quit完成结算分开，具体证据见后文。

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

结算通知修复阶段独立类 `ChessRogueMerchantEntry46Test` 为58项：原49项上增加1408→1460原始请求/同对象和重放、1408保存失败重试、四类HP/SP隔离、704明确拒绝、实际同 floor Enter 保存失败 Scene/entity 回退，以及真实服务保存失败与同报告重试。后者进一步锁住活怪 entity ID、旧 result/stats、settling 状态和唯一成功 save。新增三项只覆盖必要实际服务边界：四场原怪物的报告结算后才能换层至 G35 商店、错误战斗/Stage/重复或越界 bars/波次或完整原件变化不消费战斗、LOSE 保存失败恢复后同 Battle 重试只提交 Chess 失败状态。普通库存 drop callback 必须始终为0。

`build/chess-ammo-integrated-green-verification.json` / `build/chess-ammo-integrated-green-tests` 确认实际 Java GREEN：1032项、fail/error/skip为0，2523项源码输入稳定；其中本类58项全绿。这个 GREEN 包含报告原位回退与实际四场服务路径，尚未包含后来增加的权威队伍通知断言，不能复用为通知屏障或最终 jar 证据。

后续只读核对发现，专用报告保存了队伍体力条，却缺少原结算使用的 `PacketSyncLineupNotify`。`PVEBattleResultScRsp` 自身没有 lineup，主动查询能看到存档不能替代结算时的权威队伍通知。在实际 WIN/LOSE、保存失败及重复报告用例增加断言：失败不发通知，成功仅在 save 后发一次，生产 bridge 翻译后的4.6通知 HP/SP 与同文档存档一致。`build/chess-report-notify-red-verification.json` 确认58项/3处纯断言失败、2523项源码稳定，三处均为成功报告预期1次通知实际0；保存失败零通知屏障通过。最小修复只在成功提交、移除实际怪物并清 Battle 后发送既有队伍通知。`build/chess-report-notify-green-verification.json`确认130项全绿、2523项编译输入稳定，其中本类58项全绿；该专项与后续F2分别记录。

后续奇物取得通知增加三项，原类成为61项。实际5656只有付款/奇物UUID/库存同文档保存成功后，才在BuyRsp之前发送一次5647 `SyncRogueCommonActionResultScNotify`，mode field1=201、重复action field15→field3 `PKHKEEKIODC` oneof894 `IOMEMIEAPJO`→field13 `KOFMGCNIMIN`，miracle field2=4/use field1=0/total field4=1。完整20个原生函数核对generic201 provider和AddMiracleItem消费者；保存失败、重复付款或完整来源不符均不得发持有增量，经典域保持独立。`build/daily-wolf-chess-native-gaps-red-verification.json`确认本类61项/2纯断言失败，失败均为成功应有1次持有通知实际0；坏来源负项通过。最小修复后的`build/daily-wolf-chess-native-gaps-corrected-green-verification.json`确认187项全部通过、2525项编译输入稳定，其中本类61项全绿。测试内Gson同文档重载只算单元恢复，不能计为H2或游戏UI。

前批经典七站、原休息商店、Cavalry/Phone 的全量和原生结果各归其对应源码/产物，本批不得借用前批 13444 项全量或其它 jar 的 Native 报告。本批功能目录由主代理在实际验证后统一更新。仍待完成：真实游戏客户端点击首次入口/棋盘/商店，完整随机房间生成器与三层区域，骰子/命途战斗/事件/行动点消耗、商店之外的奇物效果以及Quit完成结算。原F2网络只覆盖其当时范围，不能覆盖后续Query profile与保存离开修复。


## F2实际网络、存储与全量构建

冻结F2为`build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar`，SHA256 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`。`build/chess-rogue-merchant-current-native-fifth.json`确认92个真实KCP请求/252项检查、3个网络快照、3次正常stop、2次同UID独立H2进程重启；每个停止后的H2检查均作两次独立Morphia读取，三份检查均只读且数据库文件字节不变。原始资源和运营数据没有变化，玩法运行、碎片、收据、购买和实际入口没有预先塞进存档。隔离账户只种入真实上游资格8013104的完成历史，未验其剧情解锁流程。

实际链为5426→四场原怪物战斗/真实报告结算→5447→原G34/G35/NPC1013→5675/5656→5647持有增量，再经两次5437和正常stop/H2重启恢复。同网络报告的受控HP/SP字段只验证实际handler、独立队伍与保存/通知边界；它不是官方客户端战斗计算重放。脚本没有执行客户端task2304/Go_Miracle、按钮或完整C++消费者，也没有把2304当网络命令。`clientGameplayVerified`、`gameplayExitVerified`、`blessingShopVerified`、`miracle4FutureEffectVerified`、`wholeChessRogueGameComplete`均为false。

父代理的`build/gameplay-cast-g61-chess-ammo-fixed-full-build-verification.json`确认本批全量497类/13622项，fail/error/skip为0、2525项编译输入前后稳定、实际构建退出0。上述源码/专项、同SHA F2的网络/H2与这次全量分别成立；全量或网络通过均不能证明完整客户端UI。较早Native探针的字段、夹具和存储解码失败证据保留；本节只使用第五次明确`passed=true`的报告，不把旧失败挪作成功。

## Query必需profile的后续缺口

F2成功5547回包仅有当前游戏field8。新的完整PE核对证明原Query消费者`caf6700..caf6849`在`caf674b`无条件把响应field10 `HIMJKAMHLMJ`传给`caf6980..caf6a2b`。后者经`cae8730..cae882b`要求HIM9 `MOOKMKEKMKC`非空；`RogueNousDiceData.Sync`在`df4e5c8→df4ecf3`存在直接空引用异常路径。HIM12 `DDAFEPBGOHK`也在`caf69c4→caf6a20`要求非空。DDAF的field9实际是`FDBDLBAPDBE`天赋记录，缺省可正常刷新返回，不能误写为MOOK骰子。

完整`ChessRogueAreaDataItem.SyncGameplayInfo`把HIM11的区域列表写入`IsUnlocked`、把HIM10写入`IsFirstRewardTaken`。固定原行area101→unlock1000020→完成8013104决定本有限入口解锁；四场服务器碎片奖励不是原首奖109011，不能据此声明领取。DDAF8由原消费者同步虚拟物品281013，原`RogueNous_Talent_TalentCoinItemID`与ItemConfig相等；当前服务器没有这个Nous币账本/取得路径，不能借经典天赋点、碎片或库存虚构其数量。只读有限profile投影保留未实现Nous的空集合/零币，同时提供必需共享子结构和实际101资格，未声称恢复Nous202。

`build/universe-miracle4-draft/profile-consumption-proof.json`记录90份整函数/88份PE unwind与2份终止叶函数、11份核心函数摘要、metadata偏移、冻结descriptor及5个完整原选行相对固定source commit的全表校验；没有执行DLL/Lua或修改客户端。

在本批提交后，正式独立类`ChessRogueNativeProfile46Test`只组合既有完整来源harness，不继承重复61项。使用已缓存JUnit与独立javac对冻结F2运行正式三项，`build/universe-miracle4-draft/profile-formal-red/verification.json`及fresh XML确认3项/2纯AssertionFailedError/1绿，0errors/skips/容器失败；8个生产类CodeSource均是这个F2 jar，完整src目录2523文件前后无变化。成功首次运行Query和四战/付款/同文档重载5437后的Query都实际缺field10；完整Act字节改变时严格拒绝且不发送profile的负项通过。此2523是独立工具的src文件清单，不与父代理包含额外构建文件的2525编译输入混计。

最小生产修复仅成功5547补源约束的field10与必需9/12，HIM11来自已保存任务资格，HIM10不冒充首奖，查询不创建新运行、不新增保存、币或奖励。主代理实际Gradle验证独立Profile3项及相邻Story130项全部通过，2530项编译输入稳定；随后纠正最初误填的旧测试类名称，单独运行实际`ChessRogueMerchantEntry46Test`61项全部通过，证据为`build/chess-profile-original-merchant-root-green-verification.json`。错误测试类没有冒称覆盖旧61项。新统一jar的Query与保存离开网络证据见后文，不使用旧F2的13622全量或92/252网络声称此修复通过。

## 健康记录保存离开与原命令恢复

完整原客户端`LeaveChessRogueAndSave` index615383、`cafb0a0..cafb17b`发送5462空请求，原Writer index163559为`1e5ba010..1e5ba020`终止叶函数；5419完整Writer/Reader分别为`1e6d2550..1e6d273f`、`1e6d2b60..1e6d2e0a`，字段4为retcode、6为AEM、11为PPAG、12为HIM、14为实际MAN场景与队伍。完整消费者index615334、`caf72f0..caf7502`在stage非空时把当前记录状态写为字面量2，然后把实际scene/lineup交给`df60630`进入地图；仍无条件同步HIM和AEM，所以成功回包必须同时提供资格profile12、AEM6以及实际普通场景stage14。该路径没有执行完成奖励或Quit。

已有记录getter index615062、`caddf50..cade020`只选状态字面量2的area；恢复请求index615384、`cafb180..cafb346`读取这个已有area的真实ID，发送5437 field8，实际响应为5483。这不把5437当作首次Start，也不把字面量2推断成经典Rogue enum别名。完整MazeMainPage的13145字节/30原型和11份整函数或终止叶函数包含于`src/test/resources/fixtures/chess-rogue-leave-and-save-4.6.json`；完整源文件88份逐件对固定TurnBasedGameData commit724b139作LF规范化的全字节比较。有限Lua原型23直接调用AndSave，24按Data.IsFinished选择AndSave或Quit，26调用Quit；父原型25存在尚未核对的原操作码，不能据此声称按钮、闭包绑定或完整页面已执行。

本服返回策略保存首次进入前普通Town/Maze/Train的plane/floor/entry/dimension与复制pos/rot，不冒称恢复官方物理出场映射。返回租约是完整plane/entry行、FloorInfo元数据及全部引用索引的已注册Group树的规范化SHA256，未注册资源以null标记保留，对象键排序而数组顺序保留。新运行首次保存即包含租约。旧记录无租约时只接受已固定的P20001/F20001001/entry2000101/dimension0，并要求完整原floor与G2经实际`FloorInfo.onLoad`后和生产加载树相等；租约只在成功离开保存中补入，保存失败不能先曝光新租约。

只有健康、来源与当前scene/entry一致、完整原房间组已加载且没有战斗或其它玩法占用的记录可以5462。实际`Player.loadScene`把普通位置和inside=false连同奇物UUID、库存、碎片、战斗收据和独立HP/SP放入同一Player保存；保存异常恢复原位运行、实际scene、普通位置、同运行队伍对象及avatar entity ID，且不发送成功回包。成功后5547只读发布同一个记录与完整资格profile，5437再装载原Chess物理房间，恢复inside=true并原样保留已付款奇物。重复离开、购买重放、坏原Act、错误entry/dimension/租约或原加载树改变均拒绝。已失败但仍在Chess内部的只读Query保持前批既有行为，失败记录不取得健康离开资格。

正式独立`ChessRogueLeaveAndSave46Test`最初6项对冻结F2的8个生产CodeSource实际执行，`build/universe-miracle4-draft/leave-formal-red/verification.json`及fresh XML确认6项全部纯AssertionFailedError、无编译/初始化/ProtocolException错误、0skip/error，完整src文件清单前后稳定。它们调用实际原命令、四场真实BattleService报告与付款，不继承重复旧61项。实施后必要返回租约用例把独立类扩至10项；最初缓存Position的测试改动未改变原Anchor.PosX，导致隔离9项中的一项测试假设失败，证据保留且不计作生产source问题。修正实际原字段后9项通过；随后新增无租约旧记录与真实G2来源改动用例，在当前owned类加F2相邻类的独立javac/JUnit中10项/1纯断言失败，锁定旧记录错误采用改动G2，最小修复再要求完整规范化原floor/G2相等。

`build/universe-miracle4-draft/leave-owned-green-fifth/verification.json`记录当前owned5类加冻结F2相邻类的隔离编译/10项回归结果，不能当项目级Gradle、完整新jar、H2或客户端UI证据。主代理随后真实Gradle专项`build/manual-sibling-save-blast-root-focused-verification.json`确认433项/0fail/error/skip、2535项输入稳定，包含Query3、Leave10与旧Merchant61。Quit5464/5472要求完整finishInfo并清运行；上游Data.IsFinished赋值及完成奖励语义仍未闭合，因此不注册Quit、不伪造finished或结算，也不能把正常stop/relogin称作游戏客户端UI验收。

## 统一新jar的保存离开网络与H2证据

新冻结包为`build/manual-sibling-save-blast-runtime.jar`，SHA256 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be`。新增独立工具`scripts/smoke-chess-rogue-saved-leave.py`保留旧商店Probe与Inspector源码、旧报告和lease；11项Python守卫全部通过。对新jar的实际javac/schema/storage codec预检为`build/chess-rogue-saved-leave-current-preflight.json`，新Probe核67份schema/10项检查，预检`nativeExecuted=false`，不冒称真实玩法运行。

实际命令为`python -X utf8 scripts/smoke-chess-rogue-saved-leave.py --jar build/manual-sibling-save-blast-runtime.jar --output build/chess-rogue-saved-leave-current-native.json`。报告SHA256为`eec239dc87a2c15d793df461c1c54ef837f5eda6ae4983cdd12ec55293a57b6e`，独占隔离目录为`build/smoke/chess-rogue-native-saved-leave-qzzkbe0j`，日志为`build/chess-rogue-saved-leave-current-native.log`。实际109个KCP请求/351项检查、4个网络快照、3次正常stop、2次同UID正常H2进程重启、3份只读H2检查，每份均作2次独立实际Morphia Player读取且数据库字节不变。前三批或旧F2的Native计数没有混入本报告。

首个快照使用原Probe只执行一次完整首次四战付款链，64请求/182检查；后续独立新Probe依次执行`saved-leave`15/62、`paused-resume`15/45、`resaved-leave`15/62，三份存档inside依次为false/true/false。实际5419含普通场景stage14、普通队伍HP/SP、完整HIM12和保留aeon6的AEM6；坏空请求与重复5462拒绝且不暴露成功stage。普通场景与已暂停的完整Query在H2重启后仍有效，原5437恢复原G34/G35/NPC1013，再查询已付款库存和拒绝重复购买。奇物4取得UUID、四胜收据UUID、运行UUID、棋盘、coins200、独立HP5000/SP2500、价格、库存与64字符返回来源租约在三份同UID存档中均保持，完整Chess文档只允许inside变化；经典运行、普通队伍/角色条、普通库存、普通币和日常均与真实首战前观察基线相等。

入口、每个快照和结束均核对同一jar、隔离config/policy、默认关闭配方、工具/source fixture和2342份原资源hash；loopback HTTP显式禁用代理，正常stop失败不遮蔽首个实际协议失败。资格夹具仅种入原8013104完成历史，原资格奖励在真实Start前后只读观察；没有预塞Chess运行、入场、碎片、胜利或奇物。这个新网络/H2结果不声明官方物理返回映射、原菜单task执行、完整客户端GUI、Quit/finished、Nous骰子/天赋/币、祝福商品、未来奇物复活效果或崩溃原子性。此时新jar的完整项目全量由主代理另行运行，433项专项与109/351网络/H2不能代替其结果。
