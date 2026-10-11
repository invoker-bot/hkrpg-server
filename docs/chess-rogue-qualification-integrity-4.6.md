# 4.6 棋盘宇宙资格记录完整性

area101 的解锁列表和首次 Start 现在共同使用本服已有的 `MissionStateRecords.mainCompleted(player,8013104)`。旧实现只区分 DOING 与其他 phase，会将空 phase、损坏进度/时间、错误任务 ID 或已取消记录当成完成资格。修复在分配新运行和进入场景前拒绝这些记录；Query 仍是只读资料查询，具备有效来源时返回完整 profile，并将未满足资格的 area101 留在锁定状态。

这次没有增加任务奖励结算、首奖、Nous 骰子/天赋、281013 币或 Quit/IsFinished。原 source201 路由、场景/NPC 来源守卫、队伍/玩法占用和原生命令结构沿用已有实现。

## 完整原始资格来源

固定 TurnBasedGameData 提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。新增 [完整来源夹具](../src/test/resources/fixtures/chess-rogue-qualification-integrity-4.6.json) 保留以下四条完整原行、原表 SHA256 与本机客户端完整消费者字节。夹具制作时逐份核对固定提交的完整文件及现有运营资源，CRLF 规范化后原文件相等；没有安装或改写运营资源，没有导入参考运行代码或执行参考二进制。

| 原表与行 | 已证关系 |
| --- | --- |
| RogueDLCArea 101 | UnlockID=1000020；FirstReward=109011 |
| RogueDLCUnlock 1000020 | UnlockFinishWay=1000020 |
| RogueDLCFinishWay 1000020 | FinishMission / ListContain / [8013104]；Progress=1 |
| MainMission 8013104 | 原资格任务；RewardID=18013104 |

资格任务奖励18013104与 area101 首奖109011分别保留；满足完成资格不代表首奖已领取，也不要求存在资格奖励 receipt。

本机完整 `ChessRogueAreaDataItem.SyncGameplayInfo` 函数，方法索引614962、PE边界 `0xcadb680..0xcadb709`，137字节 SHA256 为 `415f065221fb104f5a6ec512feeedd16803f78fbe1a4b430d0e3daffcd195ea4`。它消费 `HIMJKAMHLMJ` field11 的解锁列表与 field10 的首奖领取列表，不消费服务端 QuestState 的 phase、progress 或 finishTime。新的夹具与已有15函数完整来源夹具按完整对象、字节长度与哈希相等核对；静态解析不等于真实客户端 UI 验收。

新增夹具 SHA256：`efadaca6362d229be85fe58d84e5ad63b7ae57c6371f8094695611c6e83ec999`。沿用的完整初次 Query 来源夹具 SHA256：`c8499dd6035cdb7dfc4eb63d763b31556fd780c1fb40d580d38e16e33b15a703`。客户端 GameAssembly/metadata/descriptor 的冻结哈希保存在两份夹具中。

## 本服存档规则

共享谓词要求记录存在、questId为8013104、phase为FINISHED/CLAIMING/CLOSED、progress恰为1、finishTime为正，且任务未被取消。它是已经用于本服任务系统的存档完整性约束；现有 QuestState.advance 将完成进度限制到目标值并记录完成时间。没有据此声称恢复了官方任务存档格式。

合法 CLAIMING 继续具有完成资格。本修复不修补损坏记录、不推进剧情、不授予任务或奖励、不替换场景/来源检查。首次 Start 的拒绝发生在运行 UUID、场景与持久保存分配之前。

## 实际失败与独立修复验证

[ChessRogueQualificationIntegrity46Test](../src/test/java/org/hkrpg/server/protocol/ChessRogueQualificationIntegrity46Test.java) 使用完整原图夹具和真实5547/5426请求，不继承或复制旧61项测试。12种记录分别检验 Query 与首次 Start：空phase；FINISHED/CLOSED的进度0或完成时间0；错误questId；进度2；取消记录；合法FINISHED/CLAIMING/CLOSED；正常DOING。

Query 用例先经真实Start建立健康原source201运行，再改变存档字段，核对解锁列表、空首奖列表、保存不被调用，以及GameplayState、普通队伍、位置/场景引用不变。首次Start用例核对损坏资格拒绝后未创建运行、未切场景、未保存；合法记录仍能实际进入并保存。

先在上一批实际冻结产物 `build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256 `258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1` 上执行新24项：16个纯AssertionFailedError，8个控制通过，errors/skips为0。9个生产类的CodeSource全部指向该jar，编译完成且完整来源检查通过，未把编译/初始化错误算作RED。

RED证据：`build/universe-miracle4-draft/qualification-current-258b-red/verification.json`，新鲜XML SHA256 `6d5a004c512eb82f3a65ad1bf3c7020ba2204fc17da8932b0e541ececcb0c33e`。

随后只改 ChessRogueManager.start 与 ChessRogueProfile.current 两处谓词，从正式源码独立javac编译这两个类，联合执行新24项和旧棋盘83项，合计107/107通过，errors/skips为0。原83项为 InitialQuery9、NativeProfile3、LeaveAndSave10、MerchantEntry61。2个修复类来自隔离编译目录，另外7个受监测生产CodeSource仍来自同一258b jar。

GREEN证据：`build/universe-miracle4-draft/qualification-current-owned-green/verification.json`。此轮本lane源码、正式测试/夹具、封存helper、缓存JUnit与jar前后稳定；完整src扫描本轮也未发生变化。该记录只说明这一独立编译/测试窗口，不表示以后并行编辑期间的全局静态，也不替代root项目级Gradle、全量构建或新产物验收。

## 验证边界

上述独立验证使用本机可信Java21与已缓存JUnit；没有运行Gradle、服务端或游戏客户端。损坏任务记录按实际Gson存档字段构造，未进行损坏H2文件验收。24项Query覆盖已有运行的损坏资格；首次无Run的健康资格/无资格与真实任务奖励结算由上一批独立原生报告覆盖，不能将旧报告改称为本修复的新原生验证。

统一专项51类/1308项、全量512类/13954项均通过，2037个编译与资源输入在全量窗口内不变。新包 `build/castorice-daily-chess-aether-runtime.jar` SHA256 为 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。

同新包的有限原生验收为22次实际KCP请求、67项检查、1次正常停服与1次物理只读H2检查，独立恢复2个实际Player；没有将这两次读取计作进程重启。空phase和FINISHED/progress0夹具分别重复Query与Start，共8次请求拒绝或保持锁定，文档和钱包增量为0。原资格历史通过明确夹具建立，生产refresh实际结算18013104的5000信用点后，合法CLOSED记录能进入原source201首房；没有领取109011。完整报告 `build/chess-rogue-qualification-current-native-v2.json` SHA256 为 `1a836e1a8a8c252f3110c7f6a9d121cd01f9895c7bdaf9b54ee5604b064cbd4b`。

首轮原生工具在 `legal-Query-no-state-notifications` 失败：资格结算的奖励/任务通知留在接收队列，混入后续Query采样。这是工具采样失败，尚未执行首Start/H2，不能计作生产回归或成功验收。保留原目录 `build/smoke/chess-rogue-native-qualification-o76rsl2c` 及封存工具；v2在资格结算后增加只读场景请求屏障，生产源码不变，再执行完整有限流程通过。说明保存在 `build/universe-miracle4-draft/qualification-native-v2/sampling-tool-qualification.json`。

旧商店全矩阵不计入本轮请求。完整客户端GUI、完整资格剧情、全部DLC资格条件、Quit/IsFinished、首奖109011、Nous骰子/天赋/281013账本和实际奇物战斗效果继续待完成，见 [本批验证](castorice-daily-chess-aether-4.6.md)。
