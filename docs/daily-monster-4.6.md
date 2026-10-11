# 4.6 三条原始击怪日常委托

本批补齐 3000211、3000212、3000213 的有限服务端日常资格及原怪生命周期。保留来源 Manual 主任务、UnlockMainMission=2000116、单日一条名额、原始 Talk→KillMonster→Talk→Talk 图、Reward231 的 5000 信用点及活跃目标 2100003 的 200 点奖励。自动分配仍是本服已有策略，资格检查不直接完成目标或发奖。

| 主任务 / 击怪目标 | 原始 plane / floor | 原始组 / 怪物实例 | NPCMonster / EventID | 楼层主维度组索引 |
| --- | --- | --- | --- | --- |
| 3000211 / 300021102 | 20002 / 20002001 | 78 / 200001 | 8012010 / 20003221 | 62 |
| 3000212 / 300021202 | 20002 / 20002001 | 79 / 200001 | 8012020 / 20003222 | 63 |
| 3000213 / 300021302 | 20003 / 20003001 | 95 / 200001 | 8012030 / 20003223 | 54 |

三条来源目标均为 Equal/1，ParamInt1/2 对应所属组与原怪实例，IsBackTrack=true，MazePlane/Floor 与 LevelPlane/Floor 完全一致。此前日常安全图拒绝回溯标志、非零参数和 KillMonster，导致原图无法自动分配和下一日重接。现在只有经过原资源证明的有限 KillMonster 路径允许这些来源参数，其余目标沿用已有约束，不以全局死亡历史完成后续轮次。

`DailyMissionMonsterRules` 证明已加载普通楼层、主维度、Server/Mission 组与主任务的唯一所属关系、唯一存活来源实例、NPCMonster、世界等级 0–6 对应的全部原事件和有效战斗波次。源组 78/79 只在击怪目标或后续对话 Accept 时加载，95 保留主任务 Accept 即加载的规则；三组保留主任务 Finish 卸载和现有 Cancel 守卫。未知组条件、剧情维度、客户端专属怪、错误实例、外来所有者及缺失资源继续拒绝资格。

加载原怪还要求当前日已有真实接取记录、当前轮日绑定一致、主任务正在进行且击怪目标尚未完成。95 可以在第一条对话尚未接取击怪目标时生成原怪，这来自原始 MainAccept 条件；不能把此时的死亡算作尚未接取目标的进度。击怪完成后，同轮重新进入楼层不再生成原怪，原组和对话道具 100056/300001 继续保留，尾对话仍能交付。下一日归档旧轮并建立新接取后，从新的未完成目标重新加载原实例。

实际推进沿用已有链路：SceneEntityLoader→真实 Scene/EntityMonster→已接受战斗胜利→Scene.defeatMonster→MissionManager.onKillMonster。管理删除实体、外来场景对象和重复死亡不能记量。共同挂钩只增加 SceneEntityLoader.loadMonster 的有限加载守卫，不增加玩家字段、持久死亡表或新奖励事务。

击怪目标的原始客户端脚本位于 3000201 目录，包含 PropSetupUITrigger、自定义字符串、AdvEnablePropDialogMode 与 TriggerPerformance300020202，没有 TriggerBattle 声明。它们作为完整来源保留；本批没有把按钮声明或演出名字当作已执行的服务端脚本。

固定资源夹具 `src/test/resources/fixtures/daily-killmonster-4.6.json` 含 102 条原始行及 37 份完整资源的 SHA256/Git blob 证明。来源为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`，提交标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。以下三项检查已实际通过，逐份核对安装 JSON 与固定提交的完整 Git blob，以及每行来源、主维度原组、所有世界事件和客户端脚本边界：

```powershell
python -m unittest discover -s scripts/tests -p test_daily_killmonster_sources.py -v
```

旧实现的首批 15 项 Java 回归实际有 12 项失败，错误与跳过为零，归档在 `build/callback-daily-monster-red-tests/TEST-org.hkrpg.server.gameplay.DailyMissionMonster46Test.xml`。当前 34 项检查使用实际 Player.loadScene、SceneEntityLoader 和 Scene.defeatMonster；完整 FloorInfo 保留原主维度索引，只有其余实体人口被隔离。重进检查会先进入另一原始楼层再回来，并证明 Scene 对象发生变化，避免同楼层传送复用场景造成假验收。用例覆盖原图资格/自动分配、原加载门、管理移除不计量、同轮已击杀不复生、两日重新完成、损坏资源、损坏接取记录及未交付旧轮到期。到期用例特别证明 95 的同一真实场景在新日接取后具有新原怪，不能由主动离场掩盖未刷新的组。

第二次统一检查共 47 类/1166 项，只剩上述 3000213 未交付到期用例一项失败，日常 34 项中的其余 33 项通过，错误与跳过为零；报告为 `build/parallel-gameplay-second-red-tests/test/TEST-org.hkrpg.server.gameplay.DailyMissionMonster46Test.xml`。此真实 RED 证明：原始 MainAccept 条件在旧轮和新轮之间一直成立，组 95 的 loadedGroups 标记沿用旧场景，新轮没有原怪。修正在 DailyMissionExpiry.expire 清除旧 main/sub/runDays 后、重新分配前刷新原场景，让原始条件先卸载旧组，再由真实新日接取加载新原怪。没有替换失败断言或以主动离场规避同场景检查；修正后统一 47 类/1166 项全部通过，失败、错误与跳过均为零，其中本批日常 34 项全部通过；GREEN XML 保存在 build/parallel-gameplay-second-green-tests/test。

```powershell
.\gradlew.bat test --tests org.hkrpg.server.gameplay.DailyMissionMonster46Test -PtestForks=1
```

首轮原生尝试因探针读取 4.6 Mission 中不存在的 finish_time 失败；真实 Mission 只有 id/status/progress，Quest 才声明 finish_time。探针现已在连接前核对全部消费消息和字段，目标完成时间由正常停止后的 MVStore.readOnly/Morphia 文档验证。第二次尝试已通过真实已接受战斗胜利，随后因已死亡实体请求的负例错误期望成功而停止；已核对服务端对此返回 REQ_PARA_INVALID，修正负例仍保留原击杀完成和无新增战斗断言。以上失败日志与隔离目录均保留，没有计入通过结果。

普通掉落与委托奖励分开核验：完整固定提交 MonsterDrop 保留三 NPC 的原始 DisplayItemList 与 AvatarExpReward=36；当前既有 MonsterDropExcel.onLoad 在显示货币项2没有数量时，以 AvatarExpReward 作为信用点数量。每次胜利实际得到的36信用属于本服当前算法结果，不是已证实的官方掉率或官方信用奖励。本批不会把这36信用计入每轮 MainReward231 的5000信用或活跃目标2100003的200点，也不会把未交付主委托奖励描述为普通战斗没有掉落。

隔离原生入口 `scripts/smoke-daily-monster.py` 与 `scripts/fixtures/java/org/hkrpg/server/protocol/NativeDailyMonsterProbe.java` 已准备，语法、帮助入口及探针编译通过。当前实际运行使用冻结产物 build/parallel-gameplay-second-runtime.jar，SHA256 为 02e5a8c622c785fe126a28789f8d7055a0e280ceaa618d0e2b06c71cdd890ddd，两份独占原生报告已实际通过。脚本只在新建 build/smoke 目录使用回环端口、独立配置和 H2 数据，隔离前置解锁、入口门及单候选目录，不更改目标委托、原组、NPC、事件或阶段。每个报告预留独占 lease，已有报告和 lease 均拒绝覆盖，避免并行会话共写证据。正式运行须指定本轮统一冻结 jar 和从未使用的输出路径：

```powershell
python scripts/smoke-daily-monster.py --jar build/<统一冻结产物>.jar --output build/daily-killmonster-native-<唯一名称>.json
```

另有明确的未交付到期分支：第一日实际击杀后保留未交尾对话和未付主委托奖励，正常停止；同 UID H2 恢复后在原楼层用第二日时钟触发真实到期/接取，95 必须在任何离场前出现新的原怪，再重新击杀和交付，只发一次 5000/200。该分支用独立输出，可只检查来源 3000213，也可省略主任务参数检查三条：

```powershell
python scripts/smoke-daily-monster.py --jar build/<统一冻结产物>.jar --scenario expired-unpaid --main-mission 3000213 --output build/daily-killmonster-expired-g95-<唯一名称>.json
```

实际通过记录如下，两份均使用上述同一 SHA256 的冻结产物，失败尝试不计入这些数量：

| 独占报告 | 实际范围 | 请求 / 检查 / 快照 | 正常 stop / 同 UID H2 恢复 | 停止后只读检查 / 独立 Morphia 读取 |
| --- | --- | --- | --- | --- |
| build/daily-killmonster-settled-second-fixed-v2.json | 3000211、3000212、3000213，各完成两轮 | 458 / 368 / 30 | 9 / 6 | 9 / 18 |
| build/daily-killmonster-expired-g95-second-fixed-v2.json | 3000213，旧轮未交付到期后完成新轮 | 119 / 95 / 7 | 3 / 2 | 3 / 6 |

前一报告的唯一隔离目录分别为 daily-monster-3000211-qht282zr、daily-monster-3000212-ph_4wbu_、daily-monster-3000213-c_pzm3r_，后一报告为 daily-monster-3000213-mv2zg0lt，均在 build/smoke 内。每份保留全部 probe/server 日志、连接前 wire-fields 证明、时钟投影及三次 H2 检查。

三条实际 KCP 链路均证明接取、源楼层与原怪、存活自有施法者、原事件/世界等级0阶段及波次、已接受战斗胜利、尾对话、主委托5000信用通知先于响应、活跃目标完成通知及200点领取、同日防重、受控下一日归档与新轮奖励。每轮实际杀死原怪后会先离开到另一原始楼层再返回，证明同轮不复生且原对话道具保留；随后正常 stop 和同 UID H2 恢复后交付。未交付分支额外证明95在恢复后的原楼层且任何离场前出现第二日新怪，旧轮仅保存first/kill完成时间而main仍DOING、无主奖励，最终只发新轮主奖励一次。

DailyMonsterFixtureInspector 仅在正常 stop 后打开自有 MVStore.readOnly，两次独立 Morphia 查询并比较文档与数据库/config SHA256字节。当前轮和旧归档/到期轮的main/sub身份、进度、阶段、真实finishTime所属日、接取名额、runDays、Reward231冻结数量/已付账本、credit与active points均实际核验。普通掉落36/次与主委托5000分别计数，settled末wallet10072、expired末wallet5072，最终当日active points均为200。这些值是当前Java掉落算法与受控任务流程的结果。时钟命令和探针只编译到隔离 classpath，不进入服务 jar；胜利使用构造的有效战斗报告，不能算作官方客户端战斗模拟。

Java 场景回归、上述正常停止/H2进程恢复和固定源证明属于分别记录的已验收层级。固定时钟、前置教程完成证明、单候选和其余场景人口隔离均是明确夹具边界。未经实际客户端点击、按钮、演出和 UI 验收，不能称全部剧情、88 条日常或完整游戏已完成；受控跨日不等于真实墙钟跨日，正常 stop 恢复不等于断电原子性。本批运行 Java 为独立实现，保留 LunarCore/AGPL 归属；参考数据树无代码许可声明，不引入其运行代码或依赖。没有手改协议生成文件，没有修改运营配置、数据库和既有资源。

本批统一完整构建实际通过490类、13,304项回归，失败、错误与跳过均为零（`build/parallel-gameplay-second-full-tests/test`）。构建前后的2,489份编译输入零变更，完整构建产物与上述冻结jar哈希相同；现有70,616份配置及资源字节未变（`build/parallel-second-compilation-audit.json`）。
