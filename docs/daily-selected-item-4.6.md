# 4.6 原始物品选择日常 3011605 / 3011608

本批补齐两条原始日常的服务器链路：原配额接取、原场景 NPC 门、客户端明确选择一件原允许物品、真实库存扣除、原前置交付目标完成后报告 Talk、原主奖励和活跃度、隔日归档及新接取。它不执行客户端 Act、CustomString 或物品选择界面，也不把普通 prop 点击当成交付。

## 固定来源与原链路

来源为 `references/TurnBasedGameData` 的固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，资源标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。夹具保留 **36 份全文件 bytes / 131 个完整原对象**，Config 原件用 Base64，完整 Excel 原件用无损 zlib/Base64；并非截取若干参数冒充原件。独立 Java 21 / 冻结 39601 包证明全部所选对象属于对应原全文件，本机 **34 份完整对象相等**，数字未经过 Python float 截断，源与本机数字词法也没有差异。

| 原日常 | 原子目标 / 原末段 Talk | 原楼层 / 组 / 索引 / NPC / 实例 | 原单件选择 |
| --- | --- | --- | --- |
| 3011605 | 301160501 / Mission_301160502 | 20122 / 20122001 / G102 / 82 / 11103 / 400001 | 401004、401012 |
| 3011608 | 301160801 / Mission_301160802 | 20132 / 20132001 / G74 / 46 / 2108 / 400001 | 401007、401009、401011、402001、402005 |

两个 Main 的原头仍为 Manual → Auto；原 full/partial 都有两个 `Auto[所属 MainMissionID]` 起点，末段只有对应 Talk。两条同属原 `GroupID=30108`、`DailyMissionType=1`，原每日配额为 **1**，原解锁主线为 **1011501**，活跃目标为 **2100003**。同一日不能同时占两条同组委托。每次合法结算使用原 `RewardID=231` 给 **5000 信用点**；原活跃目标领奖给 **200 活跃度**，不是经验或额外第二笔主奖励。

原 NPC 图先 `TriggerPerformance E301160501 / E301160801`，在对话结束触发 `ConsumeItem_301160501 / ConsumeItem_301160801`；原 Mission 图等待此字符串，再调用 `SelectMissionItem`，`OnlyPerformance=false`、`SlotNum=1`、物品列表和 `CMI_物品ID` 分支均来自原全图。末段 Mission 的 `CollectDataConditions` / `ByCompareSubMissionState ... Finish` 要求对应交付目标已完成，之后才有 `FinishPerformanceMission Mission_...502 / ...802`。9 份原 E/Act 对应（含两个开场和七种交付选择）、原文本、Main/full/partial、楼层/组、物品、NPC、入口、奖励/配额规则均被保留。

以下两份原 NPC 图在本机服务端仍为 **source-only / 未安装**，本批没有向运营资源安装、伪造或自动演出它们：

- `Config/Level/NPCDialogue/P20122/F20122001_G102/DialogueMain_F20122001_G102_N400001.json`
- `Config/Level/NPCDialogue/P20132/F20132001_G74/DialogueMain_F20132001_G74_N400001.json`

服务端资格仅限这两张完整、加载匹配的原图和原 NPC 放置。它保留上述原客户端入口证据，不以缺失服务器副本推定客户端演出已完成，也不全局开放其它 `SelectMissionItem`、任意 Performance 或未知脚本。没有导入参考仓库代码、运行其二进制或增加运行依赖。该数据仓库的固定树未提供 LICENSE/COPYING，不能称其数据官方许可或 AGPL；项目仍保留 LunarCore 来源与原 AGPL 许可。

## 请求、保存与隔日边界

使用既有 4.6 协议，未手改生成类。冻结包实际 descriptor 证明：`FinishCosumeItemMissionCsReq` 的命令为 **1261**，`sub_mission_id=2`、`item_list=4`；`ItemList.item_list=2`，`Item.item_id=15`、`Item.num=6`。`FinishTalkMissionCsReq` 为 **1205**，`talk_str=2`、`sub_mission_id=6`、`custom_value_list=11`。本批 Talk 不接受臆造 CustomValue。

1261 保持客户端明确选一件的语义。無选项、多选、错数量、缺物、外来 UID、错楼层、非原完整图或 NPC 不匹配不能扣物；服务器不代选。已知 301160501 / 301160801 ID 各自只属于唯一原 Main，不能借另一条完整原图的资格进行扣物。扣除继续走既有 `MissionManager.submitItems` 与真实 `Inventory.removeItem`，没有替代库存模型。第一笔 reservation 和同一 Player 文档中的日轮收据绑定 UID、原 source、接取日、实际选择，以及 Main/交付/末段 Talk 三个真实 Quest 接取 UUID；旧全局 payment 不自动迁移为新接取的完成证据。

末段只在原交付子目标确实完成、原 payment/plan 一致、真实原 NPC/组/楼层匹配时报告成功。保存失败恢复原 Talk Quest 对象、进度、finishTime、收据和奖励域，并在保存成功后才同步；重试不二次扣物或发主奖励。已经结算或已经扣物但未报告 Talk 的旧轮，隔日使用既有原任务快照，并保留实际旧 payment/plan 在独立退休收据中；新轮移除当前旧 payment，只能新选、新扣、新报告，旧物品不退回。

**这不等于所有库存故障都可原子回滚。** 既有首次付款 `reserve` 写入异常时仍保留 pending 状态；当日重试继续 `SYSTEM_BUSY`，需要人工对账，不自动重复扣除或补造成功。次日也不静默丢弃此未决付款。这里验证的自动回滚是 Talk 文档保存及已明确可归档的隔日任务/支付/选择视图，不能扩称库存数据库写故障、崩溃恢复或未知扣款都已验收。

## 已完成验证

最初不可变 `build/out-followup-core-material-candidate-runtime.jar` 的 SHA256 为 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`。独立 JUnit 没有加入 `build/classes/java/main`，14 个生产类 CodeSource 都来自该冻结包，夹具和依赖前后 SHA 无变化。

- 首 **16 项 / 6 个真实 AssertionFailedError / 10 对照通过**；扩展 **39 项 / 22 个真实失败 / 17 对照通过**；补保存和缺失原 partial 后，同源最终 **43 项 / 26 个纯行为失败 / 17 对照通过**，均无 error、skip、abort。它们是递增集合，不相加。
- 初稿把 `claimQuest(...).retcode()` 写成不存在的 `claim(...)`，javac 日志保留；这是草稿工具错误，不计行为 RED。
- owned 初次 **43 / 43**，补 4 个隔日保存回滚和两条各 3 次真实 Morphia Player 读取后 **49 / 49**。额外自审发现新 guard 缺末段 Quest 会抛异常，先实际 **51 项 / 2 个 AssertionFailedError / 49 通过**，再补原 Doing 尾接取校验；这两项是 owned 阶段问题，不混入冻结包 43/26。
- build-only 新 **53 项**加原日常、G61、初段 NPC、副续章、Quest identity 和既有 selected/consume peers，先实际 **316 项全部通过**，0 failure/error/skip/abort。
- 后续自审发现两个已知交付 ID 可借另一个 Main 的完整原图资格。先以真实库存及 1261 入口独立锁定 **2 项 / 2 个 AssertionFailedError / 0 error/skip/abort**，再补唯一 ID/Main 校验；这属于 owned 阶段的新缺陷，不混入原冻结包 43/26。
- 更正后按正式类名、classpath 夹具及内联原 SHA helper 重新编译，实际 **55 项新回归 + 263 项 peers = 318 项全部通过**，0 failure/error/skip/abort。19 个 CodeSource 区分 6 份 captured shared、3 个 owned 类和 10 个冻结包类；所有 scoped inputs 前后不变。两条 Morphia 用例共有 **6 次实际 Player 文档读取**，使用随机端口隔离内存 Mongo，不算 H2 进程重启或客户端验收。

正式测试布局仅更换夹具资源入口和移入原 SHA 校验 helper，没有改变行为断言。下面的正式集成证据单独记录完整 Gradle 和实际 KCP/H2；早期 build-only GREEN 不替代这些验证，也不借上一批 516/14106 全量通过覆盖本次新增行为。

证据位于 `build/story-script-draft/daily-selected-item-3011605-8/`：`source-proof.json`、`java-source-proof.json`、`wire-proof.json`、`selected-item-39601-final-source-red-first.json`/XML/inputs、`selected-item-owned-missing-tail-red-first.json`、`selected-item-owned-peers-green-first.json`、`selected-item-owned-id-alias-red-first.json`、`selected-item-formal-layout-green-second.json`/XML/inputs、`shared-overlay-proof.json` 和 `shared-hooks.patch`。这套草案未写运营配置/数据库/现有资源。

## 正式集成与复核（2026-10-14）

日常生产类、55项正式回归、完整来源夹具、原生验证脚本和三份Java辅助工具现已进入正式目录。其生产类和测试内容与上一批完整构建的输入SHA一致；复核报告为 `build/continuation-20261014/historical-source-proof.json`。上一批实际完整Gradle为520类/14,226项零失败，新包SHA256为 `3eeeab41ec750f60b92c2ab3a536760afab14eb53c71581d34e18bafb02fa05c`，完整构建和运行边界见[共同验证](feixiao-selected-item-blessing-console-4.6.md)。

本次重新运行正式14项Python工具检查，并以正式审计器重新读取 `build/daily-selected-item-current-native-success.json` 及两库的原始探针、正常停服日志、只读H2记录和辅助工具哈希，均通过。审计输出 `build/daily-selected-item-resumed-audit-20261014.json` 确认140次请求、164项检查、10次正常停服、8次各库同UID重启和20次实际Player文档读取；审计本身不启动服务器，不能称为本次新包重新执行这些请求。

3011605成功case来自第三轮的 `completedCases[0]`，该整轮随后在3011608失败；3011608来自第四轮单独成功case。两个隔离库均本地UID10001，计数仅合并上述成功case，失败轮额外停服不计入。正式目录保留这项区别。仅每Main一个受控选项流程实际跑过KCP；七种选择、NPC演出/Act/CustomString、自然墙钟跨日、首次库存保存故障的自动对账和真实游戏客户端验收仍未完成。
