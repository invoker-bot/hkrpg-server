# G61 原日常3000603的电话日轮

本批补齐原3000603的重复资格、真实自动分配和独立电话收据。原NPC初遇90000103、G61原菜单、3000602以及现有协议入口继续复用；没有新增空回复，也没有把旧全局电话完成改写成当轮完成。

原3000603电话已有通用回复流程。真实缺口是旧1211402完成历史能追认新的接取：独立冻结F2观测中，只有原accept与finishTalk，当前电话回复为0、没有FinishSection请求，主任务却从DOING变成CLOSED，目标FINISHED，信用点0→5000，日轮Run仍为空。本批使该目标只认本轮接取身份和本轮的完整三次回复。

## 原件与触发链

固定来源为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`，标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。已有 `g61-npc-first-talk-4.6.json` 保留50原件的来源证据、240完整对象、完整配置图blob和三个原日常链；旧 `daily-message-3000602-4.6.json` 也已包含三条原任务和17个1211402完整节点。本批复用这些原件，不修改它们或运营资源。完整整表及50份未经转换的Git原始blob另外存于build来源证据目录，逐份匹配固定Git原件及本机整JSON对象。

固定提交没有发现LICENSE文件。因此只用于数据原件核对，没有引入参考项目运行时代码。现有LunarCore来源、Java21和AGPL许可保持原约定。

原主任务3000603为Daily，保留Manual/And接取和Auto/And开始，奖励231。原DailyMissionData类型1、组30003、前置1010201、Quest2100003；类型1原限额每天一次。活跃任务另由1010403完成解锁，规则是FinishTodayDailyMissionCnt/NoPara/Progress1。主委托奖励5000信用点，活跃奖励200点，两者保持独立。

原 full 起始为300060301，结束为300060302。partial中301是Auto→Talk/Equal/Mission_300060301；302以前者为AnySequence前驱，MessageSectionFinish/Equal/1211402/Progress1/IsBackTrack。原302实际指向 `Mission_300060303.json`，不能按编号改成302脚本。两个完整MissionTask canonical SHA分别为：

```text
300060301: 5f5e23dad522a9f1f22643206d85f84709432c065fb2328cd26f2d84c5feb4d7
300060303: 7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775
```

原NPC10206/G61/实例400001位于Maze20002、楼层20002001、维度0。组由1000401完成加载，3000604接取后的卸载延迟到楼层重载。90000103只保存真实初遇身份。原DialoguePack条件 `ByCompareSubMissionState(300060301, Started)` 提供MenuItemID/MissionID300060301，路径为 `Talk/Talk_300060301.json`。

该Talk图触发Performance E300060301。原Act300060301第15序列的TriggerCustomStringOnDialogEnd产生 `300060301_Finish`；原Mission脚本等待同名字符串后报告 `FinishPerformanceMission(Key=Mission_300060301)`。取消序列只有EndPerformance，不能作为任务完成。服务器使用现有准确finishTalk入口；这里不声称Act已独立从本机bytes完整解码或由真实客户端执行。已完成G61十份本机bytes整图证明和旧Native验收属于已有G61功能，不重复计入本任务验收。

原MessageGroup11125/联系人48的SectionList原样为[1211400,1211401,1211402]。1211402有17个原Text节点，起点121140301，三轮二选一共八条有效路径：

| 第一轮 | 第二轮 | 第三轮 |
| --- | --- | --- |
| 121140302 | 121140306 | 121140311 |
| 121140302 | 121140306 | 121140312 |
| 121140302 | 121140308 | 121140311 |
| 121140302 | 121140308 | 121140312 |
| 121140303 | 121140306 | 121140311 |
| 121140303 | 121140306 | 121140312 |
| 121140303 | 121140308 | 121140311 |
| 121140303 | 121140308 | 121140312 |

自动流式历史长度为3、6、9、13，末节点121140315。到达末节点仍须实际FinishSection请求才能完成。完整节点对象保留文本/选项Hash、Sender、联系人和Next列表；完整来源证明包含原整表，不仅是上面的编号。

## 实现与保存边界

`DailyMissionMessageRules`只允许固定3000602/201/202/1211401及3000603/301/302/1211402两个定义，各自校验实际目录、前置、配额、原Main/full/partial、原脚本完整签名、加载组/NPC、电话所有者及完整拓扑。3000601和任意其他Message/Performance仍不因本批得到重复资格。

每轮Run保存在同一Player文档，绑定Main/Goal/Section、接取日、两个实际QuestState observation UUID和来源标识。全局旧电话对象保留，但新接取的视图只能使用对应的日轮Run。回复必须逐条属于当前原选项，末尾提交单独处理；过期未付款轮进入expired，结算轮进入archive，新日从原起点重新回复。保存失败恢复原Run对象和观察身份，原请求可以重试。

`DailyMissionMessageState`的既有字段名称与3000602来源标识不变。MessageManager、MissionManager、DailyManager及Scene共享入口没有修改；复用已有declared/completed/view/prepare路由。不能因为新增定义就让一次配额打开两条委托，也不能让缺失3000603资源无条件拒绝完整3000602。

## 已执行验证

独立JDK21 javac/JUnit对冻结F2 SHA `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`执行新34项：23个纯AssertionFailedError、11对照通过、0errors/skips/aborts/container failures。运行时检查生产类CodeSource都实际来自该jar，排除当前main输出。迁入src并更正目录进入顺序后，F2仍实际复现同样34/23失败。

现实现以独立编译的两owned类覆盖F2，在同一JVM执行新34、旧Phone35、G6140、同日晚解锁21，共130项全部通过。root随后正式Gradle执行这130项及3项Query，共133项全部通过，0failures/errors/skips，2,530项冻结输入前后不变；报告为 `build/daily-sibling-chess-profile-current-root-green-verification.json`。后续联合433项专项也全部通过，2,535项冻结输入稳定，报告为 `build/manual-sibling-save-blast-root-focused-verification.json`。新3000603的第三轮选项与两个接取身份经过两次实际Morphia Player文档读取，随后真实第三回复及提交发放奖励。这些JUnit验证使用MemoryBackend；实际H2文件恢复和进程重启使用下文的新冻结包单独验收。

历史阶段保持独立：首build草稿的wrongTargetScript负例用了300060203，而它与原300060303完整内容相等，不能作为损坏来源依据；保持旧报告，改为真实不同的首Talk脚本后复跑仍34/23纯行为RED。首130相关运行有一项夹具顺序错误：进入楼层已正确分配3000603，随后夹具移除了它的目录；把首日3000602目录置于进楼层前后全部130通过，未改变任何行为断言。补充旧global观测的首次source-file启动仅有MemoryClassLoader访问错误，改为javac后实际观测成功，不计生产RED。

证据位于 `build/story-script-draft/daily-message-3000603-src-catalog-corrected-f2-red.*`、`daily-message-3000603-owned-green-second.*`、`daily-message-3000603-legacy-completion-observed-second.json` 和完整源报告。独立验证期间输入前后相等；这不代替root的完整构建审计。

运行来源核对不会修改运营数据：

```powershell
python -X utf8 scripts/verify-daily-message-sibling-sources.py --output build/daily-message-3000603-current-source-proof.json
```

正式测试由root统一执行：

```powershell
.\gradlew test --tests org.hkrpg.server.gameplay.DailyMissionMessageSibling46Test --tests org.hkrpg.server.gameplay.DailyMissionMessage46Test --tests org.hkrpg.server.gameplay.G61NpcFirstTalk46Test --tests org.hkrpg.server.gameplay.DailyActiveLateUnlock46Test
```

## 原生KCP/H2实测与边界

`scripts/smoke-daily-message-sibling.py` 使用新的 `NativeDailyMessageSiblingProbe` 与 `DailyMessageSiblingFixtureInspector`，复用已有G61原初遇命令、完整50原件守卫和独立G61保存检查。仅隔离副本安装单条原3000603目录与原活跃任务池；1000401/1010201/1010403明确使用前置历史夹具，原Main/任务图/NPC/组/入口条件不变。初遇后在同D0真实再入解锁，不改到新日来避开空池问题；目标、回复、日轮和奖励均由真实请求产生。

已实际独立javac编译5份外部helper，并验证34组请求/响应的4.6完整字段描述及G61查询2142/field4、完成2161/field13。原本机50整对象和实际Java/Gson初遇身份也匹配；报告为 `build/story-script-draft/daily-message-sibling-native-tools-preparation.json`。此步骤只在旧冻结F2上验证工具编译和schema，没有启动游戏服务、没有KCP请求或H2 Player读取，也不证明新实现已经打包。

纯工具最新10项守卫实际通过，覆盖17原节点图的八支路与全部3/6/9/13前缀、完成与初遇控制条件、外来选项拒绝、隔日先拒绝旧请求、原初遇身份稳定、报告排他、有限case选择及由真实H2历史生成的已回复路径。工具开发的首Inspector沿用了旧两选择控制，3项中2个保护回归失败后才改为原三次选择；原初遇稳定性检查缺少canonical导入另由保护回归复现，修正后全部通过。这些属于新工具准备错误，不计入生产行为RED或原生失败次数。

完整工具目录包含八个结算分支及四个未交付前缀；`--case-index`分别为0..7与0..3，省略时执行该有限目录全部案例。八个结算编号对应上表行顺序；四个到期编号对应0/1/2/3次回复。本轮实际仅执行两种场景的index0；其余结算case1..7和到期case1..3没有执行独立原生案例，其行为仍由34项回归证明。

所选结算支路分别保存第一、第二选择后正常停服，以同UID H2重启完成剩余选择，再验证原5000信用点、200活跃度与防重复；第二日使用新接取身份和另一条原路径。所选未交付支路在对应回复数后停服，受控新日的首请求直接拒绝旧回复或旧结尾，随后查询真实过期并重新接取。每次正常停服后，G61及Phone Inspector各做两次独立Morphia Player读取；原MVStore以readOnly打开，并校验前后数据库字节和原文档不变。报告中的executedCaseIndices、实际回复路径和停止次数均只来自实际完成案例；配置的三次选择不会代替未交付轮的真实已回复前缀。

实际使用root冻结的 `build/manual-sibling-save-blast-runtime.jar`，SHA256 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be`。两条流程首次运行都通过，没有新原生失败或临时工具修复；源码、工具及原运营资源保持不变。实际记录如下，停止次数来自逐进程日志，Player读取次数来自停服后实际执行的两个Inspector：

| 实测场景 | case | KCP请求 | 检查 | 快照 | 正常停服 | 同UID H2重启 | 独立Player读取 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| settled | 0 | 178 | 206 | 9 | 7 | 6 | 28 |
| expired-unpaid/noReplies | 0 | 123 | 139 | 7 | 6 | 5 | 24 |
| 合计 | 2个实际案例 | 301 | 345 | 16 | 13 | 11 | 52 |

结算case0的D0实际回复[121140302,121140306,121140311]，D1实际回复[121140303,121140308,121140312]；两日各取得5000信用点及200活跃度。到期case0的D0只保留三条原NPC开场消息，没有任何玩家回复或主奖励；受控D1首个旧回复被拒绝且旧Run/Main/Goal未变，随后查询过期、新接取和三次实际回复才取得5000/200。重启后的Main/Goal UUID继续匹配原接取，隔日新UUID与旧归档或expired不同；原G61初遇记录在两个案例各自所有重启和两日内保持完全相同。

两份报告为 `build/daily-message-3000603-current-settled-native.json` 和 `build/daily-message-3000603-current-expired-native.json`，同名 `.log` 保留全过程。独立目录分别为 `build/smoke/daily-message-g61-native-3000603-settled-302-306-311-oneReply-ekfw_5kc` 与 `build/smoke/daily-message-g61-native-3000603-expired-unpaid-302-306-311-noReplies-t1_r5_j7`。二次只读证据审计在 `build/story-script-draft/daily-message-3000603-current-native-independent-audit.json`：逐条匹配16份probe原日志、13份停服日志、26份实际Inspector输出和两份正式报告，确认52次独立读取、保存身份、奖励、防重、未交付边界及每案例163项来源文件SHA不变；每案例50完整固定原件都匹配本机整对象。

以下是实际执行命令。报告和lease不能复用；再次运行须选择新输出路径。各阶段均检查同一个包SHA，不把未执行的默认目录数作为结果。

```powershell
python -X utf8 -m unittest scripts/tests/test_daily_message_sibling_native_guard.py -v
python -X utf8 scripts/smoke-daily-message-sibling.py --jar build/manual-sibling-save-blast-runtime.jar --case-index 0 --output build/daily-message-3000603-current-settled-native.json
python -X utf8 scripts/smoke-daily-message-sibling.py --jar build/manual-sibling-save-blast-runtime.jar --scenario expired-unpaid --case-index 0 --output build/daily-message-3000603-current-expired-native.json
```

没有执行真实客户端任务菜单、剧情演出或UI验收；没有把4.2 lite基线的版本字符串改成4.6来声称兼容。受控跨日和正常保存重启已验，两种场景各一个实际case；自然墙钟跨日、客户端原Act执行、强杀/crash原子性仍未验。本批全量构建由root另行完成审计，不以专项或上述两个原生案例代替。
