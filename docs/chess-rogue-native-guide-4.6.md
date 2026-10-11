# Chess 原教程至奇物商店的 Native 工具

本工具已验证限定的两层原教程：实际首次开始，四场服务器创建的战斗及原生报告结算，下一层原休息格查询和购买奇物4，以及同 UID 正常停服后的两次 H2 恢复。第五次最终冻结 F2 包实际通过92次请求、252项检查，包含每场742队伍同步及购后5647奇物持有增量。前四次工具失败原件全部保留，没有改写为通过。生产行为和真实服务失败回归见 [入口说明](chess-rogue-merchant-entry-4.6.md)。

## 来源与隔离前置

固定 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。原夹具 `src/test/resources/fixtures/chess-rogue-merchant-entry-4.6.json` 保存88份完整 JSON 原文件、23张原表中的56条完整选行及全表摘要；物理 floor 的82份原 group 文件全部保留。工具验证部署字节、整棵 JSON 树和完整来源清单，不能用删减群组、改 Act 或追加格子补入口。选择的表行不是整张 Excel 表。没有引入参考项目运行时代码。

`defaults/ChessRogueGuideRoutes.json` 的生产默认值必须为 `enabled=false`。工具只在新的 `build/smoke/chess-rogue-native-*` 目录启用 `true`，其它原路由及来源声明相等。原源目录、运营配置与数据库不修改。完整资源的2342个摘要在准备后、每次运行阶段及结束时复核。

隔离账号先通过普通注册和登录创建，使用实际 owned formal8001；不授予角色或改造队伍。仅 console 隔离夹具写入原任务8013104的 FINISHED 历史以提供资格，报告明确 `originalQualificationHistorySeeded=true`。console 保存前后校验除该任务记录外的状态相等，不直接调用领取或写入币、运行、胜利、奇物、商店和入口。真实 Start 的场景刷新随后把资格任务关闭为 CLOSED，自动发放原 Reward18013104（Item2、5000信用点）；完整 MainMission 和 RewardData 表及该完整原行按固定提交校验。

工具保留资格写入前的快照，并在实际 Start 回包后、首战之前暂停 KCP 探针，console 仅观察并保存实际5000信用点、CLOSED任务和已发奖励收据，不改玩家。此后四场真实 WIN/重放分别按实际 BattleID 暂停，记录相对首战前基线的差值。普通信用点和星琼差值必须为0，经典宇宙、日常、背包、普通队伍和角色状态相等；只对基线中实际数据库已存的普通队伍做完整对照，不把内存中的空队伍模板冒充存档。五次观察均证明玩家及存储文档未被观察动作改动。

## 将由新包执行的网络主线

1. `5426` 输入 area101、aeon6和实际 owned formal 队伍；随后 `5547` 查询整个原10111棋盘。保留全部8格、原坐标与尺寸，首次碎片为0。
2. 原路径 `201 → 202 → 203 → 204` 使用 `5534` 选择、`5468` 实际进入。Enter字段15保持原默认0，不把服务端怪物池写入请求。前三格实际加载G24+25、Stage80010011；最后格G31+32、Stage80139011。原实际怪物、波次及level53逐场核对。
3. 对当前真实怪物发送普攻请求，`attacked_by_entity_id` 为当前场景角色实体；`cast_entity_id` 是每场独立施放 UseID。收到实际 Battle 后再发送匹配 Battle/Stage 的原生 WIN 报告。四场分别积累100碎片，重复报告不得再次付币或消怪。工具不通过 console 写入 WIN。
4. 每场受控报告为实际队伍8001、HP50/100、SP25/100。结算后必须新增一次实际742 `SyncLineupNotify`，其中独立 Chess 队伍HP5000/SP2500；后续查询和H2核对相同值，重复结算不得新增同步。登录/房间通知以每场结算前的基线分开计数。这是协议结算夹具，不是游戏输入、伤害或官方战斗计算的验证。普通队伍/extra lineup、经典宇宙、背包、日常和余额与实际 Start 后、首战前的快照对照。
5. 四份真实结算收据保存后，空 `5447` 进入10121原起点201 Respite，实际场景P80201/F80201001/dim0/entry8020101、G34+35。
6. 实际G35/NPC1013/实例400001的服务器实体用于 `5675` 查询、`5656` 购买miracle4。余额400→200，一件取得 UUID、totalCount1/useCount0/effectParameters[1.0]，重复购买、未知商品及错误实体拒绝且不再扣款。最终新包在该购买回包之前实际收到一次5647公共动作：mode201、单个detail oneof894（AddMiracleItem）、miracle字段13下id4/use0/total1；重复、无效购买及恢复未补发取得通知。购买回包只刷新商品列表，不能替代这条持有增量。
7. 结束 KCP 会话并正常停止隔离服务，后续两次启动使用同 UID 和实际 `5437` 恢复该运行。完整运行 JSON、运行/结算/奇物 UUID、金币和当前场景保持；每次正常停止后用只读 MVStore 和两次独立 Morphia 读取核对原 H2 文档。

每个请求的原始回复 frame 在解析和断言之前写入日志，解析后的 actual 字段也先写入；失败保留实际观察与首次异常。每个阶段独立日志和快照，不以预期值补造回复。

成功结算缺少队伍同步的真实 Java RED 为58项中3项 `expected1/actual0`；其它55项通过，失败静默屏障保持。该失败不能由主动查询的正确 bars 代替。本工具在第五次新包中逐战证明实际742到达及5000/2500值，重复报告未发第二条；不能由此称官方队伍 UI 已刷新。

本服0初始币、每胜100、售价200、兼容池仅miracle4、level53和有限房间路由均为已声明服务器策略。原 NPC 与源图绑定，但 SceneNpcInfo 不直接传输完整 Act。`2304` 是原 DesignData 的 `RoguePropOpenPage` task tag，绝非网络命令；工具不会发送2304，也不执行客户端对话图或菜单。

## 已完成的工具检查

旧 E 包 `build/cavalry-phone-final-metadata-runtime.jar` 的 SHA256 为 `47a3cbfd611e4abfd369a9746d80f2f699aac53f8ff4c8a677545c65f5236f5c`。`build/chess-rogue-native-preflight-E-ready.json` 记录三个 Java 工具类独立 javac 通过、57个实际协议描述和3项命令/字段检查通过，原88文件/23表/56行/82群组完整。该报告明确 `nativeExecuted=false`、`frozenJarHasChessFeature=false`；旧包没有 Chess 新实现，不能把此预检记作功能验收。

追加742观察后，`build/chess-rogue-native-preflight-E-lineup.json` 再次通过三个工具类编译，实际描述58个、命令/字段检查4项；同一旧 E 包 SHA 不变，仍未执行 Native。原12项工具保护测试通过，保留第一次报告和日志。

`scripts/tests/test_smoke_chess_rogue_guard.py` 现有15项保护测试全通过，新增原怪物群组与首战/逐战观察门控均先锁定 AssertionFailure。它们验证已有报告拒绝、独占 lease、同一 TCP 端口复用拒绝、系统代理绕开、未就绪服务不能计正常停服、清理错误保留首次业务异常、源件/群组完整性以及预检不能启动游戏服务/Native客户端。mock 预检结果只用于测试工具分支。

`build/chess-acquired-miracle-codec-final-preflight.json` 在冻结 F 包上通过三个 Java 工具编译、62份实际描述、7项字段/命令/枚举检查，以及纯内存数据库中真实 PlayerLineup 的两次独立解码。只读 H2 Inspector 使用 DatabaseManager 同一组 IntSetCodec、IntListCodec、Int2IntMapCodec，不以 JSON 树代替实际 Player、队伍、角色及物品读取。该预检仍明确 `nativeExecuted=false`；第五次完整运行对新 F2 包重复同一预检后，另外证明其5647实际发送行为。

## 失败原件与已观察事实

冻结 F 包 SHA256 为 `eda71266f09ce2a489d67a8d49a3b53e38c0d134d21eb9d5ff995446bc2d4e2a`。四次实际运行均各自保留 log、lease、failed-run.json 和隔离目录，没有改写为成功报告：

1. 首次 `chess-rogue-native-cvtgua2y` 在登录及原队伍查询后发现工具使用了旧 formal 枚举名称。原4.6实际 formal3，已保存实际 descriptor RED，再最小更正名称；WIN仍为原1。
2. 第二次 `chess-rogue-native-0in3ai3q` 的 Start 成功，但工具将房间锚点G24/G31当作怪物群组。原 MonsterList 实际在G25/实例200005及G32/实例200001；来源保护13项中1项真实断言失败后修正，无生产变更。
3. 第三次 `chess-rogue-native-xccgwsn3` 完成64请求/176检查、四条742和商店400→200，正常停服一次；H2检查将资格前信用点0错误用作战斗基线，实际原任务奖励为5000，不能由此判战斗发放泛用掉落。
4. 第四次 `chess-rogue-native-az_mrmwq` 再次完成64请求/176检查和一次正常停服。五份只读观察确认 Start 自动5000、四场每场普通信用点/星琼差值0，其它泛用状态全相等。H2先读两个真实Player成功，随后在普通队伍读取遇到工具未注册 fastutil codec；独立存储解码回归复现后按生产配置修正。`build/chess-fourth-stopped-h2-readonly-codec-fixed.json` 对该已停服 H2 的独立物理副本完成全部真实Player/team/avatar/item和Chess收据检查，原件与副本前后SHA均不变。此补充只读核验明确 `newNativeExecuted=false`，没有完成第二、第三会话，不能把第四次失败改称完整Native通过。

## 最终 F2 实际通过

`build/chess-rogue-merchant-current-native-fifth.json` 与同名 `.log` 记录冻结包 `build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar`，SHA256 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`，开始与结束相等。实际隔离目录为 `build/smoke/chess-rogue-native-i49siwyr`；三阶段请求64/14/14、检查182/35/35，共92/252，三次正常stop、两次同UID进程重启均通过。

四场真实BattleID为249416710至249416713，原Stage/波次/群组与四份唯一结算UUID相符。Start后的普通信用点为5000，四场逐战增量均为0；星琼、经典宇宙、日常、背包、普通存储队伍和角色状态都相等。四场742每场一次，实际独立Chess角色HP5000/SP2500，重复报告未再同步。

成功购买时只收到一条5647，原frame Base64 为 `CMkBegsaCfI3BmoEEAQgAQ==`，解析mode201、detail894、miracle4/total1，use_count原默认0，unknown fields为空。该包在同次购买请求序号56的回包之前已接收；无效、重复及两次恢复未新增取得通知。

每次停服H2用只读MVStore与两次独立Morphia读取实际Player，并读取真实普通team/avatar/item集合；文件与集合未变。三次完整Chess JSON相等，run UUID `fafef589-ea1b-49c5-a3af-848daa21ac1a`、奇物取得UUID `84a8e20b-fcc7-434a-a9a8-813b1491b8bb`、四份结算UUID、金币200和独立HP/SP保持。原2342文件摘要、88原件/23表/56行/82群组、默认false策略及运营数据未修改。全量构建仍由根代理独立记录，不以本工具92请求作为全量或全玩法结论。

```powershell
python -X utf8 -m unittest discover -s scripts/tests -p test_smoke_chess_rogue_guard.py -v
python -X utf8 scripts/smoke-chess-rogue-merchant.py --jar build/cavalry-phone-final-metadata-runtime.jar --output build/chess-preflight-fresh.json --preflight-only
```

再次验证必须指定新的输出路径；已有第五次证据不能覆盖。脚本预先用 CREATE_NEW 保留 `.lease.json`，已有报告或 lease 均拒绝。HTTP/Mongo TCP 端口必须不同，只使用本轮拥有的进程，loopback HTTP 不使用系统代理。失败日志和报告不得覆写；停止失败另附而不替换首次异常。

## 验证边界

未识别真实 Chess 退出请求，本批只验证 transport 会话结束、正常停服和5437恢复，`gameplayExitVerified=false`。没有祝福商品；奇物4的后续经典战斗复活效果不由此有限 Chess 工具实现。没有游戏 UI 点选、客户端 Act 执行、官方房间生成策略、完整 Chess 三层玩法或崩溃后数据库提交原子性验收。受控原生结算 HP/SP 不能冒充官方战斗结果计算。其它功能的全量、旧 Native 和别的产物证据不纳入本批结论。
