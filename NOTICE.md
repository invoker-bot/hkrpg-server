# 来源和归属

回响奖励策略参考 LunarCore development 固定提交 `ec315bb1cf5cf86ae0f6ee52824e07f1fef9a5c4`（AGPL-3.0）的 `src/main/java/emu/lunarcore/game/rogue/RogueInstance.java`：不同本命途祝福的 `3/6/10/14` 阈值、最多四项。`RogueResonance` 为独立 Java 实现并保存明确项目政策名，未导入参考运行时，也未声称该政策已匹配官方 4.6。原始七份回响/事件资源固定在 `TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，完整原行与双方 SHA 保留在测试夹具，验证边界见 `docs/rogue-resonance-4.6.md`。

HKRPG Server 主工程派生自 Melledy 和 LunarCore contributors 的 LunarCore。

- 源码：https://github.com/Melledy/LunarCore
- 分支：`lite`
- 导入提交：`04b5de871c62895091c4d65243b2e03997e66f23`
- 上游许可证：GNU Affero General Public License version 3，原文见 `LICENSE`。
- 导入内容：`src/main/java/emu/lunarcore`、`src/generated`、`src/main/resources/logback.xml`、`lib/kcp.jar`、Gradle wrapper、默认 JSON 配置。
- 修改内容：自有构建和启动入口、配置默认值、资源预检、默认数据初始化、包校验、令牌处理、HTTP 错误响应、状态/健康接口、关闭资源处理、空白格式整理、文档和测试。

保留上游包名与代码归属。生成的协议代码来自该固定提交；它是 4.2.0 基线，不应标注为已兼容其他版本。

`lib/kcp.jar` 是上游已随仓库提供的 KCP 实现二进制；当前仍沿用，尚未完成源码可重现构建和独立来源追溯。替换为有完整源码和归属的依赖列入开发计划。

所有参考项目的 URL、提交、许可证、源码统计和处理器证据保存在 `references.lock.json`。只有读取过的源码或官方项目声明作为对照依据；这些信息不意味着上游代码已移植，也不意味着客户端验收通过。

## 4.6 协议定义与流程参考

- 来源：[Mar7thLover/March7thHoney-OpenSource](https://github.com/Mar7thLover/March7thHoney-OpenSource)
- 固定提交：`fe00bdbda3c28ca33d01f171566aa35b5ab95083`，4.6 分支，AGPL-3.0。
- 导入：`protocols/4.6` 中的 144 份 proto、命令常量、命令语义别名，以及由它们生成的描述符和 JSON 命令表。
- Java 的 Quest 状态与领取流程参考该提交的 `GameServer/Game/Quest/QuestManager.cs`、Quest 收发包和 `Common/Data/Excel/QuestDataExcel.cs`、`FinishWayExcel.cs`、`AchievementDataExcel.cs`，并按本项目的持久化与兼容边界重新实现。未导入 C# 服务运行时。
- Java 的 4.6 登录序列、角色/命途、装备、玩家外观及场景语义映射参考同一提交的 `Recv/Player/HandlerPlayerLoginCsReq.cs`、`HandlerPlayerLoginFinishCsReq.cs`、`Common/Database/Avatar/AvatarData.cs`、`Inventory/InventoryData.cs`、`Player/PlayerData.cs`、`GameServer/Game/Scene/SceneInstance.Serialization.cs`、`Scene/Entity/EntityProp.cs` 及对应收发包。Java 使用独立描述符重新实现；没有继承参考项目将全部内容包标记 Finished 的占位行为。
- 重新生成与校验方法见 [protocols/README.md](protocols/README.md)。保留来源与许可证，不把结构转换结果宣称为已通过客户端验收。

猫猫糕多人房间的请求、成员加入、移动和布设消息结构参考上述固定 Honey AGPL 提交的 `GameServer/Game/TrainCakeCatch/TrainCakeCatchManager.cs`、相应收发包和 4.6 协议；返回位置、权限、并发房间批次、独立玩家领取、布设验证及持久化由本项目独立 Java 实现。客户端只按固定 SHA 静态核对，没有运行参考仓库程序或引入其运行时依赖。正式抽取概率与实际客户端联机仍须独立验收。

本轮战斗字段别名及动作输入参考同一 Honey 提交的 `GameServer/Game/Battle/BattleInstance.cs`、`MazeBuff.cs`、`SceneSkillManager.cs`、关卡请求处理器，以及 `Common/Database/Avatar/AvatarData.cs`。任务接取条件参考其主任务配置和 `GameServer/Game/Mission/MissionManager.cs`；Java 的任务、日常、登录活动和奖励账本重新实现，不沿用上游固定任务完成/满活跃的响应。

经典宇宙的地图编号公式、站点与房间池关联参考 Melledy/LunarCore 的 `development` 分支，固定提交 `ec315bb1cf5cf86ae0f6ee52824e07f1fef9a5c4`，AGPL-3.0：`data/excel/RogueAreaExcel.java`、`game/rogue/RogueRoomData.java` 和 `RogueInstance.java`。没有导入其 2.5 协议类或运行时依赖；基础 4.6 生命周期已经依据本机字段重新实现，完整游戏 UI 尚未验收，边界见 [玩法说明](docs/gameplay-4.6.md)。

基础战斗碎片规则参考同一提交 `RogueInstance.onBattleFinish` 的非首领胜利按 20～40 乘参战 NPC 数量；Java 独立实现真实实体绑定、数量冻结、同文档入账、防重与整数溢出保留。此为本项目采用的玩法规则，不声称已还原官方 4.6 私有掉落配方。首领房和未知房型不使用这条基础规则。

经典祝福强化的普通/稀有/传说基础价格 100/130/160 及一级到二级展示范围参考同一 AGPL 提交的 `PacketGetRogueBuffEnhanceInfoScRsp` 与 `RogueInstance.enhanceBuff`，作为本项目采用的规则。Java 另行实现实际休息房黑塔校验、报价冻结、同文档扣费、防重、折扣及独立国服协议；没有采用旧版本的命令号或生成类。

## 本地外部游戏数据

宇宙房间内容与怪物关联、祝福战斗增益及强化费用参考上述 LunarCore development 提交的 `RogueEntityLoader`、`RogueBuffData` 和 `RogueInstance`。剧情完成动作参考上述 Honey 提交的 `Game/Mission/FinishAction/Handler`，Java 另行实现持久化动作游标和中断保护。音律请求字段参考其 4.6 MusicRhythm 协议与收发包；Java 按资源解锁条件、活动窗口和奖励账本重新实现，没有沿用固定满星响应。奇物效果、客户端输入重演和游戏客户端接受仍须独立验收。

剧情 `ChangeLineup` 的资源参数语义另对照 [Mar7thLover/HyacineDH-Core](https://github.com/Mar7thLover/HyacineDH-Core)，固定提交 `dd54de89ca8df8355fae5727e49e9e2bbba55344`，AGPL-3.0，`GameServer/GameServices/Mission/FinishAction/Handler/MissionHandlerChangeLineup.cs`。Java 独立实现已持有正式角色的校验与换队，没有导入其 C# 代码，也没有沿用自动授予角色或试用角色流程。4.6 场景删除类型 3 对照上述 Honey 提交的 `GameServer/Server/Packet/Send/Scene/PacketSceneGroupRefreshScNotify.cs`；旧描述符缺少该值时仅允许该已知删除模式的明确转换，其余未知值仍拒绝。

Equal 的 EnterPlane/EnterMapByEntrance 条件对照上述 Honey 提交的 `Game/Mission/FinishType/Handler/MissionHandlerEnterPlane.cs`、`MissionHandlerEnterMapByEntrance.cs` 和本地固定 4.6 Mission 配置。BattleLose/BattleQuit 的关卡参数和相互完成分支来自资源 Mission 8024111；delSubMission/delMission 的标记完成语义对照同一提交的两个 FinishAction Handler。Java 另行实现结算身份校验、事件快照、引用图预检和循环执行保护，保留中断动作的待核对状态。

[DimbreathBot/TurnBasedGameData](https://github.com/DimbreathBot/TurnBasedGameData) 的固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 提供声明为国际服 4.6.0 的 JSON 资源。数据保存在被忽略的 `references/` 和 `resources/`，不作为本工程源码或 jar 分发内容。每个本地安装文件的 SHA-256 与源提交记入资源清单。游戏数据的权利与代码的 AGPL 来源分别记录。

Java 运行依赖由 Maven Central 下载，其许可证保持各自归属。AGPL 派生工程提供给用户时，应同时提供对应源码、修改记录和本许可证；此说明不是完整的发布归属审查。

## 国服客户端静态分析与辅助来源

只读核对用户指定的国服 4.6 客户端 PE、IL2CPP 类型/方法与序列化调用，固定文件哈希和命令/字段证据见 [client-protocol-evidence.md](docs/client-protocol-evidence.md)。`Universe46` 依据本机证据重新实现原生查询、待选通知和祝福选择，没有修改既有生成类、proto 或固定命令表。通知模式 101 另参考 hkrpg-go 固定提交 `ef47e49a233133c46d70ab5a4deb4bc71ae49571` 的 `pkg/constant/command.go`，AGPL-3.0；旧协议字段号没有作为新协议字段号使用。

辅助阅读 [NeonTeam58/HNA-MORAX](https://github.com/NeonTeam58/HNA-MORAX)，固定提交 `5b169a9ec74dd8e369ff20933dbb9052b1b75b30`，MIT，Copyright (c) 2026 NeonTeam58。仓库保留在忽略的 `references/`，没有执行仓库二进制、编译、导入代码或新增运行依赖。其测试版常量不适用于本机；项目客户端解析脚本的常量由本机反汇编独立核对。该辅助来源单独记入 `references.lock.json`，不计为新增玩法功能。


剧情 UseItem 的成功扣除操作计量、指定商品目标和实际领队目标参考上述 Honey AGPL 提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083` 的 `GameServer/Game/Mission/FinishType/Handler/MissionHandlerUseItem.cs`、`MissionHandlerBuyShopGoods.cs`、`MissionHandlerTeamLeaderChange.cs` 及 InventoryManager/ShopService。Java 独立实现真实扣除、参数/场景、库存数量、防重事件与保存边界；未沿用参考购买处理器无条件完成任务的行为。宇宙编队 704/719 使用同一提交原始 dumper 作命令来源，并以本机客户端类型/调用/序列化交叉核对。

跑酷原生消息定义参考March7thHoney-OpenSource（AGPL-3.0、fe00bdbda3c28ca33d01f171566aa35b5ab95083）的Proto/4.6/CmdParkourType.proto；同一固定本机4.6文件只读核对发送/接收/序列化及毫秒字段。ParkourManager、持久化状态、校验与回归为本项目Java实现。关卡/球车数值来自既有固定4.6资源快照，资源文件不随源码/JAR发布；未复制未声明许可证的参考实现。

跑酷Quest数值关联与名次阈值继续只读参考既有固定4.6资源；FinishQuest计量参考同一AGPL Honey fe00bdb的MissionHandlerFinishQuest.cs，ParkourQuests及窗口实体/回归由本项目以Java独立实现，未复制资源文本或其他许可证不明实现。

跑酷排行继续使用固定AGPL Honey同一CmdParkourType.proto及固定本机CN4.6只读字段证据；ParkourRanking为本项目Java实现。仅读取ParkourRankingList的数值关卡关联，不发布NPC资源文本/头像。


跑酷Raid继续只读参考固定AGPL Honey fe00bdbda3c28ca33d01f171566aa35b5ab95083的Proto/4.6/CmdRaidType.proto、Common.proto及RaidManager流程，并与同一本机CN4.6发送/消费/编码核对。ParkourRaid、Raid46及持久化场馆由本项目独立以Java实现；没有复制参考项目对空主任务列表取首项或空成功响应的实现。RaidConfig数值仅在本地读取，不随发布包分发。

- 普通消耗品与固定礼包由本项目独立Java实现；使用已固定March7thHoney（fe00bdbda3c28ca33d01f171566aa35b5ab95083，AGPL-3.0）的Common协议/命令表和GameServer/Game/Inventory/InventoryManager.UseItem.cs核对入口，未照搬其中从HP计算SP的恢复逻辑。本机CN4.6序列化与只读资源数字用于交叉核验；未引入参考项目运行库或下载的二进制。实际数量、预检、一次扣除及中断记录属于本项目实现，验证边界见docs/verification.md。

- 任务单选交付独立以Java实现，继续核对固定March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083（AGPL-3.0）的Proto/4.6/CmdMissionType.proto、Common.proto和HandlerFinishCosumeItemMissionCsReq.cs；没有复制其中扣除全部配置备选物品的处理。只读审查固定hkrpg-go ef47e49a233133c46d70ab5a4deb4bc71ae49571（AGPL-3.0）的UseSelectedItem自动完成行为，未采用其实现。4.6资源只用于数值和配置形状核验，不随包发布；请求调用与字段进一步由同一本机客户端静态交叉验证。

剧情维度与试用配置基础：自行实现维度索引校验、主世界资源隔离及试用角色世界等级双键；保留继承LunarCore代码来源。只读核对 March7thHoney-OpenSource `fe00bdbda3c28ca33d01f171566aa35b5ab95083`（AGPL-3.0）的 `SpecialAvatarExcel.cs` 和 `AvatarManager.cs`；未采用其向下寻找等级的回退。4.6资源仅用于核对字段和数值，来源为锁定TurnBasedGameData提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，不随源码分发。

剧情区间条件、原子资源目录和指定维度场景/锚点校验自行实现。只读参考 March7thHoney-OpenSource `fe00bdbda3c28ca33d01f171566aa35b5ab95083`（AGPL-3.0）的StoryLine、StoryLineFloorData、StroyLineTrialAvatarData与StoryLineEntityLoader；保留资源条件，未采用其无条件加载维度。SceneInfo字段1225来自同一冻结4.6定义，不修改协议文件。

试用光锥在构造时直接初始化临时战斗配装，避免调用继承的正式装备接口发送PlayerSync；此修正自行实现，保留LunarCore出处与许可证。


剧情分支运行、作用域试用角色与原生状态由本项目独立以Java实现，流程只读参考固定March7thHoney-OpenSource fe00bdbda3c28ca33d01f171566aa35b5ab95083（AGPL-3.0）的StoryLineManager、LineupManager及CmdStoryLineType。保留继承LunarCore的包名与出处；未复制参考实现的位置共享引用或缺少队伍生命周期的行为。资源数值不随包分发；CN6205的源码语义差异由同一本机固定SHA客户端静态消费和序列化核对。未执行参考二进制或客户端。


StoryParty46原生剧情队伍适配为本项目独立Java实现。身份字段同时参考固定AGPL Honey Common/Database/Lineup/LineupData.cs的序列化，以及本机固定SHA客户端TeamModule、TeamData和PKPICFPDHEO的静态读写。未导入参考运行依赖，未执行客户端，保持LunarCore出处与AGPL许可。


TrialActorExcel及专用晋阶/技能树资源适配为本项目独立Java实现，继承原LunarCore资源类并保留其AGPL出处；仅解析操作者已安装的固定4.6资源，未复制参考运行依赖或分发游戏资源。


试用资源编号与战斗试用编号分离、剧情待切换施放/关卡/花萼拦截及1308试用Actor施放阶段规则修正为本项目对继承LunarCore Java代码的修改，保持AGPL与原始出处；未复制未授权参考代码或修改冻结协议。


TrialSkillTrees指定强化形态由本项目独立以Java实现；字段和数值继续只读核对已锁定TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b。保留继承LunarCore的试用技能等级5/8策略及原出处，未引入参考运行库或修改协议生成文件。形态选择和缓存校验不代表完整模板或客户端玩法验收。


TrialActivity46及普通试用生命周期由本项目独立Java实现，保留继承LunarCore的TrialManager/TrialInstance出处与AGPL。原生消息继续只读核对固定Honey fe00bdbda3c28ca33d01f171566aa35b5ab95083（AGPL-3.0）的CmdActivityType.proto，并由同一本机固定SHA客户端发送、消费及WriteTo交叉核对；未改变CmdId占位、描述符或协议生成类。AvatarDemo数值来源仍为已锁定TurnBasedGameData，不随源码分发。临时角色预检/清理、返回重试和NPC完成校验均为本项目实现，未复制参考EndActivity的位置引用或自动完成行为。


AvatarDemo.SPList初始能量的范围/列表预检及性别候选原索引分配由本项目独立实现；数值只读核对已锁定4.6资源，资源不随源码交付。没有复制参考运行代码或修改生成协议。


TrialRewards与TrialCompletion为本项目独立Java实现，使用自身RewardLedger进行冻结、防重、容量预检和中断记录。2613/2623及2653/2644继续只读参考固定Honey AGPL协议，取奖发送2653及字段8、响应1/3/12、查询4/7/8由本机同一SHA客户端静态调用与编码交叉核对；没有复制参考缺少通关与防重检查的领奖处理。StageID终身一次为本项目规则，活动窗口语义尚待接入。


TrialRun运行快照、恢复校验与LoopbackTrialRecoveryProbe为本项目独立Java实现，保持LunarCore AGPL来源及包名；不引入参考仓库运行依赖，不启动游戏客户端。保存意图/原位置、冻结Demo/SpecialAvatar及体力并在登录恢复，尚无战斗回合重放。


TrialRun技能字典与TrialSkillTrees恢复校验为本项目独立实现；技能限制沿用资源本身的等级、晋阶、强化分支及前置点，5/8请求上限仍来自LunarCore旧实现而非完整4.6自定义模板。


TrialSkillPresets独立实现依固定4.6资源中Config/BattleLineupPage/SkillTreePointPreset读取数值政策，同SHA客户端GamePlayStatic技能模板分支静态交叉核对；来源资源提交724b139d8c9c32d12552eb95745a4fee72bfe48b，仅本地忽略安装并记录校验清单，不附带发行。无运行依赖references/。无模板的旧资源仍有LunarCore兼容5/8规则，其他模板类型尚未验收。


TrialActivityCatalog独立实现使用本地固定724b139d8c9c32d12552eb95745a4fee72bfe48b资源的ActivityAvatarDemo和ActivityConfig数值关联，客户端静态RoleTrialActivityData/RoleTrialUtils消费者交叉核对面板查表。未复制参考项目实现，无references运行依赖，完整客户端目录仍未验收。


RogueBuffShop/RogueShopPlan/RogueShopOffer和RogueBuffShop46由本项目独立Java实现，保留LunarCore AGPL工程来源及包名；现有4.6协议只读核对Honey AGPL固定提交fe00bdbda3c28ca33d01f171566aa35b5ab95083及本机同SHA客户端发送/编码/消费，无参考运行依赖或生成类手改。NPC/Dialog/RogueRoom/Buff数值源于已固定724b139d8c9c32d12552eb95745a4fee72bfe48b本地资源，不随源码交付。商店100/150/250价格及三件均匀库存为本项目规则，官方库存算法、奇物/公式商店及完整UI未验收。


## 奇物商店本项目实现

RogueMiracleShop、RogueShopContext与RogueMiracleShop46为本项目独立Java实现，未复制无许可证的参考代码。AGPL协议字段来源仍为March7thHoney-OpenSource fe00bdbda3c28ca33d01f171566aa35b5ab95083，并以本机CN4.6只读调用/编码证据校正命令5656和语义。资源快照沿用TurnBasedGameData OS4.6 724b139d8c9c32d12552eb95745a4fee72bfe48b，本地忽略且不进入发布包。200碎片定价与均匀库存为本项目规则；不宣称官方完整实现或客户端验收。


列车世界动作/选择/历史及网络验证为本项目独立Java实现；字段只读核对本机固定SHA国服4.6客户端，并使用前文固定AGPL兼容Honey协议快照。未导入客户端代码、资产、反编译二进制或未许可参考实现。WorldDataConfig/入口数据仅在本地忽略资源目录使用；实验入口门槛仅隔离副本调整，客户端和官方世界解锁未验收。


宇宙/花萼剧情条件与花萼网络探针为本项目独立Java实现；规则和协议只读参考前文固定资源、Honey AGPL和hkrpg-go固定提交ef47e49a233133c46d70ab5a4deb4bc71ae49571的FinishCocoon数值关联，未复制额外参考代码。验证任务仅在隔离目录提供，不覆盖运营数据或本地原资源；完整客户端和官方剧情未验收。

2026-10-02 花萼原生响应对照 March7thHoney-OpenSource AGPL-3.0 固定提交 fe00bdbda3c28ca33d01f171566aa35b5ab95083 的 HandlerStartCocoonStageCsReq、HandlerQuickStartCocoonStageCsReq 和 PacketStartCocoonStageScRsp，确认 Wave 传入战斗及响应回显请求的CocoonId。新增Java适配和合成任务探针为本项目实现，未导入引用仓库运行时或二进制。

2026-10-02 初始角色选择只对照 March7thHoney-OpenSource AGPL-3.0 固定提交 fe00bdbda3c28ca33d01f171566aa35b5ab95083 的 HandlerSetPlayerInfoCsReq/PacketSetPlayerInfoScRsp 和 hkrpg-go 固定提交 ef47e49a233133c46d70ab5a4deb4bc71ae49571 的 SetPlayerInfoCsReq/CreateCharacterSubMission 事件关联，未导入代码、固定完成ID、Lua或运行时。Java条件/确认存储和合成探针为本项目实现，原生响应使用本机已核对SHA的只读消费与写出证据。

回响菜单恢复与能量字段证据为本项目独立实现，继续使用前述固定 AGPL 协议来源和 LunarCore 的明确项目阈值政策。本机客户端仅作固定 SHA 的静态只读字段核对；十三个方法的完整 PE 字节保留在测试证据中，不作为运行时执行依赖。未加载或执行客户端 DLL，未导入参考仓库二进制、无许可证代码或能力脚本。

本地商品价格响应依据 March7thHoney-OpenSource 的 AGPL-3.0 固定提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083` 中 `Config/Custom/QueryProductInfoConfig.json` 整理 88 个人民币价格档位，保存在 `src/main/resources/sdk-shop-prices.json`；原档位与新增免费档位分开。SDK 路由及校验由本项目独立实现，不依赖 references/ 目录，参考价格表不代表已验证的实时官方价格。
