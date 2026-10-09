# 4.6 世界2至6的剧情副本难度

原始43306/source1扩展到来源中实际存在的世界2至6。Default与合法空偏好分别冻结actualWorld/effectiveWorld=actualWorld/difficulty0，Easy冻结actualWorld/effectiveWorld=actualWorld-1/difficulty1。正式玩家世界及试用角色继续采用实际等级，不用Easy的关卡等级降级试用角色。已存快照必须匹配同一实际世界、来源、run和难度，暂停后的相反菜单不改写原局。

| 实际世界 | 默认事件Stage/Level | 简单事件Stage/Level | 原始试用角色等级/晋阶 |
| --- | --- | --- | --- |
| 2 | 203210502/40 | 203210501/32 | 50/3 |
| 3 | 203210503/48 | 203210502/40 | 58/4 |
| 4 | 203210504/56 | 203210503/48 | 68/5 |
| 5 | 203210505/66 | 203210504/56 | 78/6 |
| 6 | 203210506/82 | 203210505/66 | 80/6 |

`src/test/resources/fixtures/raid-difficulty-worlds-4.6.json`逐份核对TurnBasedGameData固定提交724b139d8c9c32d12552eb95745a4fee72bfe48b的11份完整Git blob、29条原始记录。其中五份原始Excel含世界0至6的事件、关卡及1021304试用行；六份完整技能预设包含MaxWithInLevel、W1至W5标准预设，World6确实使用MaxWithInLevel，不造一个不存在的W6预设。原件与安装资源的LF规范化SHA一致，保留安装文件独立SHA，不修改资源。

本机已核对DifficultyAdjustModule/585807/GetEasyWorldLevel，0xccbf5ea执行世界减1并在0xccbf5ed将下溢结果归零；585808/CanRaidAdjustDifficulty在0xccbf381检查缓存世界非零，再检查原始RaidConfig的DifficultyAdjustmentType。结合实际原始世界0至6数据，支持有限43306范围的扩展推断。泛型调用和实际UI交互未全部还原，不宣称完整官方难度规则。World0既有入口保留；-1、7及99等未证明世界继续拒绝菜单更新和新局。

75项新Java检查分别覆盖世界2至6的Default/Easy/未选择事件和报文、原始试用成长及消耗MP后重入、实际世界变化时拒绝结算、两端等级三种初始偏好的活动/暂停Player文档各两次恢复、旧World0局不重解释、非法世界与NPC有效等级。父任务、早期Talk/StageWin、正式角色及NPC摆放是沿用的明确单元夹具，实际MissionRaid/BattleService/TrialManager/SceneEntityLoader参与检查。首次失败与最终结果以build/higher-world-raid-difficulty-red.xml及完整构建日志为准，不把同一范围缺口的多项失败计成独立缺陷。

原生脚本用既有三种难度模式加`--actual-world 2`至`6`，保留原World1和无标志World0调用。每个UID的首次/早期默认查询、实际两次事件入战和QUIT、暂停保存相反菜单及原局恢复按同一流程核对，未选择131次、显式133次请求；8次正常stop、7次同UID H2重启、11份快照和12次存档检查。高世界模式先核对上述11份完整来源；正式队伍与2947份原件SHA保持。实际试用行完整配置与持久化演员快照一致，包含角色等级、装备成长和技能预设。

5966项完整Java与132项Python在主目录和独立源码包均通过；主产物世界1至6乘三种模式的18组原生共2382次请求、198份快照、36场实际事件战斗、144次正常stop和126次各自同UID H2重启通过，独立包另有世界2默认/世界6简单与未选择的397次请求。两组首次UDP连接失败不计通过，失败日志保留；相同SHA的后续复测通过才进入认证。每组数据库独立，不把同一整数UID统计为跨库唯一账号。5966封存产物尚未修复启动就绪问题，后续修复单独验证；最终边界以build/higher-world-raid-difficulty-verification.json及逐模式原生报告为准。前一批5891项认证和源码包封存不改写；151份协议、Java21/AGPL及LunarCore包名保持。这里只扩展43306/source1，source2及其他副本、完整剧情和日常、其他宇宙及活动小游戏、自然战斗输入、实际游戏UI、断电事务仍待补齐，功能状态保持partial。
