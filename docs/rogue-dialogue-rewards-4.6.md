# 原始 40398 事件与第一世界七站闭环

本轮补齐默认第一世界第五站事件房间 305 的实质阻塞：此前只发布 NPC 与三项不可用选项，房间无法 clear，不能进入第六站或最终首领。本服现在为原始事件 40398 安装明确的奖励配方；不再把缺失官方效果解释成固定完成，也不宣称已恢复官方事件效果。

## 原始来源与本服规则

固定数据来源为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。原始 NPC、`Opt40398.json` 与 `Act40398.json` 全文件以 SHA-256 核对，只允许 Windows CRLF 转 LF。源文件不匹配时，这项配方不可用。测试夹具保留三个完整 Git blob、三条原始 `RogueDialogueOptionDisplay` 行及六条 CHS 原文；中文文字从固定提交的 `TextMap/TextMapCHS.json` 读取，不从选项名称猜奖励。

| 原始选项 | 原始标题 / 描述 | 本服 schema 1 配方 |
| --- | --- | --- |
| 14101 | 给我一些启动资金吧 / 获得一些宇宙碎片。 | 取得配置的碎片，默认 100；同一 Player 存档写入收据和房间 clear |
| 14102 | 给我点好东西吧 / 获得1个随机祝福。 | 从已解锁、未持有、有真实等级 1 MazeBuff 的经典普通祝福中均匀抽取一个；先保存菜单，确认取得后同存档写入祝福、收据和 clear |
| 14103 | 什么也不做 / 你不需要。 | 无物品、无碎片；记录选择并 clear 当前事件房间 |

原始 `Opt40398.json` 仅有 `OptionID` / `DisplayID`，没有官方数量、概率或奖励公式。默认 100 碎片、均匀抽取、以本服局 ID 和该事件唯一 ID 固定随机种子、确认后 clear 均为本服规则。14102 不提供刷新，不生成奇物，不抽命途回响；正常取得第三个路径祝福后仍可由现有回响流程排入回响菜单。

4.6 协议沿用项目已有 LunarCore lite 4.2 + experimental 4.6 桥接。字段来源为 AGPL March7thHoney-OpenSource `fe00bdbda3c28ca33d01f171566aa35b5ab95083` 的固定描述符，以及既有本机 4.6 原生读取/发送证据。没有新增猜测命令，没有手改生成类，没有改 `GameConstants.VERSION` 冒充客户端升级；本轮新增 Java 为项目自行实现，参考仓库未成为运行时依赖。

## 安装、保存与恢复

默认 `data/RogueDialogueRewards.json` 经 `DefaultData.install` 安装。已存在的运营文件逐字保留。默认内容：

```json
{
  "schema": 1,
  "eventId": 40398,
  "enabled": true,
  "fragmentReward": 100
}
```

`enabled` 可以设为 `false`；原三字段配置省略此字段时沿用启用。数量必须为正整数且在 Java int 范围内；未知字段、未知事件、字符串布尔值或小数数量在加载时失败。其他事件继续保留未实现状态。

首次绑定合法 NPC 时，把源标识、局 ID、场景租约、碎片数和候选 ID 冻结到 `RogueDialogueRewardState`。发出场景 NPC 元数据或选项菜单之前先保存；已有旧存档租约但缺少新字段的情况也重新标记待保存。直接选择合法旧租约同样先经过保存屏障。保存失败不会发出奖励成功回复，菜单保存可重试，选项与确认操作会回退被修改的运行字段、收据和祝福收集记录。

选择和取得使用 Player 锁；重复领取、并发选择、重复字段、uint32 溢出别名、未知字段和截断报文被拒绝。14102 的局内菜单、事件来源、唯一租约、batch 和候选祝福必须同时一致；删除收据不会把孤立事件菜单变成普通战斗奖励。暂停、换房、外部场景或失配来源不能确认事件祝福。改碎片数只影响新冻结的租约，不重定价已展示的事件。

## 默认路线与验证命令

本服已有默认 `RogueMapGenByMap.json` 给原始 map 101 指定以下七站；这份指定池是本服配置，不是恢复官方随机房间生成算法。原始 `RogueMap`、房间、入口、加权敌人和事件数据保持原样：

| 站 | 房间 | 流程 |
| --- | --- | --- |
| 1 | 304 | 原始三个加权敌人，战斗结算、祝福确认后出口解锁 |
| 2 | 302 | 原始精英战斗、祝福确认 |
| 3 | 303 | 原始休整房，现有服务器规则自动 clear |
| 4 | 304 | 新站的原始三个加权敌人、祝福确认 |
| 5 | 305 | 原始 NPC3013 / group21 / instance400001 / 40398，本服三项配方 |
| 6 | 306 | 原始休整房，现有服务器规则自动 clear |
| 7 | 307 | 原始首领、七站终局冻结记录与结算 |

休整房 303 的 NPC 主对话图只在当前部署资源中缺失，固定来源提交中存在；后续只读核对确认本机 4.6 对应的 8 字节主图是 `ShowMenu`，没有奇物商店任务。306 的原始 `Act403810002.json` 只打开祝福强化页。它们不能被当作奇物商店。已有严格商店流程和独立奇物验证保留，本轮不向 40398 塞奇物或改原始 NPC 对话图。

```powershell
.\gradlew.bat test --tests org.hkrpg.server.protocol.UniverseDialogueRewards46Test --tests org.hkrpg.server.protocol.UniverseDialogueEntry46Test --tests org.hkrpg.server.gameplay.RogueResonance46Test --tests org.hkrpg.server.DefaultDataTest
python -m unittest scripts.tests.test_rogue_dialogue_rewards_sources -v
.\gradlew.bat test shadowJar
python scripts/smoke-rogue-first-world-closure.py
python scripts/smoke-rogue-first-world-closure.py --jar build/parallel-gameplay-runtime.jar --output build/rogue-first-world-closure-report.json
python scripts/smoke-rogue-dialogue.py
```

初始四项回归在实现前全部真实断言失败，无错误或跳过；报告保留 `build/universe-dialogue-rewards-red-tests.xml`。事件专项 28 项已全部通过，包含全部选择、失败保存、第三路径祝福触发回响后的完整状态回退、旧存档先保存、并发/重复、运营文件保留、严格配置及实际 Player Morphia 文档两次读写恢复。固定来源 Python 三项已通过；打包和原生结果以下方实际记录为准。

新原生脚本使用新 `build/smoke/rogue-first-world-closure-*` 隔离目录、新本地端口、新账号和真实 H2；默认房间池与新配方都由生产默认安装机制创建。只把四个前置任务替换成隔离无奖励 Auto 夹具，保留原始 `4030006` 目标主任务、地图、怪物及事件。每次进程启动都校验同一 jar SHA-256；三次服务器正常 `stop`、两次同 UID H2 进程重启和每次正常停止后的只读 Player 检查，不编辑数据库文档。全量 Excel、原始目标任务、相关房间组和事件源文件在运行前后核对 SHA-256。原生探针按原始波次比较实际战斗入口，使用协议客户端上报胜利，不声称真实客户端已打完战斗。

旧 `smoke-rogue-dialogue.py` 在自己的隔离运营配置中显式 `enabled=false`，继续验证效果禁用时原始菜单、钱包不变、重连与拒绝分支。旧脚本使用强制终止，其 H2 恢复不能计为正常 stop 恢复。新七站脚本正向验证启用默认配方、事件菜单暂存、禁止提前出口、暂停/恢复、确认取得、首领结算及终局恢复；回环 HTTP 显式禁用环境代理，JSON 输出为 UTF-8，启动、每份快照及末尾均校验冻结 jar 与隔离 config 的 SHA-256。

## 实际结果和边界

2026-10-11，统一定向验证 479 项 / 17 类全部通过，其中事件回归 28 项；另有固定来源 Python 3 项全部通过。原生七站报告为 `build/rogue-first-world-closure-report.json`，隔离目录为 `build/smoke/rogue-first-world-closure-01k101ic`。冻结 jar 为 `build/parallel-gameplay-runtime.jar`，SHA-256 为 `3948948ca7cba698c4a6c4e0fdb1c40bb45f7a3815f8753ddb00f43a5c8d9163`；隔离 config SHA-256 为 `cba7d3c7e430817337184a9f4bb860bc7abd415f8453ba6b20e284769827ea64`。启动、四份原生快照、三次 H2 检查和末尾核对均保持一致。

| 原生阶段 | 请求数 | 重复或阻塞操作拒绝数 | 已持有祝福数 | 信用点 |
| --- | --- | --- | --- | --- |
| 首次登录、前四站与事件菜单暂存 | 81 | 15 | 3 | 366 |
| 同进程新 KCP 会话恢复暂存菜单 | 9 | 3 | 3 | 366 |
| 第一次正常停止并重启、取得事件祝福、最终首领 | 28 | 7 | 4 | 516 |
| 第二次正常停止并重启、恢复已完成局 | 9 | 2 | 4 | 516 |
| 合计 | 127 | 27 | — | — |

七站全部完成，原始加权敌人和首领共进入 8 场战斗。原始第五站 NPC 唯一 ID `1`、菜单 batch `4`、随机祝福 `612155` 在四份快照及三次 H2 检查中一致；领取前的收据 `complete=false`、已清房间 `1..4`，领取并完成首领后 `complete=true`、已清房间 `1..7`。14102 实际新增一个祝福；碎片始终为 `248`。终局恢复没有再次发奖；首次终局与已完成局重入各有一次当前会话的终局展示通知，不把重入展示计为新支付。

本次执行共正常 `stop` 3 次，同 UID / 同 H2 进程重启 2 次。每次停止后独立只读检查均报告 `changedPlayers=0`；没有通过修改数据库使断言成立。2271 份源资源守卫保持不变，包括全量 Excel、原始目标主任务 `4030006`、房间组和事件文件。生产默认地图池与本服奖励配方均实际安装；四项前置主任务仍是隔离无奖励 Auto 夹具。统一全量 `test shadowJar` 已通过 13,215 项 / 487 类；宇宙功能提交为 `b19152d`，集成文档提交为 `84d84ff`。

这项结果证明冻结服务器在原生协议探针下的七站存档闭环。原生探针按真实资源入口建战斗，再由协议客户端上报胜利；真实 4.6 游戏客户端 UI、事件演出与实际战斗画面仍未验收。默认碎片数、均匀抽取与指定房间池属于本服规则，报告中的 `officialRewardEffectsVerified`、`officialRoomGenerationVerified` 均为 `false`。所有随机路线、Tourn/Magic 和其他未知事件仍未验收。注入保存失败的回归与正常停止的 H2 恢复不等于断电或强制崩溃的原子性验证。

## 生产奇物商店入口审计

本轮七站没有补造奇物入口。按现有 `RogueShopContext` 的实际校验逐项连接 `RogueRoom` 的 type 5 房间、原始 `MapEntrance`、`GroupWithContent`、NPC1013 和其 `Dialog.LevelGraph`：53 个原始休整房商人绑定中，当前部署资源有 1 个对话图缺失、52 个没有可被现有扁平任务解析识别的 `MiracleShop` 页。这里统计当前实现认可的入口，不宣称已解释原版所有条件与嵌套事件图。首世界第三站 303 对应 `P80001/F80001002/G34/N400001` 的主图存在于固定源提交；本机只读核对完整消费其 8 字节记录，实际任务 3259 经原生工厂注册定位为 `ShowMenu`，字段只有 `TaskEnabled` 和 `DisplaySingleMenuItem`，没有商店任务。该 NPC 的原始子任务 `403000304` 进行或完成条件仍需保留。七站验收时 Transferred 包虽可读 178 字节，尚未完整解码；后续已通过完整原生工厂/Reader 核对，完整消费主图、Transferred 与两份 13 字节休息区图，四棵 JSON 树均与固定 Git blob 相等。Transferred 保留 `Started` / `Finish` 的两条 `403000304` 条件及原始 AddMenuItem；完整静态解码仍不作为客户端菜单或商店入口验收，详见 [休息区脚本解码边界](rogue-rest-graphs-4.6.md)。第六站 306 对应 `P80001/F80001003/G22` 的 `Act403810002.json` 只打开 `Ui.Rogue.Buff.RogueEnhanceBuffPage`。

固定来源有实际奇物商店：DLC130 的入口 `8020101`、平面 `80201`、楼层 `80201001`、group `35`、NPC1013 / instance400001；其图为 `Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json`，含原始 `RoguePropOpenPage(Type=MiracleShop, WaitForExit=true)`。现有 `smoke-rogue-shop.py` 的 miracle 模式把隔离夹具房间 `1000012` 改到这个来源入口，另设置隔离初始碎片与奇物目录范围；这项探针验证商店与取得行为，不能作为生产原始七站已可达奇物商店的证据。另一个经典图 `Act403810003.json` 虽含奇物商店页，现有源 `Config/LevelOutput` 未找到实际 NPC 对它的引用，也不能只凭图文件名称直接发布入口。

审计使用同一固定 TurnBasedGameData 提交，37 份相关来源逐个对照固定 Git blob，仅允许 CRLF 转 LF；只读产物保留 `build/rogue-curio-room-audit.json`。本机 DLL、metadata、DesignData 索引的 SHA-256 分别为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`、`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`、`2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`。G34 / G22 运行组分别完整消费 1988 / 1532 字节并确认原 NPC 实例；只读记录与工厂定位报告在 `build/universe-event-draft/curio-client-*`。没有启动客户端、安装恢复数据或猜测打开商店的命令。

后续本服商店配方已限定原第六站 room306 / G22 / NPC1013 / instance400001，保留原七站、303 教程与原始对话图。`RogueRestMerchants.json` 默认 `enabled=false`；启用后的 200 碎片基价、最多 3 件策略属于本服配置。实现前 32 项真实回归失败；生产 `FloorInfo.onLoad` 边界另经第 43 项真实 RED 锁定，来源副本经过同一加载规范化后，43 项商店回归及相关 735 项 / 36 类全部通过，固定来源 Python 5 项通过。新包 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b` 的独立原生报告 `build/rogue-rest-merchants-floorload-native.json` 实际通过六个快照、156 个请求、四次正常停止与三次同 UID / H2 重启；原第六站购买、唯一 UUID 和收据、图鉴以及第七站结算恢复均通过。协议请求取得与实际客户端菜单入口仍分别记录，功能保持 `partial`；未替换到 DLC130 房间，也不改动以上已完成的 40398 验证数据与奖励类别。配置、失败记录和新包验证边界见 [原七站本服休整商店](rogue-rest-merchants-4.6.md)。
