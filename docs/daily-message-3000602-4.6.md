# 4.6 原始 3000602 手机日常委托

本批补齐一条有完整来源的普通 MessageSectionFinish 日常：3000602。保留原 Manual 主任务、1010201 已完成的接取门、类型 1/组 30003 的单日一条配额、两步 Talk→Phone 图、Reward231 的 5000 信用点，以及活跃目标 2100003 的 200 活跃度。有限资格接到本服已有自动分配与隔日重接策略，不直接完成对话或发奖。3000601/3000603、Perform/Link/活动消息与其余短信日常未因本批获得重复资格。

## 原件与范围

固定来源为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`，提交标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。测试夹具 [daily-message-3000602-4.6.json](../src/test/resources/fixtures/daily-message-3000602-4.6.json) 保存 124 个完整原始对象、39 份完整源文件的 Git blob SHA256、实际安装 SHA256 与每行位置。37 份已安装 JSON 与固定完整原件相同；以下两份原始 NPCDialogue 脚本没有安装，明确记为 source-only，没有用来源 SHA 冒充安装 SHA，也没有安装或模拟其演出：

- `Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json`
- `Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json`

资料库没有可用于新增代码的明确代码许可。本批只读取资源作为证据；新增 Java、测试及探针在本项目内独立编写，保留 LunarCore 来源和 AGPL。未运行参考仓库二进制。

| 原始目标 | 接取条件 | 完成条件 | 原任务脚本 |
| --- | --- | --- | --- |
| 300060201 | Auto，无附加参数 | Talk / Equal / `Mission_300060201` / 1 | `Mission_300060201.json` |
| 300060202 | AnySequence / 300060201 | MessageSectionFinish / Equal / 1211401 / 1 / IsBackTrack | **`Mission_300060203.json`** |

目标 202 的脚本确实名为 203，按原 MissionJsonPath 加载。完整首脚本包含 CreateNPC、WaitCustomString=300060201_Finish、PerformanceTransition、FinishPerformanceMission；末脚本 DestroyNPC 的原组和实例为 61/400001。源 Talk 和完整 Act 的选项、循环及自定义字符串保留为证据。当前服务器接受真实 FinishTalk 回报，不执行全部客户端演出脚本。

消息组 11125、联系人 48、普通段 1211401 的 12 个原节点有四条有限路径：先选择 121140202/121140203，再选择 121140205/121140207；每条实际历史为九条消息，最后节点为 121140210。开头三个 NPC 消息由已有自动流逻辑推进，两个玩家选择及最终 FinishSection 请求均须实际收到，不能以结束标志替代回复。

原 Plane20002 是 **Maze**，Floor20002001 的主维度索引 49 指向共享 Reset 组 G61。该组没有 Category 或 OwnerMainMissionID，独立 LoadCondition 是 1000401 Finish，UnloadCondition 是 3000604 Accept 且 DelayToLevelReload=true。原 NPC10206/400001、条件和延期卸载行为仍由真实 Scene 处理；不把共享组改成该日常的私有组。原入口 200020102 为 Mission，保留 FinishMainMissionList=[1000401]。

## 运行行为与保存边界

`DailyMissionMessageRules` 只接受上述完整原图、两个完整 MissionTask 的规范 JSON 摘要、普通消息所有者、十二条原边以及原场景声明。缺原件、错误脚本、跨段节点、环、重复所有组或演出类型变化不会放开重复资格。`DailyMissionRepeats` 只为这个有限目标允许原始回溯标志、非零段编号和 MessageSectionFinish。

新的 `DailyMissionMessageState` 与既有 GameplayState 保存于同一个 Player 文档，每条 Phone 历史绑定接取日、主任务和子目标的实际 observationKey，以及固定来源。历史旧轮留在其旧周期记录，读取不会分配观察标识或重新绑定。新目标接取后从三个原 NPC 开头重新开始，不能把全局旧 finished 或旧回复作为新完成证明。未先完成本轮原 Talk 时不会产生本轮候选回复。

已结算旧轮仍走现有归档与奖励账本；未交付旧轮走已有日过期链路，保留旧主/子对象及 Phone 历史，随后建立新接取。换日后、下一次查询之前到达的旧回复/结束请求也拒绝，不改原轮、钱包或保存次数。同数字编号的主任务或目标被新 QuestState 替换时，旧已走完对话不能消费到替换接取中。兄弟段 1211400 的既有全局真实历史保持原对象。

Phone 激活、选择、结束、查询和同步使用同一接受视图。实际回复先准备副本与观察标识，再与普通消息状态执行一次 Player.save；保存异常恢复原 Phone、周期条目、普通 map 和观察标识，原请求可以重试。完成仍由既有 MissionManager、MissionRewards、DailyManager 和周期奖励键推进。此边界是同 Player 文档的同步失败回退，没有断电原子性声明。

## 已实际验证

最终修正夹具后的冻结 B 基线为 `build/rest-merchant-floorload-runtime.jar`，SHA256 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b`。独立 Test task 明确排除当前 main 输出，35 项中 **16 项纯 AssertionFailedError**，errors/skip 为零，证据为 `build/daily-message-final-fixture-baseline-red.json` 和对应 `...red-tests`。失败锁定实际自动分配、两日重接、未交付到期、查询前旧请求和相同数字编号替换的缺口。

此前阶段分别暴露非 static Actor 引发 21 处 peers Morphia 扫描失败、恢复夹具缺位置导致 15 个行为断言加 1 个 NPE；都已修正夹具，不计为上述行为 RED。实现首轮错误地把原 Maze 当 Town，联合 84 类/2615 项中的本类 23 项失败，其余检查通过；保留原 Plane 行与全部断言，只修资格类型。修正后 **35 项及 shadowJar 实际通过**，日志为 `build/daily-message-source-type-green.log`。这不替代后续完整构建或原生验收。

Java 回归 [DailyMissionMessage46Test.java](../src/test/java/org/hkrpg/server/gameplay/DailyMissionMessage46Test.java) 使用原资源与现有 API，覆盖四条实际回复路径、重复拒绝、新日完整重做、三个未交付阶段、接取身份、保存失败回退、损坏来源、真实 Scene 条件和已有 4.6 查询/通知。两个实际 Morphia Player 文档读取验证半段回复恢复；它们不是进程重启、H2 或客户端 UI 验收。教程历史、单候选目录、受控时钟、保存替身及楼层其他组人口隔离均为明确夹具。

来源四项及纯脚本保护八项合计 12 项已实际通过，日志为 `build/daily-message-source-and-guard-green.log`。脚本保护先锁定 stdin 清理异常覆盖首次 Phone 失败的问题，八项中的该项先出现真实 OSError；修正后正常清理、清理本身失败、已有 report/lease 阻止重跑、TCP 端口去重和阶段顺序均通过，不启动服务器。

```powershell
.\gradlew.bat test --tests org.hkrpg.server.gameplay.DailyMissionMessage46Test -PtestForks=1
python -m unittest discover -s scripts/tests -p test_daily_message_sources.py -v
python -m unittest discover -s scripts/tests -p test_smoke_daily_message_guard.py -v
```

## 原生网络与正常 H2 恢复

[smoke-daily-message.py](../scripts/smoke-daily-message.py)、[NativeDailyMessageProbe.java](../scripts/fixtures/java/org/hkrpg/server/protocol/NativeDailyMessageProbe.java) 和 [DailyMessageFixtureInspector.java](../scripts/fixtures/java/org/hkrpg/server/gameplay/DailyMessageFixtureInspector.java) 已实际运行。两份独占报告均使用冻结 D `build/cavalry-phone-runtime.jar`，SHA256 为 `8d81c933127d7f62b8c022051d5b1d8dd4d8a1a30a4ec917134109c3fd8d7412`；全部七个隔离案例结束后再次核对摘要一致。每个案例以 Java21 编译探针，在连接之前通过 31 组 schema/实际命令枚举/字段检查，随后真实 SDK 登录与 KCP 请求推进原图。

脚本只使用新建 build/smoke 目录、回环端口、独立 config/H2。HTTP 与 Mongo TCP 端口去重，HTTP 使用专用 ProxyHandler({})，输出和 lease 排他创建，已有证据拒绝覆盖，退出保留首次失败与所有日志。仅三项前置 1000401/1010201/1010403 使用明确隔离自动完成图，单候选和时钟为夹具；原委托、短信、奖励、入口门和 G61 条件均不改。两个 source-only NPCDialogue 图仍不安装。

| 实际报告 | 范围 | 请求 / 检查 / 快照 | 正常 stop / 同 UID H2 恢复 | 只读 H2 检查 / 独立 Player 读取 |
| --- | --- | --- | --- | --- |
| `build/daily-message-settled-d.json` | 四条原始选择路径，各自两日交付 | 384 / 464 / 20 | 12 / 8 | 12 / 24 |
| `build/daily-message-expired-d.json` | 未回复、一次回复、两次回复未提交 | 231 / 264 / 15 | 12 / 9 | 12 / 24 |
| 合计 | 七个独立案例 | **615 / 728 / 35** | **24 / 17** | **24 / 48** |

四条已结算路径分别实际选择 202/203 与 205/207，首回复后正常停服，重启同 UID 读取五条半段历史，再完成第二次回复与 FinishSection，收到实际任务完成通知、5000 信用点奖励通知及查询状态。次日首次查询按原图重新接取，先 Talk、再从三个开头 NPC 消息重做，选择两项相反原选项；两日各实际到账 5000 信用点和 200 活跃度。第三次进程恢复及重复结束、活跃领取均不再次付款。

三个未交付阶段的旧 Phone 历史分别为 3/5/9 条，主任务和目标仍 Doing、finishTime=0、Phone.finished=false，旧轮信用和活跃均为零。第二个进程在换日后先直接提交旧回复或结束请求，尚未发送任何任务或消息查询；实际拒绝后正常 stop，原持久 PhoneRuns、主/子接取键及目标与首进程只读结果完全一致。第三个进程才查询触发过期快照并接取新轮，用真实 Talk、两次回复与结束请求获得 5000/200；第四个进程同 UID 恢复确认旧轮未付款、新轮已关闭与防重。

每次正常 stop 后用 MVStore.readOnly 打开所属新目录，分别进行两个独立 Morphia Player 文档读取，核对首 Talk、最终目标、主任务和活跃目标的实际 finishTime 属于对应接受日；实际 UUID 接取键匹配 Phone 记录且新旧轮不同，完整 Phone 选择、旧轮归档/过期与周期奖励凭据一致。4.6 的 Mission 查询消息没有 finish_time 字段，完成时间证据来自实际 H2 Player 文档，未补造线协议字段。所有只读检查前后数据库字节不变；每案原资源摘要、配置摘要和未安装脚本边界保持一致。

报告保存以下互不相同的实际运行目录；完整服务端、探针、时钟和只读 H2 日志均保留在目录内，两份运行总日志分别为 `build/daily-message-settled-d-native.log` 与 `build/daily-message-expired-d-native.log`。请求、检查和快照不包括未执行分支，也不以重复查看报告累计。

| 原分支 / 未交付阶段 | `build/smoke/` 下实际目录 |
| --- | --- |
| 202→205 | `daily-message-settled-202-205-oneReply-hhw2r0jb` |
| 202→207 | `daily-message-settled-202-207-oneReply-sqg92y62` |
| 203→205 | `daily-message-settled-203-205-oneReply-hequa9o4` |
| 203→207 | `daily-message-settled-203-207-oneReply-etx4khwc` |
| 未回复 | `daily-message-expired-unpaid-202-205-noReplies-5p77sjp5` |
| 一次回复 | `daily-message-expired-unpaid-202-205-oneReply-jhewdthg` |
| 两次回复未提交 | `daily-message-expired-unpaid-202-205-bothReplies-pdnpb8_z` |

下列为已执行的确切命令；已有报告和 lease 会拒绝重跑，复验需换全新 output 名称。

```powershell
python scripts/smoke-daily-message.py --jar build/cavalry-phone-runtime.jar --scenario settled --output build/daily-message-settled-d.json
python scripts/smoke-daily-message.py --jar build/cavalry-phone-runtime.jar --scenario expired-unpaid --output build/daily-message-expired-d.json
```

真实手机客户端、NPC演出/菜单/短信界面、真实墙钟跨日、其他短信日常及全部剧情、断电原子性仍待完成或验收。


本批最终集成完整 `scripts/build.ps1 -RerunTests` 实际通过492类、13,444项，失败/错误/跳过为零；2,494份编译输入前后无变化。完整XML为 `build/cavalry-phone-full-tests/test`，证据为 `build/cavalry-phone-full-verification.json`，对应冻结D的SHA256 `8d81c933127d7f62b8c022051d5b1d8dd4d8a1a30a4ec917134109c3fd8d7412`。骑兽原生网络为独立C，手机日常原生网络为D；二者的实际数量和SHA分别保留，未将C网络冒称D的网络复验。
