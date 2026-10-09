"""合并全部已锁定参考项目的接口入口；接口存在不等于功能完成。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVIEWED = {
    "StartChallengeCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/MissionChallengeWinTest.java",
                  "src/test/java/org/hkrpg/server/protocol/MemoryChallenge46Test.java", "scripts/smoke-mission-challenge.py"],
        "gap": "4.6正式角色忘却之庭子集，packed5/12及AvatarIdentifier11/8正确区分两半场；原始varint/重复/超限/试用助战/混合表示与stage_info拒绝。实际1/15入场、三场资源战斗、回合扣除后判星、原生查询/结算/离场21项KCP及同UID重连/H2恢复通过；1117项全量通过。接取门槛、单目标父任务与两半场唯一8001为隔离夹具；官方互斥编队/开放门槛、特殊角色、虚构/末日/完整奖励与实际客户端演算/UI待完成/验收。"},
    "GetCurChallengeCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/MemoryChallenge46Test.java", "src/test/java/org/hkrpg/server/gameplay/MissionChallengeWinTest.java", "scripts/smoke-mission-challenge.py"],
        "gap": "固定4.6语义别名接入MEMORY当前ID/status/round_count/半场编队；实际1/15原生查询核对首半0/第二半8回合，结算详情8/16回合。未知新字段、其他挑战模式、完整通知与客户端演算/UI待验收。"},
    "LeaveChallengeCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/MissionChallengeWinTest.java", "src/test/java/org/hkrpg/server/gameplay/MemoryChallengeLeaveTest.java", "scripts/smoke-mission-challenge.py"],
        "gap": "核对挑战实例/entry/plane/floor，成功清旧实例，失败保留实例/原挑战编队并返回4.6 retcode4；重复离场与未知请求拒绝。五项原生回归先失败，实际1/15离场/下一次入场/重复拒绝KCP通过。4.2继承空响应、失败场景返回下的战斗恢复、完整队伍恢复和游戏UI待完善/验收。"},
    "GetChallengeCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/MemoryChallengeLeaveTest.java", "src/test/java/org/hkrpg/server/gameplay/ChallengeHistoryStarsTest.java", "scripts/smoke-mission-challenge.py"],
        "gap": "原生查询仅MEMORY一/二半场，尊重unlockAllChallenges，返回已有star/taken_reward/score_id和来源组奖励位，不发送尚未支持的Boss扩展以免断开。两项回归先失败，实际1/15历史星数5/5及同UID重连/正常H2重启保持；独立目标位累积全64组合通过。最高层/开放门槛、其他模式/完整奖励和客户端UI待完成/验收。"},
    "GetHeartDialInfoCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/HeartDial46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialMissing46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialFull46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-heartdial.py", "scripts/smoke-mission-raid.py"],
        "gap": "原生6334查询严格Normal、Missing/Full及Player剧情副本的Missing/Lock/UnLock/Normal子集；返回实际步骤、情绪、历史演出和解锁状态，不制造完成。Lock流程19项回归含11项先失败；缺失与锁定两笔收据、任务门槛、Morphia重载和资源变更拒绝已验证。7条TrialOnly图、Control、跟踪、全部官方剧情加载门槛与真实游戏UI待完成/验收。"},
    "SubmitEmotionItemCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/HeartDialMissing46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialFull46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-mission-raid.py"],
        "gap": "原生6360/6316的准确单件条件分别推进Missing0、Lock2；实际库存身份/余额与唯一当前NPC整批预检，扣款前保存独立阶段收据，完成后发布6383。锁定道具不跳过指定主任务；并发每阶段只扣一次，中断保留待核对记录且不自动重扣。仅支持本服严格Player/Mission/SkipUI副本图；TrialOnly、多件条件、Control、跨文档崩溃自动对账与游戏UI待完成/验收。"},
    "ChangeScriptEmotionCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/HeartDial46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-heartdial.py", "scripts/smoke-mission-raid.py"],
        "gap": "原生6305仅允许当前Normal4、实际已加载绑定NPC及资源可选情绪；情绪保存后响应，非普通步骤、错误/重复字段、未解锁和跨玩法忙碌拒绝。Player剧情副本归属新增先失败回归，退出后正常恢复。其余复杂图、NPC切换官方门槛及真实游戏UI待完成/验收。"},
    "FinishEmotionDialoguePerformanceCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/HeartDial46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialFull46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-heartdial.py", "scripts/smoke-mission-raid.py"],
        "gap": "原生6358按真实NPC、当前步骤/情绪的准确对话记录冻结已接受任务目标；Missing/Full/Lock/UnLock历史保留，Normal需真实任务门槛或已存释放证明。错误阶段与伪造未付款历史拒绝，一次演出不消费新解锁任务。19项Lock回归覆盖四阶段；完整演出脚本、TrialOnly/Control与真实游戏UI待完成/验收。"},
 'SetPlayerInfoCsReq': {'tests': ['src/test/java/org/hkrpg/server/protocol/ProfileCreation46Test.java', 'scripts/smoke-profile-creation.py'], 'gap': '原生41/30支持is_modify=true首次昵称/性别及真实已有8001/8002命途选择，显式确认推进有限CreateCharacter目标；合成KCP/重连/H2重启已验证。重复同选幂等、冲突拒绝；is_modify=false、后续资料变更/改名限制及官方完整开局/游戏UI待完成。'},
 'GetBasicInfoCsReq': {'tests': ['src/test/java/org/hkrpg/server/protocol/ProfileCreation46Test.java', 'scripts/smoke-profile-creation.py'], 'gap': '原生80/67只查询显式确认的创建标记与实际性别，丢弃未映射空设置；默认男主不代表已选择。cur_day=1及周花萼0等继承值尚非完整实际统计；设置、改名时间与游戏UI待完成/验收。'},
 'StartCocoonStageCsReq': {'tests': ['src/test/java/org/hkrpg/server/protocol/CocoonEntry46Test.java', 'src/test/java/org/hkrpg/server/protocol/CocoonWave46Test.java', 'src/test/java/org/hkrpg/server/gameplay/MissionFarmingBattleTest.java', 'scripts/smoke-farming-mission.py'], 'gap': '原生1432/1484绕过旧响应命令缺失；实际1001花萼三波、体力与两步隔离任务经合成KCP/重连/H2重启通过。附加选项拒绝；场景物件身份、助战/强化变体及游戏UI待完成/验收。'},
 'QuickStartCocoonStageCsReq': {'tests': ['src/test/java/org/hkrpg/server/protocol/CocoonEntry46Test.java', 'src/test/java/org/hkrpg/server/protocol/CocoonWave46Test.java', 'src/test/java/org/hkrpg/server/gameplay/MissionFarmingBattleTest.java', 'scripts/smoke-farming-mission.py'], 'gap': '原生快速入口保留实际花萼ID/波次；与普通入口共用真实战斗服务。合成KCP验证第二局一波、两局只扣40体力及重复结算拒绝；附加选项、助战/强化变体及游戏UI待完成/验收。'},
    "PlayerGetTokenCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/ProtocolBridgeTest.java", "scripts/smoke-4.6.py"],
        "gap": "账号令牌和玩家创建已通过合成 KCP 登录；真实国服客户端、协议加密和新 SDK 密码验证待完成。"},
    "PlayerLoginCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/LoginFlow46Test.java", "scripts/smoke-4.6.py"],
        "gap": "4.6 基础信息及内容包初始化已验证；请求遥测/语言尚未应用，完整通知和客户端验收待完成。"},
    "PlayerLoginFinishCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/LoginFlow46Test.java", "scripts/smoke-4.6.py"],
        "gap": "ACK 已验证；未发送旧固定全解锁图鉴。真实月卡、完整任务和每日活跃初始化通知待完成。"},
    "GetAvatarDataCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/InitialData46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/LoginFlow46Test.java", "scripts/smoke-4.6.py"],
        "gap": "全量角色、命途、皮肤和装备关系已有回归；请求过滤、新角色完整成长及国服客户端验收待完成。"},
    "GetBagCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/InitialData46Test.java", "scripts/smoke-4.6.py"],
        "gap": "基础背包及装备归属已验证；宠物、新增物品类别和实际客户端物品操作待完成。"},
    "GetAllLineupDataCsReq": {
        "tests": ["scripts/smoke-4.6.py"],
        "gap": "真实玩家全量编队读取已通过合成 KCP；4.6 编队变更、新增类型与客户端验收待完成。"},
    "GetCurSceneInfoCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/InitialData46Test.java", "scripts/smoke-4.6.py"],
        "gap": "起始场景快照、组状态及属性已验证；全地图资源、场景交互/切换、剧情和真实客户端进入待完成。"},
    "ContentPackageGetDataCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/LoginFlow46Test.java", "scripts/smoke-4.6.py"],
        "gap": "仅返回配置目录及 Init 初始状态；剧情条件、当前内容选择、状态持久化和转移尚未实现。"},
    "SetNicknameCsReq": {
        "tests": ["scripts/smoke-4.6.py"],
        "gap": "中文昵称经 KCP 修改后可在重连和进程重启时恢复；输入校验、改名限制和真实客户端表现待完善。"},
    "FinishQuestCsReq": {
        "tests": ["src/test/java/emu/lunarcore/game/quest/QuestHandlerTest.java",
                  "src/test/java/emu/lunarcore/game/quest/QuestProgressTest.java"],
        "gap": "只推进配置允许的客户端目标；大量完成条件、其余周期与日常规则尚未实现。"},
    "GetQuestDataCsReq": {
        "tests": ["src/test/java/emu/lunarcore/game/quest/QuestProgressTest.java",
                  "src/test/java/emu/lunarcore/game/quest/QuestPersistenceTest.java",
                  "src/test/java/org/hkrpg/server/protocol/ProtocolBridgeTest.java"],
        "gap": "真实状态和文档重载已验证；成就经验、全部解锁条件及国服客户端表现待验收。"},
    "TakeQuestRewardCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Quest46HandlerTest.java",
                  "src/test/java/emu/lunarcore/game/quest/QuestProgressTest.java"],
        "gap": "并发、重复和待核对领取不会再次发奖；背包跨文档事务及中断自动对账待实现。"},
    "PlayerHeartBeatCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/ProtocolBridgeTest.java", "scripts/smoke-4.6.py"],
        "gap": "本机回环 UDP/KCP 心跳已通过；真实国服客户端和加密流程尚未验收。"},
}

# 4.6 玩法回归仍不代表客户端验收。
REVIEWED.update(
{'SceneCastSkillCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                   'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                   'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                   'scripts/smoke-4.6.py'],
                         'gap': '基础普攻、非法索引与基础秘技校验；复杂能力参数和新角色全脚本待补齐。'},
 'SceneEnterStageCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                    'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                    'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                    'scripts/smoke-4.6.py'],
                          'gap': '事件 ID 与关卡解析、实际 KCP 进入验证；全部进入条件和客户端待验收。'},
 'PVEBattleResultCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                    'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                    'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                    'scripts/smoke-4.6.py'],
                          'gap': '当前战斗身份、并发/重复/异常结算和统计校验；胜负重演及事务待实现。'},
 'GetCurBattleInfoCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                     'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                     'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                     'scripts/smoke-4.6.py'],
                           'gap': '当前战斗种子/波次保持稳定；跨进程战斗恢复待实现。'},
 'QuitBattleCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                               'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                               'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                               'scripts/smoke-4.6.py'],
                     'gap': '实际 KCP 退出不发通关奖励；复杂重战参数暂不支持。'},
 'GetMissionDataCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/MissionFarmingBattleTest.java',
                                   'src/test/java/org/hkrpg/server/gameplay/MissionUniverseVictoryTest.java',
                                   'src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                   'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                   'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                   'scripts/smoke-4.6.py'],
                         'gap': '真实待办任务快照已通过 KCP；全部剧情脚本与客户端待验收。'},
 'GetMissionStatusCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                     'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                     'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                     'scripts/smoke-4.6.py'],
                           'gap': '仅返回实际任务状态；未知目标不制造完成状态。'},
 'AcceptMainMissionCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                      'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                      'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                      'scripts/smoke-4.6.py'],
                            'gap': '接取条件校验及存储状态；全部解锁脚本待完成。'},
 'FinishTalkMissionCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                      'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                      'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                      'scripts/smoke-4.6.py'],
                            'gap': '校验当前目标、精确文本、楼层与声明的 Former 变量，支持部分完成动作；全部演出和客户端待验收。'},
 'GetDailyActiveInfoCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                       'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                       'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                       'scripts/smoke-4.6.py'],
                             'gap': '真实点数、档位和日期周期；官方任务抽样与全部类型未完成。'},
 'TakeApRewardCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                 'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                 'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                 'scripts/smoke-4.6.py'],
                       'gap': '配置档位、点数门槛和唯一领取；中断需人工核对，客户端待验收。'},
 'TakeAllApRewardCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                    'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                    'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                    'scripts/smoke-4.6.py'],
                          'gap': '逐档位检查并仅报告成功项；全部客户端展示待验收。'},
 'GetLoginActivityCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                     'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                     'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                     'scripts/smoke-4.6.py'],
                           'gap': '仅显示配置开放且已解锁的登录活动；领取日展示混淆字段未确认。'},
 'TakeLoginActivityRewardCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                            'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                            'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                            'scripts/smoke-4.6.py'],
                                  'gap': '累计登录日、时间窗口及重复领取校验；客户端展示待验收。'},
 'GetActivityScheduleConfigCsReq': {'tests': ['src/test/java/org/hkrpg/server/gameplay/GameplayProgressTest.java',
                                              'src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java',
                                              'src/test/java/emu/lunarcore/game/battle/BattleSettlementTest.java',
                                              'scripts/smoke-4.6.py'],
                                    'gap': '原生 4.6 时间窗口目录；其余活动和试用完整流程待补齐。'}}
)

REVIEWED.update({
    "FinishCosumeItemMissionCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/MissionConsume46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/MissionSelectedItem46Test.java",
                  "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java"],
        "gap": "固定费用及UseSelectedItem一件单选交付、实际信用点、库存/场景/取消条件、冻结选择、并发/中断/Morphia和整数别名/重复字段已回归。参考项目自动完成与扣除全部备选项不采用；其他选择/字符串语义、跨文档事务及正向KCP/游戏UI验收待完成。"},
    "GetRogueInfoCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Universe46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseUnlock46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseMiracle46Test.java", "scripts/smoke-4.6.py"],
        "gap": "原生查询和基础生命周期有回归；部分解锁图、通关历史、奇物资源 ID/次数已接入。全部解锁、奇物获取事件/剩余效果、命途和国服 UI 待完成。"},
    "EnterRogueMapRoomCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Universe46RoomTest.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseRestRoom46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/Universe46Test.java", "scripts/smoke-4.6.py"],
        "gap": "只接纳已分配相邻房间；新增实际非战斗类型5休整到达/重入后开放绑定出口、待选关门及无胜利奖励，14条回归含初始7条先失败及Duck另1条先失败；限定资源Duck地图入口保留邻接选择，未知脚本不启用，保存先通知/并发/Morphia/类型与资源/失败回滚已验证，休整可离开复用cleared为项目规则。前房清理、选择门槛和传送回滚已回归。独立队伍的基础开始/重入已进入真实资源房间；相邻切换成功仍为合成场景测试，游戏 UI 待验收。"},
    "HandleRogueCommonPendingActionCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Universe46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseReroll46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseMiracle46Test.java",
                  "src/test/java/org/hkrpg/server/gameplay/RogueMiraclesTest.java",
                  "src/test/java/org/hkrpg/server/gameplay/RogueMiracleSnapshotTest.java",
                  "src/test/java/org/hkrpg/server/gameplay/RogueClubCouponTest.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseChaosTrametes46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseCompoundEye46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseFuliGlue46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseDimensionalDie46Test.java",
                  "src/test/java/org/hkrpg/server/protocol/UniverseUncertaintyBox46Test.java",
                  "src/test/java/emu/lunarcore/data/RogueMazeBuffResourceTest.java",
                  "src/test/java/org/hkrpg/server/gameplay/UniverseBlessingTest.java", "scripts/smoke-4.6.py"],
        "gap": "本机核对 5676 的祝福/奇物选择及空重置分支；测不准匣取得即发1～2个可构造/未持有/已解锁祝福，奖励/收藏/拥有同文档保存；11条回归含初始9条先失败覆盖确定两项、购买/选择、空池不收费、仅一候选、重复并发、通知在保存后及Morphia；均匀数量/选池与截断为项目规则，即时奖励、通知、防重/重连/H2正常重启KCP已有隔离六项，713项产物样本600000一级；拆表加载修复后的718项产物KCP已取得612621/612646两项并跨重连/H2保持，游戏UI待验收。降维骰子两候选/双选、下一批次、半完成保存和共用刷新预算有12条回归（初始9条先失败），字段6总次数/9当前次数由本机编码和消费核对；连续菜单/序号/预算采用项目规则，双选KCP/UI待验收。福灵胶生成非空战后三星池后一次损毁，12条回归含10条先失败；刷新保留三星池/不再消耗、与3/2并存、并发、Morphia及原生通知先保存有回归；触发时机为项目规则，战后KCP/UI待验收。跃迁复眼战后Common选项二级与完整候选等级计划冻结，10条回归含9条先失败，原生展示/选择/刷新、战斗绑定、旧/坏记录与实际Morphia恢复有验证；二级资源缺失保守关闭，奇物3取得/购买KCP已验证，战后选择/刷新及UI待验收。混沌云芝战后免费次数与原有付费次数并存，8条原生回归含7条先失败、真实Morphia已用次数保留；免费优先和有限不重复候选池采用项目规则，2/12取得/购买已有隔离资源KCP，免费重置KCP与游戏UI未验收。候选/批次/重置费用和次数冻结、并发/文档恢复已有回归，俱乐部券实际胜利收益在开战时冻结、8条回归含6条先失败、Morphia溢出待发和并发重试保留；整数向下取整为项目规则，2/12取得/购买已有隔离资源KCP，战斗加成及免费重置KCP/游戏UI未验收。奇物获得参数冻结、缺失/坏快照保留、Morphia重载及十进制折扣有10条新增回归；选择/复活效果通知先保存有2条新增原生回归。奇物仍为 12 项效果子集（含1降维骰子、2混沌云芝、3跃迁复眼、5测不准匣、7福灵胶及12俱乐部券），未解释奇物6额外参数等完整语义。空请求仅作用于当前唯一菜单，不能辨别跨菜单延迟点击。奖励事件、剩余效果、结果展示及客户端验收待完成。"},
    "GetMainMissionCustomValueCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java"],
        "gap": "仅返回已存储的声明变量；ClientOnly/LoadGroup 的完整脚本语义尚未支持。"},
    **{name: {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/MusicGameplayTest.java",
                  "src/test/java/org/hkrpg/server/gameplay/MusicRewardRecoveryTest.java",
                  "scripts/smoke-music-rewards.py",
                  "src/test/java/org/hkrpg/server/protocol/Gameplay46HandlerTest.java",
                  "src/test/java/org/hkrpg/server/protocol/Music46CompositionTest.java",
                  "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java"],
        "gap": "音律开局冻结奖励、准确结算报告/中断保留、原生标量宽度与重复校验有9条新回归；真实101/102关卡、270003库存、重连/H2重启及资源改价经合成KCP六项通过。CLAIMING不自动重发，跨文档中断须人工对账；音律关卡、资源编曲/预设、音效门槛/通知、隐藏曲目最低评级及全局高难度标志已有回归；高难度启用仍按资源任务和普通关卡评级的保守门槛，完整官方流程、输入重演及客户端验收待完成。"}
       for name in ["MusicRhythmDataCsReq", "MusicRhythmStartLevelCsReq", "MusicRhythmFinishLevelCsReq", "MusicRhythmSaveSongConfigDataCsReq"]},
    **{name: {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Message46Test.java",
                  "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java"],
        "gap": "已接取消息图、回复分支、终点校验、整批预检、真实任务目标和文档重载已回归；PFMHKKKAGCD 映射、非零文本变体、Raid/Link/跨段脚本、游戏 UI 待验收。"}
       for name in ["GetNpcStatusCsReq", "GetNpcMessageGroupCsReq", "GetMissionMessageCsReq",
                    "FinishItemIdCsReq", "FinishSectionIdCsReq", "FinishPerformSectionIdCsReq", "FinishMultiPerformSectionIdCsReq"]}
})

REVIEWED.update({name: {
    "tests": ["src/test/java/org/hkrpg/server/protocol/Universe46LifecycleTest.java",
              "src/test/java/org/hkrpg/server/gameplay/UniverseTalentEffectsTest.java",
              "src/test/java/org/hkrpg/server/gameplay/UniverseLifecycleTest.java",
              "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java", "scripts/smoke-4.6.py"],
    "gap": "本机字段静态核对；独立编队及返回坐标持久化，11/21/32 开局能量/额外选择/重置费用已有服务端回归。真实 KCP 验证基础资源房间与跨重连/H2 重启恢复，天赋效果未通过实际 KCP 或游戏 UI。命途、特殊角色和完整结果待完成。"}
    for name in ["StartRogueCsReq", "EnterRogueCsReq", "LeaveRogueCsReq", "QuitRogueCsReq"]})

REVIEWED["ReviveRogueAvatarCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseRevive46Test.java", "src/test/java/org/hkrpg/server/protocol/UniverseClockRevival46Test.java", "src/test/java/org/hkrpg/server/protocol/UniverseRevivalWire46Test.java", "scripts/smoke-rogue-revival.py", "scripts/smoke-4.6.py"],
    "gap": "原生1811/1817/1803与10/13字段由发送者、消费者、类型槽、四消息常量及18份完整PE方法核对；32项新增请求检查、17项真实先失败，角色/实体/tag/长度全编码高位、重复/截断/未知字段拒绝且不进入扣费。原始306/8000103/G22/300019复活装置、双人200扣费与70→9/999奖励、重连/stop正常关服重启由专用KCP脚本核对；倒地与70/490额外费用为仅测试离线夹具。每人单价/附加费用取整/超过499比较为本服策略；其他恢复计数/效果变体、天赋折扣、完整官方取得与倒地链、全部玩法/UI和断电原子性待完成/验收。"}


REVIEWED.update({
    "InteractPropCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/SceneInteraction46Test.java", "scripts/smoke-4.6.py"],
        "gap": "本机发送/WriteTo 核对配置字段和 UInt64 上下文；支持免费普通物件/平台/电梯/锚点状态校验、保存和部分目标，未知物件不报告成功。费用、宝箱、完整机关/演出及游戏 UI 待完成。"},
    "GetUnlockTeleportCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/SceneAnchor46Test.java", "scripts/smoke-4.6.py"],
        "gap": "基础楼层仅报告配置初始开放或玩家实际激活的有效锚点；完整故事线/内容变体和游戏 UI 待完成。"},
    "GetSceneMapInfoCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/SceneAnchor46Test.java", "scripts/smoke-4.6.py"],
        "gap": "基础楼层返回真实锚点、保存值及当前加载组属性；不制造探索区块。完整探索、宝箱统计、非当前楼层组及内容/故事线变体和 UI 待完成。"},
    "EnterSceneCsReq": {
        "tests": ["src/test/java/org/hkrpg/server/protocol/SceneAnchor46Test.java", "scripts/smoke-4.6.py"],
        "gap": "基础入口检查配置前置和锚点解锁；无效传送不回退入口坐标或报告成功。其他入口条件、坐标请求、混淆分支、内容/故事线变体及游戏 UI 待完成。"},
})
REVIEWED["GetCurSceneInfoCsReq"]["tests"].append("src/test/java/org/hkrpg/server/protocol/UniversePortal46Test.java")
REVIEWED["GetCurSceneInfoCsReq"]["gap"] = "基础快照/组状态属性及宇宙实际出口目标/开闭/旧楼层隔离已有回归；完整地图、入口 UI、机关和剧情演出仍待验收。"
REVIEWED["SceneCastSkillCsReq"]["tests"].append("src/test/java/org/hkrpg/server/protocol/SceneInteraction46Test.java")
REVIEWED["EnterRogueMapRoomCsReq"]["tests"].append("src/test/java/org/hkrpg/server/protocol/UniversePortal46Test.java")
REVIEWED.update({name: {
    "tests": ["src/test/java/org/hkrpg/server/protocol/FirstTalk46Test.java",
              "src/test/java/org/hkrpg/server/gameplay/FirstTalkLifecycleTest.java",
              "src/test/java/org/hkrpg/server/gameplay/SubMissionRewardTest.java",
              "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java", "scripts/smoke-4.6.py"],
    "gap": "本机发送/序列化字段已核对；实际已接取目标、场景、展示脚本校验，首次记录与原始目标列表存档，重复报告不推进新目标。任务奖励按完成快照结算；未知脚本效果、NPC 系列入口、全部剧情和游戏 UI 待完成。"}
    for name in ["GetFirstTalkByPerformanceNpcCsReq", "FinishFirstTalkByPerformanceNpcCsReq"]})
REVIEWED["FinishTalkMissionCsReq"]["tests"].append("src/test/java/org/hkrpg/server/gameplay/SubMissionRewardTest.java")


REVIEWED.update({name: {
    "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseTalent46Test.java",
              "src/test/java/org/hkrpg/server/gameplay/UniverseTalentEffectsTest.java",
              "src/test/java/org/hkrpg/server/protocol/UniverseReroll46Test.java",
              "src/test/java/org/hkrpg/server/protocol/UniverseFinish46Test.java",
              "src/test/java/org/hkrpg/server/gameplay/InventoryGrantDeltaTest.java", "scripts/smoke-4.6.py"],
    "gap": "42 个节点查询/前置/扣点及恢复已有回归，实际 KCP 核对列表/非法激活；11/21/32 开局能量/额外祝福/重置和信仰债券折扣已通过服务端回归。其他统计/命途效果、完整天赋界面仍未完成，不能把节点激活算作全部效果有效。"}
    for name in ["GetRogueTalentInfoCsReq", "EnableRogueTalentCsReq"]})

REVIEWED.update({name: {
    "tests": ["src/test/java/org/hkrpg/server/protocol/CycleScore46Test.java",
              "src/test/java/org/hkrpg/server/protocol/RogueCurrency46Test.java", "scripts/smoke-4.6.py"],
    "gap": "七天周期、冻结经典房间分数、实际10档奖励、33/34余额、防重/跨周/重载和超限债券明确丢弃已有服务端回归；经典关联及整笔超限保留采用本项目规则。其他模式计分、官方规则、通用对账与游戏 UI 待完成。"}
    for name in ["CycleScoreRewardGetDataCsReq", "CycleScoreRewardTakeRewardCsReq",
                 "CycleScoreRewardDestroyExceedRewardCsReq"]})


for name in ("GetRogueBuffEnhanceInfoCsReq", "EnhanceRogueBuffCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseEnhance46Test.java",
                  "scripts/smoke-4.6.py"],
        "gap": "经典休息房实际黑塔、首次冻结报价、一级升二级、奇物13折扣、余额不足/待选/离场拦截、并发扣费及Morphia恢复已有回归；1885字段13及1807字段3/6独立于参考同名棋盘模式定义。基础100/130/160价格及每祝福每局一次采用本项目规则；官方价格变体、响应布尔字段13和真实客户端UI待验收。"}

for name in ("GetRogueShopBuffInfoCsReq", "BuyRogueShopBuffCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseBuffShopTest.java", "scripts/smoke-rogue-shop.py"],
        "gap": "原生5616/5615字段与本机发送、消费和编码交叉核对；实际休息房NPC1007及BuffShop资源脚本、冻结最多三件库存/价格、整批扣费/售出、并发/重复/余额不足/认证/非法字节和Morphia重载通过15项Java回归。真实1000012房间商人在隔离首站/300碎片配置下经KCP购买600000、100报价/200余额与效果通知，重连/H2重启保留库存归属/报价/防重；五项检查未修改来源及运营数据，不代表官方房间生成或游戏客户端。Common/Rare/Legendary的100/150/250价格与均匀选池为本项目规则。购买在同文档记录祝福收藏，RogueUnlockBuff解锁及Morphia存取通过失败回归。固定数据6个RogueRoom关联该商人，仅2个脚本保留BuffShop打开动作，未开放完整脑内宇宙模式；经典房间生成、完整对话门槛、官方库存/折扣/刷新、奇物/公式商店及完整商店/游戏UI仍待完成/验收。"}

for name in ("GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseMiracleShopTest.java"],
        "gap": "本机实际5675查询/5656购买、5660/5683响应与商品ID/费用/Available/AlreadyHave字段已核对；不按参考5672同名别名发送。实际NPC1013及MiracleShop平铺页面、最多三件冻结报价/库存、八项支持效果、并发/防重/余额不足/字段校验及Gson/Morphia恢复有15项Java回归，先保存交易再通知。200碎片/均匀库存采用本项目规则。三页面关联59个组，主要属于虫灾等扩展房间，当前基础RogueRoom分配不含这些入口，真实资源入口8020101/组35/实例400001的2/12购买、通知、重复拒绝、重连/H2正常重启分别5项合成KCP通过；隔离首房/300碎片/2件目录不修改源资源，祝福商人另5项回归通过。完整扩展宇宙、事件、官方生成/库存/价格/折扣/刷新、奇物全集及游戏UI仍待完成/验收。"}

REVIEWED["PickRogueAvatarCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/UniverseDownload46Test.java",
              "scripts/smoke-4.6.py"],
    "gap": "1864/1821 的装置编号6、正式角色列表14/15和结果码13已由本机发送/序列化核对；当前休息房实际1001装置、持有正式角色、独立生命/冻结初始能量、并发与Morphia恢复已有回归。每局每台装置一次下载一名且不自动部署采用本项目规则；额外编号列表11/6语义、试用/剧情角色、特殊上限及真实游戏UI尚未确认/验收。实际KCP只验证非法目标拒绝。"}

REVIEWED["ReplaceLineupCsReq"] = {
    "tests": ["scripts/smoke-story-trial.py", "src/test/java/org/hkrpg/server/protocol/UniverseLineup46Test.java", "src/test/java/org/hkrpg/server/gameplay/StoryLineWireTest.java", "scripts/smoke-4.6.py"],
    "gap": "剧情换队纠正为实际发布的AvatarTrialType1，拒绝LimitType2与FormalType3，两项新回归先失败。实际2022304/2024201/维度2、202230507替换、初始/后续通知、原生队伍/场景角色、重复对话拒绝和旧角色可再选择已通过14项KCP；同UID三份快照在重连与正常H2重启后保持角色可用/独立队伍/目标完成，26份来源SHA不变。三个实际角色七种世界等级共21组合可构造，未写存档或发布正式角色。1133项Java、8项脚本、47项通用网络和两次Dispatch通过；前置接取/入口和前导任务场景为明确隔离夹具，完整官方剧情门槛/演出/游戏UI仍待验收。经典宇宙已下载正式角色的槽位顺序/领队/独立状态、待选/战斗/离场/终局拦截、失效运行与列表别名有回归；原生704/719通过实际KCP正向探针。独立剧情试用队伍有Java原生身份9/5/8/extra=0、双角色重排/领队/独立状态和认证/旧请求/待切换拦截回归，剧情正向KCP与真实UI未验收。719使用本机JHICJLAIPLD字段7/9，不复用简名Join定义字段6。普通编队、虚拟/助战及全部试用变体和完整客户端UI未验收。"}
for name in ("BuyGoodsCsReq", "ChangeLineupLeaderCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/MissionCommerceTest.java"],
        "gap": "Java真实操作已接入指定商品购买和实际队长切换的剧情目标；非法数量/溢出/重复价格/常见库存容量、重复/错误队长及新目标不复用同次事件有回归。客户端字段/通知、完整商品开放/限购/周期、复杂物品及交易中断恢复仍待验收/完成。"}

for name in ("GetQuestDataCsReq", "FinishQuestCsReq", "TakeQuestRewardCsReq"):
    REVIEWED[name]["tests"].append("src/test/java/emu/lunarcore/game/quest/ParkourQuestTest.java")
    REVIEWED[name]["gap"] = "跑酷关联Quest的窗口隔离、通关/前N名/汇总回溯、防止客户端代完成及并发/部分发奖保留有Java回归；" + REVIEWED[name]["gap"]

for name in ("ParkourGetDataCsReq", "ParkourStartLevelCsReq", "ParkourEndLevelCsReq", "ParkourGetRankingInfoCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/Parkour46Test.java", "src/test/java/emu/lunarcore/game/quest/ParkourQuestTest.java", "scripts/smoke-4.6.py"],
        "gap": "原生8390/8396、8384/8393、8400/8385、真实窗口/楼层/前置/球车、保存最佳毫秒时间及首次通过、重复/并发/错误/别名整数拒绝、冻结目标/Morphia恢复和已有剧情目标推进有回归。实际KCP覆盖未玩查询及未开放/未开始拒绝；成功跑酷为Java协议回归。关联活动Quest的通关/前N名/前置汇总按窗口回溯，并发领奖防重及部分余额/保留持久化已有回归；成绩依赖客户端报告。NPC客户端资源合并、个人/双向好友实际排行已有回归；完整Raid进出/其他统计/输入重演和游戏UI待完成。"}

REVIEWED["ParkourGetRankingInfoCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/ParkourRanking46Test.java", "src/test/java/org/hkrpg/server/gameplay/ParkourFriendRankingTest.java", "src/test/java/emu/lunarcore/game/friends/FriendshipKeyTest.java", "scripts/smoke-4.6.py"],
    "gap": "8397/8388查询返回资源排行关卡、非空汇总对象及实际个人最佳用时/UID/球车，同球车完整关卡汇总有回归；旧无球车证据、未知字段与异常资源不伪造排名，真实Morphia恢复有测试。好友只读取双向已接受关系与已保存实际成绩，单向/待确认/删除/旧错误键排除，双向并发查询和Morphia有回归；NPC由客户端资源合并。混合球车汇总、官方有效成绩规则和游戏UI仍待完成/验收。"}

for name in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetSaveRaidCsReq", "GetAllSaveRaidCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/ParkourRaid46Test.java", "src/test/java/org/hkrpg/server/protocol/MissionRaid46Test.java", "src/test/java/org/hkrpg/server/protocol/MissionRaidFinish46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-mission-raid.py", "scripts/smoke-4.6.py"],
        "gap": "44306001跑酷Player/SkipUI入口保留；新增严格Player/Mission/SkipUI单主任务、无保存/目标/奖励/试用/限制/Buff副本。IsInRaid禁止普通世界接取/推进，父任务需实际接取且未取消，资源起点按唯一入口查找，换层先保存保留标记，失败重试、实际移动位置、暂停/重入、指定完成入口及实际完成目录有16项Java回归，含九项先失败。43305原始双楼层KCP19项基础检查通过；钟表10108两阶段扣费/副本联动29项通过，父任务/剧情尾部和替代NPC加载为隔离夹具。副本目标新增RaidFinishCnt单Raid/单步与RaidFinishedAndTransfer有限Equal子集：实际主/最终子任务完成、保存的成功返回及地点校验，11项回归含五项先失败及真实Morphia恢复；错误/取消/暂停/失败返回不制造完成。不伪造RaidData保存。7个TrialOnly钟表副本、Control/所有官方剧情/保存/目标/额外副本奖励和真实游戏UI仍待完成/验收。"}

REVIEWED["UseItemCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/gameplay/ItemUseTest.java", "src/test/java/org/hkrpg/server/protocol/ItemUse46Test.java", "scripts/smoke-4.6.py"],
    "gap": "原生558/556实际恢复/扣除/失败响应有Java回归；百分比HP/SP、实际正式队伍/存活复活目标、批量上限、固定礼包资源/溢出/容量/真实增量、并发及Morphia中断记录已有测试。KCP拒绝未持有道具，不虚构使用或奖励。固定HP数值需要真实属性计算；其余Buff/饱食度/试用挑战队伍、可选随机礼包/配方、跨文档事务与中断自动对账及游戏UI待完成/验收。"}

REVIEWED["SetTrainWorldIdCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/TrainWorld46Test.java", "src/test/java/org/hkrpg/server/protocol/TrainWorldScene46Test.java", "src/test/java/org/hkrpg/server/gameplay/TrainVisitedWorldTest.java", "src/test/java/org/hkrpg/server/gameplay/StoryTrainWorldTest.java", "scripts/smoke-train-world.py"],
    "gap": "实际1489请求与1401响应字段由本机发送/消费/序列化核对，严格UInt64场景编号与世界字段；SceneInfo1031仅发送当前列车场景的本项目运行编号，重建/重连不能复用。原生9条、场景5条、声明动作8条、真实进入3条Java回归，含12项先失败回归。拒绝认证前、其他场景、未知/未到达世界、过期/高位别名、未知/重复/超宽字段及战斗/待剧情/试用/宇宙/场馆/保留动作。选择与已到达世界保存后通知，不改正式队伍或普通地图世界；Morphia/并发/场景重建有回归。到达范围来自实际Town/Maze进入、服务端声明动作及旧最后世界/有效选择，是本项目规则，尚未解释官方WorldUnlockConfig完整表达式。网络验证入口门槛仅隔离配置两条真实入口；游戏UI与完整世界解锁仍待完成/验收。"}

REVIEWED["GetStoryLineInfoCsReq"] = {
    "tests": ["scripts/smoke-story-trial.py", "src/test/java/org/hkrpg/server/gameplay/StoryTrialReplacementTest.java", "src/test/java/org/hkrpg/server/gameplay/StoryTrialSkillFreezeTest.java", "src/test/java/org/hkrpg/server/gameplay/StoryLineLifecycleTest.java", "src/test/java/org/hkrpg/server/gameplay/StoryLineWireTest.java", "scripts/smoke-4.6.py"],
    "gap": "剧情换队纠正为实际发布的AvatarTrialType1，拒绝LimitType2与FormalType3，两项新回归先失败。实际2022304/2024201/维度2、202230507替换、初始/后续通知、原生队伍/场景角色、重复对话拒绝和旧角色可再选择已通过14项KCP；同UID三份快照在重连与正常H2重启后保持角色可用/独立队伍/目标完成，26份来源SHA不变。三个实际角色七种世界等级共21组合可构造，未写存档或发布正式角色。1133项Java、8项脚本、47项通用网络和两次Dispatch通过；前置接取/入口和前导任务场景为明确隔离夹具，完整官方剧情门槛/演出/游戏UI仍待验收。新增裸StoryLineReplaceTrialPlayer严格单任务子集，先替换实际冻结队伍再推进已接取目标；取消/未知脚本/变量奖励/NPC条件/冲突故事线/战斗切换拒绝。14项回归含7项先失败，1131项全量通过，保存检查点与20次并发不重置生命；17882目标只读加载唯一202230507→1041209绑定，四份来源SHA不变。实际三角色七种世界等级共21组合已构造，2022304剧情的14项原生KCP/重连/H2恢复通过，26份来源SHA不变；前置接取/入口和前导任务场景为隔离夹具，完整官方剧情/UI未验收。恢复构造仅校验已冻结技能，新增缺中间等级的预设节点不阻断旧剧情队伍；4条快照回归含新失败回归。6234/6225查询已注册并校验认证/空请求，只报告实际保存的分支和可用试用角色。部分资源ChangeStoryLine动作、独立试用队伍、生命/能量/返回位置、切换中断、原生9/5/8与extra=0身份、严格704/719编辑及Morphia重载有43项剧情运行回归，另有3项跨玩法拦截；CN通知6205列表3/11按本机消费与编码修正，SceneInfo2045补实际分支。可用角色单独持久化，未加入角色拒绝，下场角色仍可选，旧记录只恢复已存队伍；秘技加成角色下场按新上限保存，切换中阻止秘技/关卡/花萼/怪物袭击。SkipJoinLineup、领队锁定、其余动态加入/移除、完整试用配装、客户端主动切换、完整脚本和游戏UI仍待完成/验收；KCP另验证实际2022304入场/替换/独立队伍和原生角色类型1，重连/正常H2重启恢复；完整官方剧情门槛和UI未验收。"}

for name in ("SceneCastSkillCsReq", "PVEBattleResultCsReq", "GetRogueInfoCsReq",
             "StartRogueCsReq", "EnterRogueCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/UniverseCombatFragmentsTest.java",
        "src/test/java/org/hkrpg/server/protocol/UniverseInitialCoins46Test.java"])
    REVIEWED[name]["gap"] = (
        "4.6资源开局50碎片及基础非首领战斗碎片按本项目规则获得、冻结参战/防重和真实KCP重连/H2重启已有验证；"
        + REVIEWED[name]["gap"])


for name in ("StartTrialActivityCsReq", "LeaveTrialActivityCsReq", "GetTrialActivityDataCsReq", "TakeTrialActivityRewardCsReq"):
    REVIEWED[name] = {
        "tests": ["src/test/java/org/hkrpg/server/gameplay/TrialActivityWindowTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialActivityLifecycleTest.java", "src/test/java/org/hkrpg/server/protocol/TrialActivityWireTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialPresetPolicyTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialRunSkillFreezeTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialSkillEligibilityTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialRunRecoveryTest.java", "src/test/java/org/hkrpg/server/gameplay/TrialRewardWireTest.java", "src/test/java/org/hkrpg/server/gameplay/GameplayPersistenceTest.java", "scripts/smoke-4.6.py"],
        "gap": "原生2662/2614、2636/2677核对阶段字段及身份，认证/重复字段/整数别名/错误阶段/返回失败重试有Java回归；整批角色预检，缺员/重复/冲突拒绝，入场失败恢复和本局临时角色清理已有回归。放弃和战败不报完成，配置实际NPC胜利才返回；2646已按本机阶段1/状态10直接发送。21条生命周期、8条运行恢复、8条原生领奖、4条技能限制、8条预设、7条技能快照、1条Morphia奖励恢复和1条原生完成字节回归；初始能量按SPList原候选索引校验并写入实际试用角色，这些回归不代表完整玩法；入场冻结奖励数量，真实配置NPC通关持久化；2613/2623仅查真实通关，2653/2644拒绝未通关/重复/非法字段，容量预检与发放中断保留实际增量及唯一预留。当前StageID终身一次属于本项目规则；入场意图/冻结队伍与原场景持久化，恢复校验位置/成员后发布，失败保留可重试；待返回完成普通队伍恢复。7条目录/窗口回归与合成原生目录已校验ActivityConfig真实异号面板及当前TypeParam，关闭/锁定/过期/错模块拒绝；中断战斗完整回放、跨文档自动对账、技能按连续资源等级/角色等级/晋阶/前置节点校验，TYPE_CUSTOM实际读取11份预设，支持固定/最大/角色等级政策且快照冻结，其他类型未验收；完整配装和游戏UI仍待完成/验收。"}


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueRankAggroMiraclesTest.java",
        "src/test/java/org/hkrpg/server/gameplay/RogueWeaknessMiracleTest.java",
        "src/test/java/org/hkrpg/server/gameplay/RogueShieldMiracleTest.java",
        "src/test/java/org/hkrpg/server/gameplay/RogueBattleMiraclesTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = (
        "时空棱镜62/黑森林咕咕钟66分别按展示43/47、冻结[1]/[1,5]绑定实际633108/633109；"
        "资源分别增加战斗临时星魂等级、首波随机一人五回合受击倾向。11项回归含9项先失败，持久星魂/生命/秘技点不改；"
        "两项分别原生购买5项加战斗7项通过，准确buff/查询/防重/重连/H2保持，实际客户端临时等级上限/随机和倒计时未验收。"
        "朋克洛德精神68按展示49/效果68取得并冻结[1,1,3]；实际战斗633110携带#ADF_1=1/#ADF_2=3，覆盖源表十回合默认而不改资源。"
        "12项回归含6项先失败，原生购买5项/战斗7项通过，准确浮点参数/查询/余额137/秘技点5/重连/H2恢复已核对；客户端实际弱点/倒计时/UI未验收。"
        "天外重声大碟19实际绑定633105首波一次非DOT防护和三回合免疫资源脚本，10项回归含6项先失败；"
        "原生购买5项/实际战斗7项通过，查询、余额135/秘技点5、重连/H2恢复保持；实际客户端演算/UI未验收。"
        "羊皮卷8/机械钟71实际资源战斗绑定633102/633111，15项回归含6项先失败、Morphia两次恢复；"
        "两项分别原生购买5项及实际房间战斗7项KCP通过，准确buff/查询、防重、重连/H2钱包和秘技点保持。"
        "目录/首房/区域/300碎片为隔离夹具，客户端脚本实际演算/UI及奇物全集仍待验收/完成。"
        + REVIEWED[name]["gap"])


for name in ("GetRogueInfoCsReq", "EnterRogueMapRoomCsReq", "GetRogueShopMiracleInfoCsReq",
             "BuyRogueShopMiracleCsReq", "EnterRogueCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RoguePerpetualClockTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = (
        "永动咕咕钟67按展示48/效果67冻结0.05，仅实际相邻房间切换成功后扣费；先利息后扣费和扣费向下取整为项目规则，保存后同步钱包。"
        "9项回归含7项最初及1项暂停取得先失败；原生购买5项加转区战斗9项通过，100→95通知、重复拒绝查询、战后115/秘技点5/重连/H2保持。"
        "官方取整/叠加、写入中断和游戏UI未验收；目录/首房/区域/300碎片为夹具，奇物全集仍待完成。"
        + REVIEWED[name]["gap"])


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueBerserkMiracleTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = (
        "虫网59按展示40/效果59冻结[0.5,0.2]，实际资源战斗绑定633107；"
        "资源首波选择最高攻击的一名角色，增加50%攻击、回合损失当前HP20%且保留1点，状态销毁时转移随机一人。"
        "10项回归含6项先失败，1075项全量通过；原生购买5项/实际战斗7项通过，准确buff/查询、防重、同UID重连/H2保持。"
        "不在服务端提前改血或攻击；目录/首房/区域/300碎片是隔离夹具，实际客户端选人/回合/转移及UI尚未验收。"
        + REVIEWED[name]["gap"])

for name in ("SceneEnterStageCsReq", "PVEBattleResultCsReq", "GetMissionDataCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/MissionBattleKillsTest.java",
        "src/test/java/org/hkrpg/server/gameplay/MissionBattleScriptTest.java",
        "src/test/java/org/hkrpg/server/protocol/BattleResult46Test.java",
        "scripts/smoke-mission-battle-kills.py"])
    REVIEWED[name]["gap"] = (
        "KillMonsterInBattle有限单怪/单步胜利子集：开战冻结已接受目标、脚本字面量事件、实际世界等级关卡/名单与地点；"
        "保留原生MonsterBattleInfo字段20死亡来源，未知/逃跑/重复/名单超量不计击杀，新接取目标等下一战。"
        "20项新增回归含5项初始行为RED及2项混合脚本加载RED；1065项全量通过，真实305001事件/800204001名单KCP十项通过、重连/H2保持。"
        "接取/入口门槛和父任务终点为隔离夹具；完整演出、动态/嵌套任务、多目标/阶段、失败退出击杀和输入重演/真实游戏UI待完成。"
        + REVIEWED[name]["gap"])


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueBeautyRobeTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = ("纯美之袍14按展示12/效果14冻结[100,0.16,5]，实际房间战斗绑定633104。本场碎片余额通过ItemNumber传入，#ADF_1=100和#ADF_2=0.16覆盖能力默认[100,0.2]；重试保留原余额，后续战斗读取新余额。19项回归含12项先失败，1152项全量/8项Python/47项网络/两次Dispatch通过。原生购买5项和战斗8项通过，查询保持参数、错误/重复结算拒绝、重连/正常H2重启保留当前局和永久奇物，八份来源SHA不变。当前33项奇物效果子集；float32余额只支持0至16777215，其他余额不绑定该能力；第三参数5不猜寿命或价格。目录/首房/区域/300碎片为隔离夹具，实际客户端伤害/回合/UI仍待验收。 " + REVIEWED[name]["gap"])


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueDestructionCurioTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = ("湮灭烛剪58按展示39/效果58冻结0.03，实际宇宙房间成功击碎可破坏物后保存六段身份，同一物件重报/重建仅计一次。战斗绑定633112，数字哈希-924445233以等哈希ASCII兼容键传入冻结计数，#ADF_1=0.03覆盖资源默认0.3，原始变量拼写尚未恢复。24项回归含初始10项中的9项先失败；1176项全量、8项Python、47项网络、两次Dispatch通过。原生购买5项/战斗9项确认真实组73实例300009道具101017移除、重复攻击拒绝、计数1和当前查询稳定，11份来源SHA不变。两次实际Morphia重载保留计数/取得标识；原生重连/H2验证永久所有权与钱包，不等于实机伤害/计数UI或崩溃恢复验收。当前34项奇物效果子集，计数仅覆盖实际当前房间GroupWithContent中可破坏实体，不计战斗中/暂停/待选择/取得前击碎。 " + REVIEWED[name]["gap"])


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueRepairedCodeTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = ("六项修复后代码46/48/50/52/54/56已按真实展示/效果/参数取得，实际宇宙战斗绑定631014/16/18/20/22/24。资源分别处理击破恢复40能量、终结技按生命上限恢复30%、低于半血减伤25%、战技行动提前25%、击杀全队增伤25%、普攻恢复1战技点；Java不伪造战斗属性。38项回归含14项先失败，1214项全量/8项Python/47项网络/两次Dispatch通过；六次独立原生流程各购买5项/战斗7项及各三份购买和战斗恢复快照通过。上述1214项快照为历史记录；损坏代码及三胜替换已在1245项快照补齐，实际客户端事件执行和UI仍待验收。 " + REVIEWED[name]["gap"])


for name in ("SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq",
             "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq",
             "HandleRogueCommonPendingActionCsReq"):
    REVIEWED[name]["tests"].extend([
        "src/test/java/org/hkrpg/server/gameplay/RogueBrokenCodeTest.java",
        "scripts/smoke-rogue-shop.py"])
    REVIEWED[name]["gap"] = ("损坏代码45/47/49/51/53/55及修复后六项已成对接通：源副作用能力631013/15/17/19/21/23绑定实际宇宙战斗，同一取得UUID经三次不同资源战斗胜利后原子替换为46/48/50/52/54/56。前两次保存use=1/2、total=3，第三次使用本机客户端已核对的1957分支通知旧ID→新对象；配对参数冻结，重复/并发、迟取得、换UUID、暂停、退出和错误关卡不推进，旧记录不补造修复历史。31项新增回归含28项先失败，1245项全量、8项Python、47项网络和两次Dispatch通过；六次独立原生流程各三份购买/战后重连重启快照，24场实际资源战斗、12次进度和6次替换通知通过，第四场使用修复后能力。当前46项奇物效果子集；退出/败北修复计数语义、实机技能副作用/UI、完整宇宙/剧情/活动仍待完善或验收，状态保持部分。 " + REVIEWED[name]["gap"])


for name in ("GetDiceCombatSystemDataCsReq", "GetDiceCombatShopDataCsReq", "BuyShopGoodCsReq"):
    REVIEWED[name] = {"tests": ["src/test/java/org/hkrpg/server/protocol/DiceCombat46Test.java", "scripts/smoke-dice-combat.py"], "gap": '经典骰子活动新增系统/商店查询和真实购买：本机4.6命令9294/9279/9298与响应9266/9283/9280、取得9268已静态核对；使用现有语义别名发送，未改冻结协议。64角色、50骰子、3商店、56商品目录按固定资源关系校验；活动50073/模块5007301仅由运营时间窗开放，未解锁商品留给客户端资源补出。按真实Material库存扣费并保存基础角色/初始骰子/时间及购入收据；查询不送证书、满级角色或已完成关卡。24项新增回归含12项入口先失败及保存校验/发送名称各2项先失败，1269项全量、8项Python、47项网络及两次Dispatch通过。原生8项、同UID四份购买/重连/两次H2正常重启（末次关闭活动）快照验证1101商品264001原价264996×1、余额10→9、一次取得通知和防重，10份来源SHA不变；资金为隔离音律101奖励261001→264996×10夹具。此1269项历史快照尚未接入Offering；供奉12已在1300项快照接入。其他供奉、角色升级/换骰、完整PVE/PVP/V2/滚骰、一般剧情奖励取得及客户端UI仍待完成；中断收据保留待核对，正常重启不等于断电原子性。'}

for name in ("GetOfferingInfoCsReq", "SubmitOfferingItemCsReq", "TakeOfferingRewardCsReq"):
    REVIEWED[name] = {"tests": ["src/test/java/org/hkrpg/server/protocol/Offering46Test.java", "scripts/smoke-offering-12.py"], "gap": '经典骰子供奉12已接通原生6930/6937/6924及6936/6928/6933：查询从真实零进度开始，手动提交实际积分264999，前10级每级50、后15级每级100，总2000；冻结成本/奖励ID并保存逐笔扣款与整批领奖收据。真实25级奖励、防重、上限余款、OfferingLevel 12-5商品解锁和奖励角色264021基础牌组/时间已接入；服装227005作为道具入库，不自动装备。31项回归含15项不同失败先复现，1300项全量、8项Python、47项网络及Dispatch通过。11项原生检查、同UID四份提交/领奖/购买、重连与两次H2正常重启（末次关闭活动）快照通过；2050分实际消耗2000剩50，全部25档奖励一次，奖励角色及商店骰子取得通知各一次，13份来源SHA不变。积分/10枚货币来自隔离音律101奖励261001夹具，两个活动页面与音律剧情门槛明确放开；不证明官方积分来源/剧情门槛。当前仅供奉12的菜单entity0子集，其他供奉及非零地图装置拒绝，服装装备/角色升级换骰/完整PVE/PVP/V2/滚骰/一般剧情奖励取得/客户端UI仍待补齐；扣款与库存跨文档中断保留待核对，不自动补发或宣称断电原子性。'}

for name in ("GetDiceCombatSystemDataCsReq", "GetDiceCombatShopDataCsReq", "BuyShopGoodCsReq"):
    REVIEWED[name]["tests"].extend(["src/test/java/org/hkrpg/server/protocol/Offering46Test.java", "scripts/smoke-offering-12.py"])
    REVIEWED[name]["gap"] = '经典骰子供奉12已接通原生6930/6937/6924及6936/6928/6933：查询从真实零进度开始，手动提交实际积分264999，前10级每级50、后15级每级100，总2000；冻结成本/奖励ID并保存逐笔扣款与整批领奖收据。真实25级奖励、防重、上限余款、OfferingLevel 12-5商品解锁和奖励角色264021基础牌组/时间已接入；服装227005作为道具入库，不自动装备。31项回归含15项不同失败先复现，1300项全量、8项Python、47项网络及Dispatch通过。11项原生检查、同UID四份提交/领奖/购买、重连与两次H2正常重启（末次关闭活动）快照通过；2050分实际消耗2000剩50，全部25档奖励一次，奖励角色及商店骰子取得通知各一次，13份来源SHA不变。积分/10枚货币来自隔离音律101奖励261001夹具，两个活动页面与音律剧情门槛明确放开；不证明官方积分来源/剧情门槛。当前仅供奉12的菜单entity0子集，其他供奉及非零地图装置拒绝，服装装备/角色升级换骰/完整PVE/PVP/V2/滚骰/一般剧情奖励取得/客户端UI仍待补齐；扣款与库存跨文档中断保留待核对，不自动补发或宣称断电原子性。' + " " + REVIEWED[name]["gap"]

REVIEWED["SetPlayerOutfitCsReq"] = {'tests': ['src/test/java/org/hkrpg/server/protocol/PlayerOutfit46Test.java', 'scripts/smoke-offering-12.py'], 'gap': '装饰道具现已接入4.6装备请求330/响应324、PlayerSync tag267与初始角色tag4：只接受实际持有的Usable/PlayerOutfit、来源非默认外观及不相交槽位；全列表替换、默认外观通过移除列表项恢复、清空和同一集合幂等保存。保存成功后同步，不消耗装饰、不制造已解锁列表。16项回归中首批9项先失败，覆盖未持有/重复/槽位冲突/缺失或坏配置/宽整数与嵌套字段/并发/保存异常/旧空记录和两次真实Morphia恢复；1316项全量及14项真实KCP检查通过。同UID四份快照中供奉奖励227005未自动穿戴，未持有1006拒绝，1005装备/清空/再次装备均以角色查询核对，重连及两次正常H2重启保持[1005]，末次活动关闭仍可穿戴已持有装饰；14份来源SHA不变。330/324使用固定Honey4.6协议定义及本地合成请求，客户端实际发送命令号尚无完整调用链证明；本机静态确认Equip按SlotTypeList排除冲突、默认不加入列表及持有检查，CJLCPMDGIBO字段1使用uint列表。初始角色与同步的原生内容已验证，PlayerDetail仅验证核心容器及子消息映射；完整好友详情仍有旧字段映射缺口，实际游戏UI、剧情/性别/命途额外限制及断电原子性未验收。'}


for _dice_request in ("GetDiceCombatSystemDataCsReq", "GetDiceCombatShopDataCsReq", "BuyShopGoodCsReq"):
    REVIEWED[_dice_request]["tests"].append("src/test/java/org/hkrpg/server/protocol/DiceSlot46Test.java")
    REVIEWED[_dice_request]["gap"] = '角色骰组原生结构已修正：客户端CreateByAvatarProto从报文骰列表复制到DiceIDPerRare，DiceCountPerRare独立取资源；因此响应按稀有度返回一个骰ID，空槽保留0，不能重复展开为数量。NPIJFFFFKBK本机writer已核对字段1角色、2等级、3packed骰列表、4取得时间，Modify响应消费也只替换DiceIDPerRare。保存的既有数量展开列表与供奉取得收据保持不变，发送时按来源数量分组投影；同组不同ID/缺失或多余副本拒绝为损坏玩家记录，查询不重写运营存档。7项新回归全部先复现，1323项全量通过；供奉14项及商店8项真实KCP均通过，奖励264021返回[264101,264201,264301,264518]，购买264001返回[264101,264201,0,0]，各自同UID四份重连/正常H2重启快照一致。此前1300/1316和1269网络检查仅证明当时展开数据可传输，未证明客户端槽位语义，本项更正该输出。角色升级、换骰及完整PVE/PVP/V2/滚骰/实际UI仍待接入和验收。' + " 历史检查：" + REVIEWED[_dice_request]["gap"]


for _dice_mutation in ("DiceCombatUpgradeAvatarCsReq", "DiceCombatModifyAvatarDiceCsReq"):
    REVIEWED[_dice_mutation] = {"tests": ["src/test/java/org/hkrpg/server/protocol/DiceUpgrade46Test.java", "src/test/java/org/hkrpg/server/protocol/DiceModify46Test.java", "scripts/smoke-dice-progression.py"], "gap": '骰子角色升级与特殊骰替换已接入原生9292/9267、9286/9264。按来源Rare及本机DesignData恢复的真实数值映射，1升2扣264998×60，仅三星可再扣180升3，一/二星上限2；不会改骰组数量、取得时间或冻结供奉取得记录。扣费前保存独立收据，库存或完成保存中断保留待核对状态，重试/其他交易不再扣费；完成保存失败不发布等级。换骰以150093实际持有解锁，只允许所持Colorful骰或该角色来源默认骰，替换第四稀有度全部副本；不消耗权限物品/骰子，重复同选不保存，失败保存恢复内存旧骰组。17项升级和15项换骰回归，最初11+10项全部先失败；真实Morphia两次恢复、库存身份/不足/上限/窗口/战斗/并发/坏字段与坏收据已有检查。1355项全量通过；新增22项合成UDP/KCP、同UID四份快照验证供奉角色264021按60+180实际扣费、3级、库存1000→760、特殊骰264403/默认264518互换、普通重连及两次正常H2重启（末次关闭活动）保持。14份来源资源SHA不变，原生稀有度列表[264101,264201,264301,264403]。费用映射只对固定常量表SHA开放，未使用丢失重复键JSON的最后180值作统一价格；运行时不依赖游戏安装目录或参考项目二进制。PVE/PVP/V2、滚骰/技能演算、其他活动、全部剧情/宇宙及真实游戏UI仍待补齐；正常重启不证明断电原子性。'}


for _clock_entry in ("GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq", "GetRogueInfoCsReq", "EnterRogueMapRoomCsReq"):
    if _clock_entry in REVIEWED:
        REVIEWED[_clock_entry]["tests"].append("src/test/java/org/hkrpg/server/gameplay/RogueMechanicalClockAliasTest.java")
        REVIEWED[_clock_entry]["gap"] = '机械咕咕钟来源编号10→效果71已接入，保留原生持有编号10，不伪造拥有71。严格核对MiracleID10/Display52/Effect71与效果参数[2]，绑定既有来源633111的首波战技点消耗，不转为加点或扣秘技点；10与71任一已持有时拒绝另一项，损坏记录同时持有两者时均不作为有效效果。取得参数/唯一身份被冻结，进入真实房间战斗时只绑定一次，准备后取得不可追加入旧战斗；已有准备战斗保留冻结绑定。10项Java回归中8项先失败，真实Morphia恢复编号/参数/身份，1365项全量通过。原生商人5项与战斗7项、三份同UID战斗/重连/正常H2重启快照验证编号10、绑定633111、战后碎片121和秘技点不变；8份来源SHA不变。新增是来源别名支持，不另计新的奇物效果。初次探针在自有支持名单处提前拒绝，未发送战斗请求；名单已修正后重验通过。实际战技点数值演算由客户端资源执行，尚未运行实际客户端；全部宇宙变体、其余奇物9/11/16/18/21/57/63/64与全部剧情/活动仍有缺口。' + " 历史检查：" + REVIEWED[_clock_entry]["gap"]


_leader_request1378 = REVIEWED["ChangeLineupLeaderCsReq"]
_leader_request1378["tests"].extend(["src/test/java/org/hkrpg/server/gameplay/LineupLeaderRequestTest.java", "scripts/smoke-lineup-leader.py"])
_leader_request1378["gap"] = '换队长不再固定返回成功。原生708请求与768响应使用已冻结的slot字段8及响应6/9，严格拒绝重复字段、错误线类型、未知字段和宽uint32，阻止4294967296截断为槽0。实际角色/槽位校验、战斗中与剧情转场中拒绝、普通/剧情当前队长幂等已有13项回归；6项断言和1项未知字段异常在修复前复现，编译错误未计入红阶段。全量1378项Java通过。新增双角色剧情原生网络验证与原14项合并为21项检查，同UID三份首次/重连/正常H2重启快照均为队伍[1031224,1041209]、队长槽1、实际场景领队1209，26份来源SHA不变。此处修复切换和响应，202230605的CreateTrialPlayer/ChangeTeamLeader/LockTeamLeader完整脚本尚未接通，不能宣称队长锁定、完整剧情演出或真实客户端验收完成。' + " 历史记录：" + _leader_request1378["gap"]


_pve_request1400 = {"tests": ["src/test/java/org/hkrpg/server/protocol/DicePve46Test.java", "scripts/smoke-dice-pve.py"], "gap": '经典骰子PVE已接入9293/9271任务授权首胜子集：只处理真实已接取、当前场景匹配的NoPara/单步胜利目标，来源21条条件中2条符合该形状。关卡1001使用来源奖励8014100，实际发放264996×1和264998×100，冻结关卡/奖励/原目标/场景/opaque uint32后保存首胜账本，重复上报不重发、不消费后来接取的目标。系统查询只展示实际已发首胜，不送角色或骰子。失败、未识别flag=true、未知关卡、错误场景/目标、重复/宽整数/错误线字段明确拒绝；库存或保存中断保留待核对，并阻止新的购买/供奉/升级，不自动再扣费或发奖。新增22项Java回归，初始入口1项和跨交易2项断言先失败；1400项全量、8项Python、58类条件部分支持/106类未知审计通过。11项合成UDP/KCP和同UID三份首次/重连/正常H2重启快照核对实际钱包1/100、任务进度1、首胜[1001]与重复空奖励，8份来源SHA不变。任务接取及前置门槛放开、导航由10501/10501001移至隔离场景，原始完成条件与真实奖励不改；不是官方剧情验收。Equal/失败/退出目标、活动自由PVE/PVP/V2、骰面/技能/回合演算、全部剧情/宇宙/活动、真实客户端UI和断电原子性仍未完成或未验收。'}
REVIEWED["DiceCombatFinishPveStageCsReq"] = _pve_request1400
REVIEWED["GetDiceCombatSystemDataCsReq"]["tests"].extend(_pve_request1400["tests"])
REVIEWED["GetDiceCombatSystemDataCsReq"]["gap"] = _pve_request1400["gap"] + " 历史记录：" + REVIEWED["GetDiceCombatSystemDataCsReq"]["gap"]


_battle_custom1424 = {"tests": ["src/test/java/org/hkrpg/server/gameplay/MissionBattleCustomValueTest.java", "src/test/java/org/hkrpg/server/protocol/BattleCustomValue46Test.java", "scripts/smoke-battle-custom.py"], "gap": '剧情战斗自定义值新增BattleWinWithCustomValue有限Equal/单步/显式事件及0/1子集：按来源PlaneEvent映射20241054到各世界等级202410540至546，冻结已接受目标、父任务、事件脚本、世界等级和原场景；读取BattleStatistics字段13的真实float，缺键不默认0，分数/邻近值不截断为整数。202200506与507分别匹配_PlayerWin=1和0，一次结算只推进匹配分支；配套202200502的StageWin从同一冻结事件身份完成，不按数字字符串前缀授权。重复键/键值重复字段/错误线类型/非有限值/非法UTF8/超长键拒绝，错误结算保留当前战斗；源未识别脚本、动作、取消或新接取目标仍不完成。24项新回归含两项初始断言和两项事件映射断言先失败；1424项全量及8项Python通过。13项合成UDP/KCP、两个UID各三份首次/重连/正常H2重启快照核对两分支、事件目标、后继对话和主任务仍DOING，15份来源SHA不变。原生夹具将源IsInRaid=true明确放开为普通场景，并迁移40235/40235001到10101/10101001、放开入口；原始条件与七份脚本保持，未播放官方演出，也未证明TrialOrPlayer副本40235或战斗输入重演。源副本入口/试用或正式队伍限制、其他参数变体、完整剧情/宇宙/活动、实际客户端及断电原子性继续待完成或验收。'}
for _battle_entry1424 in ("PVEBattleResultCsReq", "SceneEnterStageCsReq", "GetCurBattleInfoCsReq"):
    if _battle_entry1424 in REVIEWED:
        REVIEWED[_battle_entry1424]["tests"].extend(_battle_custom1424["tests"])
        REVIEWED[_battle_entry1424]["gap"] = _battle_custom1424["gap"] + " 历史记录：" + REVIEWED[_battle_entry1424]["gap"]
    else:
        REVIEWED[_battle_entry1424] = {"tests": list(_battle_custom1424["tests"]), "gap": _battle_custom1424["gap"]}


_raid_hybrid1470 = {"tests": ["src/test/java/org/hkrpg/server/protocol/RaidHybridFormal46Test.java", "src/test/java/org/hkrpg/server/protocol/FormalLineup46Test.java", "src/test/java/org/hkrpg/server/protocol/PlayerBoardSync46Test.java", "scripts/smoke-battle-custom.py", "src/test/java/org/hkrpg/server/ConfigurationStartupTest.java", "src/test/java/org/hkrpg/server/ConfigurationFilesTest.java"], "gap": '新增TrialOrPlayer剧情副本的正式持有路径：40235保留IsInRaid、原始40235001入口、IncludeAvatar限制3要求椒丘1209、七个任务条件/脚本、20241054事件及2023101通关返回。按实际持有对象和当前正式队伍核对，不把1011209声明当成试用授予。冻结队伍规则随副本保存，损坏/旧缺计划/规则变化拒绝续进；失去必需队员阻止目标但允许退出。剧情自定义值战斗另外冻结具体副本记录，替换记录或离开不能消费旧战斗。修复正式队伍替换704/719，校验实际成员、槽位、队长和跨玩法忙碌，重复请求不重写；仅当前普通队伍，其他编辑变体仍待补。4.6正式角色枚举为3、試用为1，与旧内部正式1不同，副本和宇宙选择改按语义名称；头像解锁/签名/当前头像框同步接PlayerSync的781容器，未知头像框解锁列表/时效保持未支持。启动保留已有配置原始字节，包括未知键和CRLF；缺失配置以CREATE_NEW安装，显式保存先校验并序列化，再用完整临时文件替换；9项新增配置回归与原生三次启动/每次探针配置SHA验证通过。55项新增回归及既有宇宙/跑酷夹具校正，红报告29项真实失败；1479项全量、真实Morphia重载、23项原生网络、两个UID共12份进行中及完成后首次/重连/正常H2重启快照通过，24份來源SHA不变。父任务2022004准备、起始普通地图入口门槛和音律首奖授予1209仍为明确隔离夹具；未播放官方演出、未重演战斗输入，也未完成試用路径、全部队伍限制、副本恢复属性、完整父任务、宇宙/活动、客户端或断电原子性验收。'}
for _raid_entry1470 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "ReplaceLineupCsReq", "PVEBattleResultCsReq", "SceneEnterStageCsReq", "MusicRhythmFinishLevelCsReq"):
    if _raid_entry1470 in REVIEWED:
        REVIEWED[_raid_entry1470]["tests"].extend(_raid_hybrid1470["tests"])
        REVIEWED[_raid_entry1470]["gap"] = _raid_hybrid1470["gap"] + " 历史记录：" + REVIEWED[_raid_entry1470]["gap"]
    else:
        REVIEWED[_raid_entry1470] = {"tests": list(_raid_hybrid1470["tests"]), "gap": _raid_hybrid1470["gap"]}


_raid_trial1500 = {"tests": ["src/test/java/org/hkrpg/server/protocol/RaidHybridTrial46Test.java", "scripts/smoke-battle-custom.py"], "gap": '新增TrialOrPlayer的声明试用队伍路径：40235选择1011209以演员1209满足IncludeAvatar3，保留原始副本入口、IsInRaid、七目标、20241054事件和2023101通关返回。试用身份仅发布到当前副本临时演员，普通正式队伍和持有列表不授予角色；失败进入、暂停或通关退出按对象身份清理。冻结当次世界等级、核心角色配置、行迹模板/已选节点、队伍、生命/能量和秘技点，重入及玩家文档重载不按新等级/新模板扩展旧角色；损坏存档返回实际原位置并保留任务历史，身份占用和非持有普通队伍不能绕过。21项新回归、两项真实先失败，1500项全量、8项Python、真实Morphia玩家文档、26项试用及23项正式网络检查通过；每条路径两个UID各12份快照，试用30份源SHA及启动配置原字节不变。试用战斗核对40级/光锥23012的40级/非空源行迹及类型1，正式列表在全部快照仅原8001、通关后普通队伍恢复。只支持全部成员均为声明试用的队伍；正式与试用混编、TrialOnly、其他限制、遗器属性/覆盖属性/恢复规则、完整父剧情及官方战斗输入/演出/实际客户端/断电原子性仍未完成或验收。'}
for _raid_trial_entry1500 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "GetAvatarDataCsReq", "PVEBattleResultCsReq", "SceneEnterStageCsReq"):
    if _raid_trial_entry1500 in REVIEWED:
        REVIEWED[_raid_trial_entry1500]["tests"].extend(_raid_trial1500["tests"])
        REVIEWED[_raid_trial_entry1500]["gap"] = _raid_trial1500["gap"] + " 历史记录：" + REVIEWED[_raid_trial_entry1500]["gap"]
    else:
        REVIEWED[_raid_trial_entry1500] = {"tests": list(_raid_trial1500["tests"]), "gap": _raid_trial1500["gap"]}


_raid_bounds1510 = '试用副本角色增加实际突破表边界：对冻结及新建角色核对声明世界等级、角色突破/等级/星魂、光锥突破/等级/叠影，按AvatarPromotionConfig与EquipmentPromotionConfig和对应演员/装备资源上限检查，不接受靠已有行迹或具名身份恢复非法数值。10项真实先失败回归修复后31项试用专项和1510项全量通过；同一类产物的26项网络、两个UID12份快照、32份源SHA和已有配置原字节复测通过。仅全部成员均为声明试用的队伍，正式/试用混编、其他类型与限制、遗器/覆盖属性/恢复规则和官方客户端输入/演出仍待补或验收。'
for _raid_bound_entry1510 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "PVEBattleResultCsReq", "SceneEnterStageCsReq"):
    if _raid_bound_entry1510 in REVIEWED:
        REVIEWED[_raid_bound_entry1510]["gap"] = _raid_bounds1510 + " 历史记录：" + REVIEWED[_raid_bound_entry1510]["gap"]


_raid_mixed1522 = 'TrialOrPlayer新增正式持有与声明试用混编：按请求顺序形成副本独立队伍，正式成员生命/能量复制到副本局部状态，试用配置/行迹仍冻结；不授予试用身份，不改写普通队伍生命或成员。重入核对每项正式/试用类型及顺序，重复角色/演员、未持有身份和活动期正式成员丢失不能推进，退出仍清理临时试用并保留历史。12项新回归含1项先失败，43项试用专项、1522项全量通过；真实40235以8001与1011209混编的27项网络检查、两个UID共12份进行中/通关快照及正常H2重启、32份源SHA与已有配置字节通过。全试用26项、全正式23项同类产物复测。TrialOnly、其他队伍限制/锁定队长、遗器/覆盖属性/恢复规则、完整父剧情和官方游戏UI/战斗输入/演出及断电原子性仍未完成或验收。'
for _raid_mixed_entry1522 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "PVEBattleResultCsReq", "SceneEnterStageCsReq"):
    if _raid_mixed_entry1522 in REVIEWED:
        REVIEWED[_raid_mixed_entry1522]["gap"] = _raid_mixed1522 + " 历史记录：" + REVIEWED[_raid_mixed_entry1522]["gap"]


_raid_only1544 = 'TrialOnly仅试用副本增加按声明自动组队：空选择请求按原顺序建立独立队伍，MultiplePathAvatarConfig性别筛选仅保留匹配主角，拒绝正式替代、错性别、重复或声明外成员，缺失资源不能静默缩队。ForbidReplace14/34只支持冻结试用队伍；锁定队长按演员或明确BaseAvatarID定位，缺失/歧义拒绝，单槽换队长和直接队伍设置均不能越过锁定。队长及换人规则随副本冻结，TrialOnly记录性别，损坏/变化存档返回真实普通位置并保留历史。新增22项回归，65项试用专项与1544项全量通过；3项新先失败及1项情绪扣款连带失败修复，累计46项真实失败。实际40222001/40531/40452三副本、3UID共21快照、15项网络检查核对原始入口/队伍/未完成起始目标、重连/正常H2重启/暂停重入及正式持有列表不授予；混编27项、全正式23项同类产物复测。情绪付费锁定图仍明确只允许Player队伍，不能因开放TrialOnly提前扣除未接通入口的道具。仍为有限队伍规则：其他限制与变体、正式队伍锁定/禁止换人路径、全部仅试用副本、遗器/覆盖属性/恢复规则、完整剧情演出/父任务/小游戏/宇宙及实际游戏UI和断电原子性未完成或验收。'
for _raid_only_entry1544 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_only_entry1544 in REVIEWED:
        REVIEWED[_raid_only_entry1544]["gap"] = _raid_only1544 + " 历史记录：" + REVIEWED[_raid_only_entry1544]["gap"]
        REVIEWED[_raid_only_entry1544]["tests"] = list(dict.fromkeys(REVIEWED[_raid_only_entry1544]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidTrialOnly46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_formal1557 = 'TrialOrPlayer副本的纯正式选择现在支持资源声明的锁定队长及ForbidReplace：实际持有成员可与普通编队不同，冻结独立队伍和生命/能量，队长按演员定位；没有试用选择就不创建临时演员或授予角色。暂停重入读取保存队伍，正式成员缺失或对象被替换不能推进，历史保留且可返回普通位置。新增13项回归、4项真实先失败，专项103项与全量1557项通过，累计50项真实失败。原始4012301以明确隔离的音律奖励准备真实持有1111，副本/队伍规则/全部源目标脚本位置保持原样；15项网络检查、1UID7快照验证锁定第二槽、重连、正常H2重启、暂停重入、原普通队伍返回和正式持有列表。三仅试用副本15项21快照、混编战斗27项12快照、通用47项和Dispatch2项使用同类产物复测。其他等级/人数/多命途限制、Player队伍的锁定规则、遗器/覆盖属性/恢复变体、完整剧情/小游戏/宇宙和实际游戏UI仍未完成或验收。'
for _raid_formal_entry1557 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_formal_entry1557 in REVIEWED:
        REVIEWED[_raid_formal_entry1557]["gap"] = _raid_formal1557 + " 历史记录：" + REVIEWED[_raid_formal_entry1557]["gap"]
        REVIEWED[_raid_formal_entry1557]["tests"] = list(dict.fromkeys(REVIEWED[_raid_formal_entry1557]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidHybridTrial46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_threshold1595 = '4.6原始TeamLevel2和TeamAvatarMinCount5按本机静态证据接通：逐个成员的最低等级至少30，非空实际成员至少4，不能用高等级队长/平均等级、重复选择、未持有角色或空槽凑过条件。Player可使用IncludeAvatar/等级/人数的有限规则，仍拒绝锁定队长/ForbidReplace等未接通路径；正式及混编/仅试用路径在进入、继续推进和重载时检查当前成员及冻结规则。规则ID/阈值持久化，降低资源阈值也不能改写已有副本，损坏存档或正式成员降级返回普通位置并保留历史。新增38项回归、3项真实先失败，141项专项及1595项全量通过，累计53项真实先失败。首次全量发现共用检查误加演员ID约束引发24项普通副本失败，已修复并全量复测；这些不计入新增规则先失败数量。隔离四人试用声明追加原始规则2/5，15项网络检查、1UID7快照通过重连/正常H2重启/暂停重入；原始副本与目标脚本位置保留，队伍声明明确改过，不能宣称原始副本1/43211完整接通。正式锁定队长15项7快照、混编战斗27项12快照、通用47项与Dispatch2项使用同类产物复测。多命途限制、Player锁定/禁止换人、全部副本变体、完整剧情演出/活动小游戏/宇宙及实际客户端验收仍未完成。'
for _raid_threshold_entry1595 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_threshold_entry1595 in REVIEWED:
        REVIEWED[_raid_threshold_entry1595]["gap"] = _raid_threshold1595 + " 历史记录：" + REVIEWED[_raid_threshold_entry1595]["gap"]
        REVIEWED[_raid_threshold_entry1595]["tests"] = list(dict.fromkeys(REVIEWED[_raid_threshold_entry1595]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidTeamThreshold46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_player1612 = '原始40412的Player队伍和ForbidReplace34接入独立正式队伍：空请求使用真实普通成员，正式选择可与普通编队不同，队伍及生命/能量冻结，不生成临时演员或授予角色。Player锁定队长另用明确合成声明验证；源表没有Player锁定队长配置，不能当作实际原剧情验收。暂停重入恢复保存队伍和生命，缺失成员/规则变化不能推进或错误恢复，历史保留且返回原普通位置。新增16项Player队伍回归与1项付费边界回归，全量1612项通过；4项Player先失败和1项实际提前扣款失败已修复，累计58项真实先失败。付费情绪锁图仍只接受普通Player队伍，独立冻结队伍未接通付费图时不能提前消耗道具。实际40412副本/队伍规则/全部目标脚本位置保持原样，15项网络检查、1UID7快照通过未持有选择拒绝、重连、正常H2重启、暂停重入和普通队伍返回；正式持有列表只有8001，首目标始终未完成。等级/人数隔离队伍15项7快照、通用47项和Dispatch2项同类产物复测；混编27项及TrialOrPlayer正式队长15项沿用1595基线，三个仅试用副本15项21快照沿用1557基线。多命途/角色身份变体、全部副本、遗器/属性/恢复变体、完整剧情/活动小游戏/宇宙及真实客户端验收仍未完成。'
for _raid_player_entry1612 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_player_entry1612 in REVIEWED:
        REVIEWED[_raid_player_entry1612]["gap"] = _raid_player1612 + " 历史记录：" + REVIEWED[_raid_player_entry1612]["gap"]
        REVIEWED[_raid_player_entry1612]["tests"] = list(dict.fromkeys(REVIEWED[_raid_player_entry1612]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidHybridTrial46Test.java", "src/test/java/org/hkrpg/server/protocol/HeartDialLock46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_multipath1650 = '指定命途条件IncludeMultiPathAvatar接入实际角色配置：13/32/48分别要求主角8005/8003/8009，女性按明确配对减一比较；15要求三月七1224（基础1001）。正式角色必须使用实际生效的命途对象，试用使用演员配置而非SpecialAvatar编号；仅持有基础角色或其他命途不能通过。非主角资源缺省性别字段可用，主角仍须核对玩家性别和配对关系。条件随副本保存，活动/暂停JSON及Morphia恢复、存档或源规则变化拒绝、正式切换命途后停止推进均通过。新增38项回归，全量1650项通过；用修正后的真实基础角色夹具复现5项断言先失败，累计63项真实先失败。实际41212原始副本/规则/全部目标脚本位置不改，15项网络检查、1UID7快照通过三月七试用入口、基础队长1001定位、重连、正常H2重启、暂停重入和普通队伍返回，正式持有列表只有8001，首目标202210201始终未完成。40412 Player冻结队伍15项7快照、通用47项和Dispatch2项在1650产物复测。43308无EnterType仍未接通实际入口，32/48组合为明确隔离；其余角色身份变体、全部副本、遗器/属性/恢复变体、完整剧情/活动小游戏/宇宙和真实客户端验收仍未完成。'
for _raid_path_entry1650 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_path_entry1650 in REVIEWED:
        REVIEWED[_raid_path_entry1650]["gap"] = _raid_multipath1650 + " 历史记录：" + REVIEWED[_raid_path_entry1650]["gap"]
        REVIEWED[_raid_path_entry1650]["tests"] = list(dict.fromkeys(REVIEWED[_raid_path_entry1650]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidMultiPath46Test.java", "scripts/smoke-raid-trial-only.py"]))


_trial_gender1668 = '副本与独立剧情试用队伍新增持续性别核对：首次进入、已缓存角色、活动存档恢复和暂停重入使用同一资源性别关系；当前玩家性别或主角资源关系不再匹配时，不能继续使用/恢复错性别演员。三月七等无性别限制角色不会冻结无关的玩家性别。无效剧情队伍仍可明确返回普通场景，保留未完成分支及普通队伍；恢复无效剧情记录按既有非法存档边界拒绝，不自动完成或补造演员。新增副本10项/剧情8项回归，8项副本及6项剧情断言先失败，全量1668项通过，累计77项真实先失败。错性别及资源改变场景为明确隔离回归。当前1668产物复测原始41212与40412各15项7快照、三个TrialOnly副本15项21快照、2022304剧情14项、通用47项和Dispatch2项；网络采用有效原角色，仅证明进出/通知/恢复保持。原始41212及40412起始目标仍未完成，2022304前置/来源场景仍为既有隔离夹具。全部副本、角色身份变体、遗器/覆盖属性/恢复变体、完整剧情/日常/活动小游戏/宇宙、真实客户端及断电事务恢复仍未完成或验收。'
for _trial_gender_entry1668 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq", "ChangeStoryLineCsReq"):
    if _trial_gender_entry1668 in REVIEWED:
        REVIEWED[_trial_gender_entry1668]["gap"] = _trial_gender1668 + " 历史记录：" + REVIEWED[_trial_gender_entry1668]["gap"]
        REVIEWED[_trial_gender_entry1668]["tests"] = list(dict.fromkeys(REVIEWED[_trial_gender_entry1668]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidTrialGender46Test.java", "src/test/java/org/hkrpg/server/gameplay/StoryTrialGenderTest.java", "scripts/smoke-raid-trial-only.py", "scripts/smoke-story-trial.py"]))


_raid_role1679 = 'IncludeAvatar基础角色条件改按已核对的RoleID身份比较：多命途试用演员由明确MultiplePathAvatarConfig.BaseAvatarID取得基础身份，主角缺失该关系不能凭编号补造；正式角色仍使用真实持有的基础身份。IncludeMultiPathAvatar继续比较当前实际命途，两个条件不能混用。演员的场景/战斗原编号、试用类型及SpecialAvatar编号保持不变，不授予基础角色。新增11项回归，7项断言先失败，全量1679项通过，累计84项真实先失败；男女主角试用/正式、活动和暂停恢复、缺失关系及把8005当作基础身份的合成反例已验证。原始条件1仅在源Player副本1/40525使用；单元组合与网络40452增加条件1均明确隔离，不能称为原副本队伍验收。15项网络检查、1UID7快照验证1028007按基础8001通过、重连/正常H2重启/暂停重入及普通队伍返回，正式持有列表仍只有8001，原起始目标不完成。原始41212另15项7快照、通用47项和Dispatch2项在1679产物复测；Player/三个TrialOnly/独立剧情沿用1668基线。全部副本、其余身份变体、遗器/覆盖属性/恢复变体、完整剧情/日常/宇宙/活动小游戏、真实客户端和断电事务恢复仍未完成或验收。'
for _raid_role_entry1679 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq", "ChangeLineupLeaderCsReq"):
    if _raid_role_entry1679 in REVIEWED:
        REVIEWED[_raid_role_entry1679]["gap"] = _raid_role1679 + " 历史记录：" + REVIEWED[_raid_role_entry1679]["gap"]
        REVIEWED[_raid_role_entry1679]["tests"] = list(dict.fromkeys(REVIEWED[_raid_role_entry1679]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidRoleIdentity46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_candidates1709 = 'TrialOrPlayer候选目录与实际出战人数分开校验：来源40473/40475/40546分别有12/13/7个候选，允许明确选取最多四名，原始IncludeAvatar47/49仍约束实际队伍；五人、重复、声明外试用或缺少必需角色不能进入。整个候选目录及选择队伍随副本冻结，活动/暂停恢复保留四名成员、原始行迹/强化和独立生命，不增加正式持有角色。新增30项回归、25项修正后的真实断言先失败，全量1709项通过，累计109项真实先失败。SkipJoinLineup原先在Raid模型被丢弃，现完整读取且未核对的true入口明确拒绝；三个原始大候选副本都有此标志，并另有Performance/奖励/父任务差异，不能称为其原始入口已接通。网络40452仅隔离套用40475的TeamType/TrialAvatarList/TeamLimitIDList三字段，四名选择含末尾候选1031408；15项检查、1UID7快照验证重连、正常H2重启、暂停重入、返回原普通队伍，首目标保持未完成且正式持有列表仍只有8001。三个原始TrialOnly副本15项21快照、通用47项与Dispatch2项在1709产物复测。遗器/覆盖属性/不自动入队演出、全部原始副本、完整战斗/剧情/日常/宇宙/活动小游戏、真实客户端与断电事务恢复仍未完成或验收。'
for _raid_candidates_entry1709 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq"):
    if _raid_candidates_entry1709 in REVIEWED:
        REVIEWED[_raid_candidates_entry1709]["gap"] = _raid_candidates1709 + " 历史记录：" + REVIEWED[_raid_candidates_entry1709]["gap"]
        REVIEWED[_raid_candidates_entry1709]["tests"] = list(dict.fromkeys(REVIEWED[_raid_candidates_entry1709]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidTrialCandidates46Test.java", "scripts/smoke-raid-trial-only.py"]))


_raid_uniqueness1724 = '副本独立队伍按基础角色身份去重：正式基础角色和试用当前命途不再因编号不同重复占槽，两个试用主角命途也不能重复同一基础角色；缺失基础关系不能补造。进入、活动队伍检查、存档重建和暂停重入均执行该规则，演员实际编号及战斗SpecialAvatar编号保持不变。新增15项回归，其中11项进入/存档断言及2项已有队伍缓存断言先失败，全量1724项通过，累计122项真实先失败；男女主角同/不同命途、无锁定队长的三月七组合、损坏活动/暂停存档、资源关系变成重复及不同基础角色正例已验证。初次暂停反例因空请求被已有选择校验拒绝，改为提交保存的完整重复队伍后重新复现，不把初次两项通过算作去重证明。网络40452仅隔离套用40546的TeamType/TrialAvatarList/TeamLimitIDList，原始规则49保留：必需1510＋正式8001＋试用1048007被拒绝，合法两人队伍不授予角色；15项检查、1UID7快照通过重连、正常H2重启、暂停重入及返回普通队伍，首目标未完成。13候选四人队伍15项7快照、原始41212命途队伍15项7快照、通用47项与Dispatch2项在1724产物复测。原始40546的SkipJoinLineup等入口仍未接通，完整战斗属性/遗器/覆盖属性、全部剧情/日常/宇宙/活动小游戏、实际客户端与断电事务恢复仍未完成或验收。'
for _raid_unique_entry1724 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq"):
    if _raid_unique_entry1724 in REVIEWED:
        REVIEWED[_raid_unique_entry1724]["gap"] = _raid_uniqueness1724 + " 历史记录：" + REVIEWED[_raid_unique_entry1724]["gap"]
        REVIEWED[_raid_unique_entry1724]["tests"] = list(dict.fromkeys(REVIEWED[_raid_unique_entry1724]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidRoleUniqueness46Test.java", "scripts/smoke-raid-trial-only.py"]))


_trial_properties1741 = '试用角色冻结配置补回专属遗器、覆盖属性、能力和提示等23个遗漏字段，完整保留本机SpecialAvatarRow的38个已知字段；声明仍为副本局部数据，不授予正式角色或普通遗器。17项回归全部在旧模型先失败后通过，13个来源40475候选逐字段保存，活动/暂停JSON及真实Morphia玩家文档恢复一致；两条非空覆盖属性/测试能力为明确合成存储正例，来源覆盖数组全为空，不声称执行官方效果。全量1741项通过，累计139项真实先失败（本轮17项包含原先15项，不重复计算）。有界客户端函数核对战斗类型/编号/世界等级标签8/16/96及专属套装、主副属性总值累加；OverrideProperty使用AbilityProperty而遗器总值使用AvatarPropertyType，不能混用或补造普通词条。世界等级查询先读0再按行内标志决定是否读传入等级，未凭固定行0误改战斗消息。13候选四人队伍15项7快照、三原始仅试用副本15项21快照、通用47项和Dispatch2项在1741产物复测；重连/正常H2重启/暂停重入及正式持有列表保持，首目标不完成。原始大候选SkipJoinLineup/Performance入口、完整属性公式/能力/覆盖效果、全部战斗剧情日常宇宙活动小游戏及实际客户端、断电事务恢复仍未完成或验收。'
for _trial_properties_entry1741 in ("StartRaidCsReq", "LeaveRaidCsReq", "GetRaidInfoCsReq", "GetCurLineupDataCsReq"):
    if _trial_properties_entry1741 in REVIEWED:
        REVIEWED[_trial_properties_entry1741]["gap"] = _trial_properties1741 + " 历史记录：" + REVIEWED[_trial_properties_entry1741]["gap"]
        REVIEWED[_trial_properties_entry1741]["tests"] = list(dict.fromkeys(REVIEWED[_trial_properties_entry1741]["tests"] + ["src/test/java/org/hkrpg/server/protocol/RaidTrialProperties46Test.java", "docs/trial-properties-4.6.md", "docs/client46-trial-properties-evidence.json", "scripts/smoke-raid-trial-only.py"]))


_trial_maze_lock1749 = '试用配置声明的LockMazeSkill接入秘技施放前校验：原生1460和兼容入口在扣秘技点、执行效果、推进原生日常统计和尝试战斗之前拒绝锁定秘技，普通攻击及未锁定秘技继续按既有流程；恢复后的冻结剧情配置不跟随新资源解除锁定。新增8项隔离故事回归含4项真实先失败，全量1749项通过，累计143项真实先失败；兼容及原生路径均验证扣点/效果/攻击边界，未把攻击服务测试边界当作战斗完成。来源SpecialAvatar有37行LockMazeSkill=true，38字段类型证据已确认布尔声明；客户端直接消费该标志的函数仍未定位，这是按资源声明实行的服务端规则，不能声称37条官方剧情或游戏客户端已接通。实际2022304剧情14项/同UID3快照、通用47项、Dispatch2项在1749产物复测，来源26份SHA不变，剧情前置/入口仍为既有隔离；这些网络检查未施放新锁定秘技，正向拒绝证据来自原生消息处理回归。完整属性/能力/演出、全部战斗剧情日常宇宙活动小游戏及客户端、断电事务恢复仍未完成或验收。'
for _trial_maze_entry1749 in ("SceneCastSkillCsReq",):
    if _trial_maze_entry1749 in REVIEWED:
        REVIEWED[_trial_maze_entry1749]["gap"] = _trial_maze_lock1749 + " 历史记录：" + REVIEWED[_trial_maze_entry1749]["gap"]
        REVIEWED[_trial_maze_entry1749]["tests"] = list(dict.fromkeys(REVIEWED[_trial_maze_entry1749]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/TrialMazeSkillLockTest.java", "docs/trial-properties-4.6.md"]))


_scene_caster1763 = '场景施法者按请求实体解析当前真实队员：原生4.6入口、兼容入口、命中与攻击回调、元素入战效果均改用实际施法者；秘技锁定及1308免扣点不再落到队长身上。元素入战效果的拥有者和目标槽位使用实际施法者位置，保留忽略弱点修饰符。新增14项回归在1749快照的三个旧处理器下全部断言先失败，新的场景解析方法保留以编译现有夹具；全量1763项通过，累计157项真实先失败，先前8项施法者断言属于这14项，未重复计数。入战效果回归使用空怪物波次的合成关卡，只证明效果归属与4.6 BattleBuff字段。2022304双试用角色网络25项/同UID3快照验证非队长秘技拒绝、无目标普通攻击接受、点数和队长不变及重连/H2正常重启；测试副本只对1031224/1041209的14行世界等级变体声明LockMazeSkill，两个编号的原始14行均为false，不能算来源37条锁定剧情验收。剧情前置/入口仍为隔离，原始26份来源SHA不变，通用47项与Dispatch2项通过。客户端直接消费锁定标志的函数、正式游戏UI、完整战斗输入/属性能力演出、全部剧情日常宇宙活动小游戏及断电事务恢复仍未完成或验收。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _scene_caster1763 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SceneSkillCasterTest.java", "scripts/smoke-lineup-leader.py", "src/main/java/org/hkrpg/server/protocol/LoopbackStoryTrialProbe.java"]))


_maze_lifetime1775 = '探索Buff读取明确的固定LifeTime，不再统一写死20秒：保留小数秒精度，-1声明映射不定时过期，角色与怪物到期时间使用long毫秒避免先做int乘法溢出；保留既有int方法/构造器和整数时长getter，实际执行使用精确秒数。12项回归含8项真实先失败，全量1775项通过，累计165项真实先失败。三个逐字来源任务分别是黄泉130801/1.1秒、白露121101/-1、昔涟141501/30秒，只提取明确Caster任务构造隔离单动作能力；原始三个文件SHA不变，未将片段测试当作完整能力、目标/条件/变体或官方角色验收。固定声明共有53项（其中39项-1、3项1.1、10项1、1项30），并非全部53项触发链已接通。缺失时长与未求值动态表达式仍保留既有20秒回退，未把旧DynamicFloat的15当作求值结果。场景Buff正值发送沿用原有毫秒字段语义，永久标志为-1；4.6客户端计时单位和显示仍待直接消费函数核验。Buff消耗/移除脚本、完整动作条件与动态参数、全部战斗剧情日常宇宙活动及游戏UI继续待完成/验收。双角色施法者25项、通用47项与Dispatch2项在1775产物复测通过；这些网络检查未专门施放三个新时长来源效果，正向时长证据来自Java动作执行与实际到期值。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _maze_lifetime1775 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/emu/lunarcore/data/MazeBuffLifetimeTest.java", "src/test/resources/fixtures/maze-buff-lifetime-4.6.json"]))


_buff_units1780 = '探索Buff发送单位按本机4.6客户端直接读取路径修正：BuffInfo字段4/标签37是float秒，字段8/标签64仍是uint64毫秒创建时间；内部到期计算和原有duration getter继续使用毫秒，-1永久标志保持。客户端33942/BJLOENPDAHE消息的字段常量、Float/UInt64类型与MergeFrom 232121匹配；573153在0xc311d80读取同一参数类型，将创建时间和当前毫秒时间除以1000后从LifeTime减去经过秒数；负值返回-1。AdventureAbilityComponent 526447调用该剩余时间函数，626894/get_NowMsTimeStamp提供当前毫秒。四段有界函数及五项参数类型证据、客户端/三个资源SHA已记录到docs/client46-buff-lifetime-evidence.json，仅读取，未执行或修改客户端。新增5项回归在1775产物代码下有4项真实先失败，已有永久项作为对照；时长测试总17项，全量1780项通过，累计169项真实先失败。验证1.1/30/5秒的4.6转换字段、毫秒创建时间及兼容5秒；此前发送1100/30000/5000的问题已修复。双角色25项、通用47项和Dispatch2项复测通过，但这些网络流程未专门发送三种新时长Buff通知；实际游戏计时显示、动作条件/目标/变体、动态表达式、Buff消耗/移除、全部剧情日常宇宙活动及客户端完整验收仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _buff_units1780 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/emu/lunarcore/data/MazeBuffLifetimeTest.java", "docs/client46-buff-lifetime-evidence.json"]))


_buff_notify1785 = '探索Buff完整通知转换修复：旧entity_buff_info_list映射到4.6 entity_buff_change_list，新增分支add_buff_info映射到buff_change_info并明确ADD_MAZEBUFF原因1；移除分支保留remove_buff_id及默认原因0。此前转换层会拒绝该未匹配字段，阻断新增/移除通知。五项完整包回归验证1.1秒、永久-1、新增/移除oneof、批量混合及旧版本帧保持，四项真实先失败；全量1785项通过，累计173项真实先失败。Java包经实际发送封包和转换解码，未专门在KCP发送新增/移除通知，未验收游戏UI；双角色25项、通用47项、Dispatch2项现有网络回归通过。参考March7thHoney提交fe00bdbda3c28ca33d01f171566aa35b5ab95083的通知字段含义，仅实现自有转换规则，未导入运行时代码，未改冻结协议。动作脚本运行时移除、动态条件/表达式、Buff消耗和全部剧情日常宇宙活动仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _buff_notify1785 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/protocol/SceneBuffNotification46Test.java"]))


_buff_remove1800 = '探索施法者Buff运行时移除：明确的RPG.GameCore.TargetAlias/Caster任务保留为顺序动作，在已接通的施法、命中、攻击回调清除已有Buff，重复/缺失移除不重复通知。仅当前场景实际同一角色发出entity_id/remove_buff_id通知，脱离队伍的同实体号对象不能通知替代角色，正式/试用角色隔离。原先解析时删除添加动作的逻辑已修复；未知或缺失目标不再删除前序添加动作。读取飞霄122001、貊泽122302、希儿110202三条原始任务叶子并记录路径/SHA，但不宣称其条件链或完整技能变体已接通。15项新回归包含13项移除/顺序/通知及2项原始LookAt字符串目标兼容，先失败12+2项；全量1800项通过，累计187项真实先失败。初次1798项Java通过后网络复测揭示TargetType也有字符串，导致整份技能配置跳过；现已按动作类型读取JsonElement且保留未知形状，重跑构建与网络。正式/试用移除通知为Java生产调用到封包转换解码验证，尚未专门KCP发出Buff通知或验收UI。双角色25项、通用47项、Dispatch2项复测通过；动态表达式、条件失败分支、完整目标选择、技能变体/OnBattle时序、Buff战斗消耗、全部剧情日常宇宙活动仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _buff_remove1800 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeCasterBuffRemovalTest.java"]))


_maze_binding1811 = '探索主动作名称绑定修复：默认普通攻击优先选择当前资源的NormalAtk01，辅助TriggerHitMonster、RemoveMazebuff和NormalAtk02不再覆盖默认攻击；无规范名称的既有独立配置保留首个NormalAtk兼容路径。编译动作保留原始Ability.Name；4.6 SceneCastSkillCsReq字段15 maze_ability_str仅在名称与所选主动作完全一致时接通，空名称保留已有行为，未知名称、错技能索引、未接通变体在扣点和效果之前拒绝，试用秘技锁继续生效。三份4.6资源原始名称/SHA夹具配合明确隔离效果标记验证绑定；11项新增回归中5项真实先失败，全量1811项通过，累计192项真实先失败。新增可选网络模式发送三月七1224资源的LocalPlayer_Mar_7th_10_NormalAtk01原名，实际KCP验证接受与未知名称拒绝，并在同UID重连/H2进程重启复查；双角色既有25项加名称3项、三快照和27份源资源SHA通过，通用47项、Dispatch2项通过。该网络模式仍用放宽剧情门槛及隔离秘技锁夹具，普通攻击无目标，不是完整战斗或原版游戏UI验收。参考March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083的场景技能名称选择作为来源核对，自有实现只接通已绑定主动作，不采用未知名称回落执行。完整辅助/连击/秘技变体、条件分支和失败分支、动态表达式、目标选择、OnBattle时序、Buff消耗和全部剧情日常宇宙活动仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _maze_binding1811 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeSkillBindingTest.java", "src/main/java/org/hkrpg/server/protocol/LoopbackStoryTrialProbe.java", "scripts/smoke-lineup-leader.py"]))


_monster_wave1820 = '怪物Buff波次修复：NPC探索Buff只绑定其实际波次，掩码为1<<(waveNum-1)，不再用覆盖此前所有波次的前缀掩码；同BuffID、相同施法者槽位、等级1的NPC波次绑定合并波次位，保留已有动态值等元数据。拒绝0或超过32的无法表示波次，避免移位回绕；无效波次不消耗临时Buff。通用addBuff替换行为保留，合并仅用于NPC绑定；相同ID不同施法者的完整多实例仍未完成。参考March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083 BattleInstance的逐波2^count掩码，使用自有Java实现。九项新增回归中八项真实先失败，验证第1/2/4/32波、非相邻波的两种顺序、重复绑定元数据、无效波次及4.6 BattleBuff wave_flag/owner_index转换；全量1820项通过，累计200项真实先失败。测试为隔离NPC到Battle生产回调及Buff消息转换，没有原版多波战斗或游戏UI验收。双角色25+名称3项、三快照/27份源资源SHA，通用47项和Dispatch2项复测通过；网络流程未专门发送这些新多波Buff。动作条件/失败分支、动态表达式、完整目标/变体、OnBattle时序、Buff消耗及全部剧情日常宇宙活动仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _monster_wave1820 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeSkillBindingTest.java", "src/main/java/org/hkrpg/server/protocol/LoopbackStoryTrialProbe.java", "scripts/smoke-lineup-leader.py"]))


_avatar_maze1832 = '角色探索Buff分表加载：MazeBuffExcel在必需MazeBuff.json和可选RogueMazeBuff.json之外注册可选AvatarMazeBuff.json、AvatarMazeBuffLD.json，保留ModifierName、MazeBuffType、UseType，避免角色Buff定义及后续绑定依据缺失。4.6原始分表分别148/4行；白露121101、黄泉130801、昔涟141501和LD101401四行原样夹具记录来源、行号和SHA，验证实际资源加载、绑定字段保留、基础/宇宙表并存、老包缺少分表兼容、损坏的已存在分表报错和必需基础表不可替换。不补造原文缺失的UseType/绑定类型。12项新增回归中10项真实先失败，全量1832项通过，累计210项真实先失败；双角色25+名称3项、三快照/27份源资源SHA，通用47项和Dispatch2项复测通过。March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083 MazeBuffExcel的分表名称作为来源核对，使用自有Java资源注册和字段实现，不导入参考运行时代码。这里只完成资源输入和字段保留，角色Buff完整战斗绑定、消耗/修饰器生命周期尚未完成；条件分支、动态表达式、目标/变体、完整剧情日常宇宙活动及游戏UI验收仍未完成。'
if "SceneCastSkillCsReq" in REVIEWED:
    REVIEWED["SceneCastSkillCsReq"]["gap"] = _avatar_maze1832 + " 历史记录：" + REVIEWED["SceneCastSkillCsReq"]["gap"]
    REVIEWED["SceneCastSkillCsReq"]["tests"] = list(dict.fromkeys(REVIEWED["SceneCastSkillCsReq"]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeSkillBindingTest.java", "src/main/java/org/hkrpg/server/protocol/LoopbackStoryTrialProbe.java", "scripts/smoke-lineup-leader.py"]))


_lineup_buffs1841 = '场景队伍同步保留实际角色Buff：同一角色对象仍在场时保留实体编号、Buff及准确到期时间；新入队对象清理脱离场景的旧效果，离队对象清理Buff并撤销实体编号。同一AvatarID对应的对象已替换时，以对象身份识别删除旧实体和添加新实体，4.6完整刷新通知保持准确的新增/删除编号。9项新增回归包括正式/试用角色的1.1秒和永久Buff重复刷新、加入/离开/再次加入、普通队伍重排和同编号对象替换，8项真实先失败；全量1841项通过，累计218项真实先失败。角色25+名称3项、三次同UID快照和27份来源SHA，通用47项、Dispatch2项复测通过。参考March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083 SceneInstance.Actions.SyncLineup的仍在队角色保留规则，自有Java实现，不导入参考代码。新增Buff保留和对象替换行为由Java及4.6通知转换验证，网络复测覆盖既有入口；尚未新增携带Buff的正向KCP刷新用例或游戏UI验收。这里不代表换队触发的完整修饰器/怪物Buff清理、跨场景生命周期或进入战斗消耗已完成；完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "ReplaceLineupCsReq", "ChangeLineupLeaderCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _lineup_buffs1841 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SceneLineupBuffLifecycleTest.java"]))


_avatar_battle1858 = '角色探索Buff战斗快照与有限消耗：有效战斗在Player.setBattle入口一次冻结当前实际参战角色的未过期Buff、施法者槽位和目标槽位；首次及重复查询不再读取后来添加/删除的场景效果，未开始的预览不消费或污染战斗缓存。同一BuffID的两名角色独立保留绑定，显式战斗Buff也不被角色效果覆盖；既有ID查询保留首个可用绑定，完整发送使用独立角色列表。按资源明确的AddBattleBuff、CharacterSkill/CharacterSkillAdv及非空绑定键消耗一次性角色效果，复用实际场景身份校验的删除通知，重复进入/查询及下一场战斗不再重复使用。当前基础/LD角色分表中符合规则的资源为41/3条，不代表这些技能完整链均已验收；CharacterAbility、缺失UseType/定义/绑定键的消费语义仍待实现。17项新增回归使用白露121101和LD101401原样资源定义及隔离有效入口，验证正式/试用角色通知、两个施法者、显式Buff并存、前后查询/预览、过期与非法关卡；11项真实先失败，全量1858项通过，累计229项真实先失败。角色25+名称3项、三快照/27份来源SHA，通用47项、Dispatch2项复测通过。参考March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083 AvatarSceneInfo.ApplyBuff及BattleInstance.PrepareSceneBuffs的先结转再清理和多实例列表，自有Java实现，不复制运行时代码。新增消费/双施法者的正向验证为Java实际入口和4.6通知/BattleBuff转换；尚未新增携带Buff的正向KCP战斗或游戏UI验收。NPC同编号不同施法者、完整修饰器/条件/动态表达式/目标/堆叠等级和跨场景生命周期、完整剧情日常宇宙活动及客户端验收仍未完成。'
for _req in ["SceneCastSkillCsReq", "ReplaceLineupCsReq", "ChangeLineupLeaderCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _avatar_battle1858 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/AvatarBattleBuffSnapshotTest.java"]))


_summon_lifecycle1873 = '探索召唤资源和基础生命周期：固定4.6资源724b139d8c9c32d12552eb95745a4fee72bfe48b补充下载/安装全部探索技能目录、探索修饰器、召唤配置、MazeBuff配置和召唤实体配置，新增465份JSON；五目录664份原始JSON逐一SHA核对，原始33703条主清单SHA保持不变，追加清单不覆盖原资源。完整审计核验70433份文件，校验错误0，169条地图组引用仍缺失。原有“召唤目录未提供”的判断修正为本地下载选择遗漏；74条召唤表中28条服务端配置均存在且由独立Java进程实际调用生产加载器读入，46条客户端配置继续不建立服务端实例。新增4项安装回归覆盖五类运营冲突的复制前拒绝、原始清单保持及追加幂等，7个断言先失败，12项Python通过。单召唤物模型现在记录实际当前实体，替换/超时/直接移除清理旧实体和指针；时间以有符号int32毫秒传递，-1永久、合法有限秒数及长整数到期时间正确，非法或溢出时间在替换前拒绝。进战仅在有效战斗入口依据真实DestroyOnEnterBattle标记销毁，结算清空不销毁新建场外单位。黄泉13081和银狼15061两条Excel及完整配置原样夹具记录来源、行号和SHA；15项Java新增回归中10项真实先失败，全量1873项通过，Java累计239项真实先失败，安装Python先失败另计7项。角色25+名称3项/三快照/27份来源SHA、通用47项和Dispatch2项使用当前产物及追加资源复测通过。参考March7thHoney fe00bdbda3c28ca33d01f171566aa35b5ab95083的EntitySummonUnit有符号永久标记和固定资源的进战声明，自有Java实现，不复制运行时代码。生命周期、毫秒和永久值的正向验证为Java真实工厂/场景回调及4.6字段转换；尚无新增正向召唤KCP或游戏UI验收。技能Create/DestroySummonUnit动作、OnCreate/OnDestroy/条件/动态时长/目标、触发器完整链、MaxSummonCount/唯一组、完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "ReplaceLineupCsReq", "ChangeLineupLeaderCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _summon_lifecycle1873 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SummonUnitLifecycleTest.java"]))


_maze_summon_action1894 = '召唤技能声明动作（1894项快照）：删除按角色编号推算召唤物和统一20秒的旧逻辑；解析真实CreateSummonUnit的SummonUnitID及明确固定Duration，并依据服务端表和Caster位置/召唤者选择器准入。实际8条创建叶任务中7条服务端叶和1条客户端专用对照原样保存来源SHA、配置及表行；5个服务端编号的固定时长5/20/3/2/25秒在真实场景工厂执行。DestroySummonUnit按实际TargetFetchSummonUnit、Caster和编号销毁对应单位，错误编号、其他角色和未知目标不删除已有单位；动作按脚本排列执行。-1和0.5/1.1秒的服务端扩展夹具通过4.6有符号毫秒字段核验；实际飞霄0.5秒行是客户端专用，未建立服务端单位。21项新Java回归中17项真实先失败，全量1894项通过，累计Java256项真实先失败；Python12项通过。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项和Dispatch2项通过。4.6本机CreateSummonUnit字段/构造函数已只读定位，但其缺省Duration的最终消费者尚未核实；16条动态时长、20条未声明时长不会再猜测20秒或替换已有单位。所有测试仅显式执行叶任务，不证明原始条件/能力变体完整运行。Distance/Offset/PosAdaption、创建/销毁脚本、复杂目标、完整触发器、唯一组/多实例及正向召唤KCP/游戏UI仍待补齐；完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_summon_action1894 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeSummonActionTest.java"]))


_summon_trigger1911 = '召唤触发效果和销毁清理（1911项快照）：阿格莱雅14021和遐蝶14101原始召唤配置的TaskActionTarget固定LifeTime均为500秒，改为按声明保留秒数，不再统一5秒；明确固定的小数秒和-1永久扩展夹具也保留精度。动态时长及不支持的目标不会猜测并向怪物施加效果。触发处理串行、去除重复目标并拒绝已过期单位。召唤物记录实际AddBuff返回的效果实例；依据原始RemoveMazeBuffOnDestroy标志，在直接移除、到期、替换和入战销毁时只清理自身已施加且尚未被替换的效果；怪物同编号被同/其他角色后来覆盖后保留新实例；离开场景的怪物清理不发entityId=0通知，重复移除不重复发包。阿格莱雅原始清理标志为true，遐蝶原始缺省false的保留行为作为对照。新增17项Java回归，第一轮16项中14项真实先失败；随后同角色覆盖窗口的新增1项再先失败，累计Java271项真实先失败，全量1911项及Python12项通过。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项、Dispatch2项通过。验证执行真实Scene触发及清理回调和4.6BuffInfo秒字段转换；尚未新增正向召唤KCP或客户端碰撞/UI验收。触发半径/视线/目标筛选、OnTriggerExit、创建/销毁脚本、条件、动态/缺省时长、唯一组及完整能力链仍待完成，完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _summon_trigger1911 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SummonTriggerBuffLifecycleTest.java"]))


_maze_buff_predicate1925 = '角色持有效果条件与召唤销毁任务（1925项快照）：识别明确ByCompareCarryMazebuff/Caster/BuffID条件，在实际角色当前未到期效果中查询，并支持Inverse，运行时选择SuccessTaskList或FailedTaskList，不再将此类条件的成功列表直接展开。对该已知条件的未知目标不当作成功，释放锁后执行分支；普通攻击和命中回调保留此项条件。技能复用时重新读取角色状态。银狼原始150601条件及15061销毁选择器整体原样夹具记录来源、路径和SHA；已存在的召唤单位由测试明确创建，不证明其缺省Duration或完整创建链。召唤配置OnDestroy中明确CasterSummoner/RemoveMazeBuff按顺序调用实际施放者移除效果，银狼原始移除150601在直接、到期、替换和进战回调中通过，保留其他编号并只通知一次。新增14项Java回归中13项真实先失败，全量1925项通过，累计Java284项真实先失败；Python12项通过。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项和Dispatch2项通过；独立Java进程再次实际调用生产加载器，全部28个服务端召唤配置（含销毁列表）仍能加载，46个客户端专用配置不建立服务端实例。仅这一种简单条件及明确销毁移除叶已接入；复合/其他条件、分支选中状态跨异步事件继承、分支内TriggerBattle/修饰器状态、OnCreate、复杂销毁任务和动态/缺省时长仍待完成，完整剧情日常宇宙活动及4.6客户端验收仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_buff_predicate1925 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeCasterBuffPredicateTest.java"]))


_maze_buff_condition1942 = '探索效果任务的组合条件（1942项快照）：明确AddMazeBuff/Condition支持ByCompareCarryMazebuff/Caster及非空ByAnd、ByAny和布尔Inverse；整棵条件树在同一角色效果锁内使用同一时刻求值，解锁后才执行动作。未知类型、目标或非法字段不添加效果。砂金原始两条ByAnd/ByAny叶任务保存原始路径、来源SHA和固定提交，四种标记组合只执行符合条件的叶；原始外层动态随机条件与修饰器链未执行。显式Caster目标在命中及普通攻击回调仍施加给施放者，并在入战快照前执行。召唤TaskActionTarget效果也采用此条件门槛；组合条件与阿格莱雅触发器联测为明确标注的扩展夹具，原始完整链尚未验收。17项新增Java回归中15项真实先失败，全量1942项通过，累计Java299项真实先失败；Python12项通过。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项和Dispatch2项通过；独立生产加载器仍可读取28个服务端召唤配置，46个客户端专用配置不建立服务端实例。已接入范围为效果任务Condition，PredicateTaskList的组合条件、其他目标/条件、异步分支状态、创建脚本与动态/缺省时长仍待完成；未新增正向召唤KCP、碰撞或真实游戏UI验收。完整剧情日常宇宙活动仍未完成。固定资源仓库完整Git树核对确认169个地图组引用和2个剧情脚本引用均无同名原件或替代路径，不能通过继续下载该提交补齐。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_buff_condition1942 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeBuffTaskConditionTest.java"]))


_summon_trigger_enable1957 = '召唤触发器默认状态和定时开关（1957项快照）：每个实际单位分别保存原始DefaultEnable（缺省true）和开关游标，直接OnCreate中的明确固定WaitSecond按创建时间累计，并顺序执行SetSummonUnitTriggerEnable/Caster。椒丘0.566秒和开拓者0.25秒边界、乱破19.5秒仅关闭指定怪物触发器、康士坦丝缺省启用、万敌缺省关闭使用六份原始配置及路径/SHA/表行夹具。关闭期间实际物件不被破坏且不发触发通知，启用后可破坏；替换单位重建状态，时间线不反复覆写后续技能开关。明确TargetFetchSummonUnit/Caster/编号的技能开关接入释放、命中和攻击动作；万敌两条原始无TriggerNames的启用/禁用叶按本项目规则作用于全部已声明触发器，不是原生任务执行器验收。缺省/动态等待或未知可能阻塞任务后的时间线不猜时间，吉尔伽美什缺省等待仍待核对；相关视觉、移动、嘲讽等任务未因列入非阻塞名单而执行。15项新增Java回归中10项在修复前真实失败，全量1957项通过，累计Java309项真实先失败；Python12项通过。新增边界用例使用服务端明确事件时间，另在1942独立基线副本仅添加时间入口兼容转发后重跑，同一15项仍有10项断言失败，排除初始化耗时造成的伪失败。网络请求不读取客户端时间。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项、Dispatch2项和28个生产加载器召唤配置检查通过。尚未新增正向召唤KCP和游戏UI验收；乱破转发能力、开拓者/椒丘缺省LifeTime怪物效果、完整OnCreate/OnDestroy及时间缩放/动画等待均未完成，完整剧情日常宇宙活动仍待补齐。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _summon_trigger_enable1957 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SummonTriggerEnableTest.java"]))


_summon_default_wait1963 = '召唤创建等待的4.6缺省值（1963项快照）：本机两份客户端文件SHA锁定后只读核对WaitSecond构造及DynamicFloat静态初始化，二者引用同一静态存储槽和对象偏移，静态初始化实际写入固定数值零；四个函数的完整相关字节区间、SHA、反汇编与元数据类型记录在docs/client46-summon-trigger-evidence.json。未声明WaitTime现在采用此数值零，不再阻断后续开关；显式动态、空对象或未知等待仍不代入零。原始吉尔伽美什配置前两个命名触发器在零等待后启用，物件触发器保持关闭至第二个固定0.46秒等待结束；0/459/460/461毫秒通过确定的服务端事件时间核对，460毫秒后实际物件可破坏。新增6项Java回归中4项真实先失败，全量1963项通过，累计Java313项真实先失败；Python12项通过。旧吉尔伽美什未知等待负向用例改为第二个已声明等待的459毫秒边界；原1957快照与确定时钟先失败证据保留，不重复累计。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项、Dispatch2项及28个生产召唤加载检查通过。DefaultEnable/Enable布尔成员与构造写入的关联由元数据布局推断，并非原生任务消费者或游戏UI验收。这里仅核实缺省WaitTime的固定数值，时间缩放、帧调度和完整脚本执行仍待完成；缺省Duration、动态时长、正向召唤KCP、碰撞/目标筛选和完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _summon_default_wait1963 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SummonTriggerDefaultWaitTest.java"]))


_summon_trigger_target1970 = '召唤触发器目标类型与实际接受列表（1970项快照）：原始TargetTypes中的NPCMonster、Prop和NPC按实际EntityMonster/EntityProp/EntityNpc类筛选，目标还须属于当前场景且真实存在，重复目标只保留一次。动作和通知使用同一列表，执行动作前冻结目标编号，物件销毁后不会回报entityId=0；未知、缺失、重复或类型不符的原始编号不再原样返回。阿格莱雅怪物/物件/NPC三种原始列表及银狼原始怪物+物件混合列表保持不改，覆盖混合请求、无匹配、缺失/负数/零和物件销毁。隔离测试构造三类实际场景实体，不代表原始全部NPC/房间的客户端生成。7项新增Java回归全部真实先失败，完整1970项通过，累计Java320项真实先失败；Python12项通过。全部7项均通过真实出站转换解析4.6的RefreshTriggerByClientScNotify，核对原始命令1490、触发器编号/名称与字段14的接受目标列表，未知字段为空；原151份冻结协议不变。当前产物角色25+名称3项/三快照/27份来源SHA、通用47项、Dispatch2项和28个生产召唤配置检查通过。未声明TargetTypes的旧配置保留既有选择行为；未知声明类型不匹配。这里仅补目标类型和通知一致性，距离、视线、导航、TargetFilter/退出回调、NPC命中演出和银狼转发能力仍待完成，未新增正向召唤KCP/真实游戏UI验收；完整剧情日常宇宙活动仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _summon_trigger_target1970 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SummonTriggerTargetTypeTest.java"]))


_rogue_dialogue1984 = '宇宙原始事件NPC绑定与查询（1984项快照）：按实际事件房间GroupWithContent绑定原始NPC3013、RogueNPC40398及其进度0的三项选项14101/14102/14103。4.6客户端只读核对13个有PE边界及字节SHA的原生函数，确认NPC对话编号字段12、进度字段1、唯一实例字段5；5636选择请求的唯一编号字段3、选项编号字段12；查询实例编号字段14、选项编号字段4。HEOBLDJAOPE基本owner对的4/15按已匹配混淆名和旧版基本owner结构关联，独立原生owner消费与游戏UI尚待验证，未把元数据内存偏移当作wire字段号。生产加载器读取260个NPC定义、216个Event定义、318条对话与940个选项，这些数目只代表原始配置读取，不代表全部事件可玩。新租约只支持可解锁且唯一进度0的Event定义，绑定实际当前场景/入口/房间/组/配置实例，失效/离场/替换/未知条件不暴露旧实例；玩家级单调编号与已存旧局高水位恢复防止新局复用。查询和出站场景扩展在首次暴露编号前保存，重复查询不新增实例或重复保存。14项新增Java回归，12项在入口修改前真实失败，旧存档编号另1项真实先失败，完整1984项通过，累计Java333项真实先失败；Python12项通过。JSON存档重载后原始实例保持编号；额外双NPC用原始放置的隔离副本验证，不代表原始该房间有两个NPC。当前4.6固定资源缺少旧版DialogueEvent.json效果/成本定义，所有选项暂返回is_valid=false，已知原始选项请求返回ROGUE_STATUS_NOT_MATCH，外来编号/选项拒绝，均不发奖励、不清房间、不确认完成。原151份冻结协议不变，角色25+名称3/三快照/27来源SHA、通用47、Dispatch2和28个生产召唤配置均通过。没有新增正向事件KCP及事件记录H2进程重启验收，完整事件效果/成本/随机分支/动态选项/剧情进度/祝福奇物事件获取及真实游戏UI仍待完成。'
REVIEWED.update({name: {"tests": ["src/test/java/org/hkrpg/server/protocol/UniverseDialogueEntry46Test.java"], "gap": _rogue_dialogue1984} for name in ["GetRogueCommonDialogueDataCsReq", "SelectRogueCommonDialogueOptionCsReq"]})


_rogue_dialogue_network1984 = '宇宙事件入口网络与H2恢复（1984项追加验证）：自有LoopbackRogueDialogueProbe及scripts/smoke-rogue-dialogue.py已纳入源码，可从实际服务jar重复执行。8项正向UDP/KCP检查核对原始NPC事件元数据、三项原始选项、重复查询编号稳定、未实现效果拒绝、外来选项拒绝、实际钱包不变、重连及H2进程重启恢复。同一UID的首次/同进程重连/重启三份快照保持事件唯一编号，后两份从存档恢复租约。隔离首房池改为原始房间305，初始碎片50改300用于验证钱包；原始NPC/组/Act/选项不改，10份来源SHA保持不变，不代表官方首房生成或真实游戏客户端UI。该追加验证替代上一快照中事件正向网络及H2尚未验收的边界，完整事件效果/成本/随机分支/祝福奇物事件获取仍待完成。完整Java1984、Python12、角色25+名称3/三快照/27份SHA、通用47、Dispatch2及生产配置检查全部通过，151份冻结协议不变；本次未增加Java回归，累计真实先失败333项保持原值。'
REVIEWED.update({name: {"tests": ["src/test/java/org/hkrpg/server/protocol/UniverseDialogueEntry46Test.java", "scripts/smoke-rogue-dialogue.py"], "gap": _rogue_dialogue_network1984} for name in ["GetRogueCommonDialogueDataCsReq", "SelectRogueCommonDialogueOptionCsReq"]})


_rogue_broken_clock1999 = '公司咕咕钟转换与破碎奖励（1999项快照）：奇物70取得时若原始奇物9/展示55/效果9及参数499、999严格匹配，另存不可变转换参数。现有已报价的祝福强化、祝福重置和通用强化扣费只累计匹配取得实例的额外费用；超过499为本项目明确判定规则，原始资源仅给499/999，官方比较表达式未核实。跨门槛后同一玩家文档中以9替换70并发放一次999碎片，原70实例的奖励收据同时落盘。9只由该转换产生，不进通用候选或商店；持有9不能重新取得70。额外费用、正常费用、碎片余额、强化/菜单及转换共享一次保存，保存后才发送既有原生替换case的70→9与实际余额通知。资金不足、计数/余额溢出、转换参数损坏、既有9冲突明确拒绝且不扣费/升级/覆盖；旧存档或取得时缺少匹配9定义不借用后来资源补造奖励。15项新增Java回归，初始12项中8项真实先失败，含并发一次、JSON及Morphia两次重载、冻结来源改动与替换通知只在保存后发布；完整1999项通过，累计真实先失败341项。Python12、事件8项/三快照/10来源SHA、角色25+名称3/三快照/27来源SHA、通用47、Dispatch2及生产配置核验通过，151份冻结协议不变。未新增咕咕钟正向KCP/H2或真实游戏UI验收；购买/复活/下载等其他支出附加费及原始全部奇物、事件、命途、剧情活动与断电原子性仍待补齐。'
REVIEWED.update({name: {"tests": ["src/test/java/org/hkrpg/server/protocol/UniverseBrokenClock46Test.java", "src/test/java/org/hkrpg/server/protocol/UniverseCompanyClock46Test.java"], "gap": _rogue_broken_clock1999} for name in ["EnhanceRogueBuffCsReq", "GetRogueBuffEnhanceInfoCsReq"]})


_maze_mp2099 = '当前探索条件追加：AdventureByCompareMP读取真实施放者当前队伍的一次秘技点快照，六种比较、原生R4有限阈值/缺省0、Inverse及MP/效果组合分支接入；无玩家、身份/队伍不匹配或点数越界为不可判断，不展开成功或失败叶。33项新增回归中首批28项的21项真实先失败，其余验证剧情独立MP与浮点精度。原始银狼OnStage销毁召唤物任务显式单独执行，未接入完整修饰器生命周期或自动恢复道具。继承入口另有16项回归/7项真实先失败，对齐4.6已有前置校验，失败不扣点、不执行动作。完整剧情日常宇宙活动和游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_mp2099 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeMpPredicateTest.java", "src/test/java/org/hkrpg/server/gameplay/MazeCastPreflightTest.java"]))

_rogue_clock_native2099 = '公司咕咕钟原生消费追加：scripts/smoke-rogue-clock-shop.py先购买奇物70再首次查询另一原始商人的祝福库存，按原始分类及项目基础价格核对附加费用、批量扣费/祝福/钱包通知、重复拒绝、取得前报价冻结及重连/H2重启恢复。首房1000012、组1/35/117与1500初始钱包为隔离配置，12份原始场景/图/表SHA保持不变；未验证官方房间生成、原生破碎转换、复活费用或真实游戏UI。'
for _req in ["GetRogueShopBuffInfoCsReq", "BuyRogueShopBuffCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _rogue_clock_native2099 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["scripts/smoke-rogue-clock-shop.py"]))

_rogue_clock_conversion2113 = '公司咕咕钟原生转换追加：scripts/smoke-rogue-clock-conversion.py实际取得70并消费后正常停服，重启继续消费到累计额外支出522，核对70→9替换、一次999奖励及准确余额3177，复活报价100→80；重试、重连及再次H2重启不重发奖励或替换，转换阶段三快照的替换1/0/0、钱包通知4/0/0。取得前后商店报价保留冻结，14份原始来源SHA不变。临时房间组1/35/101/117/118、钱包5000及限定原始祝福/奇物目录属于隔离夹具；超过499仍为项目规则。未验证官方生成、复活实际扣费、断电原子性或真实游戏UI，完整宇宙及其他玩法仍未完成。'
for _req in ["GetRogueShopBuffInfoCsReq", "BuyRogueShopBuffCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq", "GetRogueInfoCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _rogue_clock_conversion2113 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["scripts/smoke-rogue-clock-conversion.py"]))

_story_arithmetic2113 = '剧情变量动作追加：SetCustomValue/AddCustomValue及显式完成子任务链按执行顺序预检，溢出返回已有完成方式错误，预检阶段不修改变量、不保存、不预约；实际每条变量动作再核对当前值。14项构造边界回归中8项真实先失败，覆盖父子修改顺序、正负范围、前序赋值、保存游标/JSON恢复、重复引用和已完成子任务。未证明原始全部脚本、其余未知动作或实际游戏UI完成。'
for _req in ["FinishTalkMissionCsReq", "FinishCosumeItemMissionCsReq", "GetMainMissionCustomValueCsReq", "StartFinishSubMissionCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _story_arithmetic2113 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/StoryActionArithmeticTest.java"]))

_maze_unknown2132 = '探索未知分支追加：所有PredicateTaskList先编译判断；未知、缺失、空值或错误JSON类型整枝跳过，不展开成功/失败动作、攻击回调、静态修饰器或分支战斗标记。既有施放者效果、秘技点及组合判断继续执行，技能默认标记及独立动作保留。19项回归中修正对照夹具后的17项真实先失败，固定提交三条完整原始砂金/通用火属性/Ashveil任务保留路径、资源与blob SHA；明确独立解析而非完整技能链。未知判断和目标求值器、已知分支的部分静态标记、全剧情宇宙活动及游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_unknown2132 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeUnknownPredicateTaskTest.java"]))


_maze_conditional2152 = '探索条件标记追加：已支持分支的施放者修饰器及秘技入战标记只在所选运行时分支登记，每施放者独立、下次施放清理；战斗入口按本次结果选择1000119或原有属性效果及是否入战。20项新增Java检查，12项状态及2项旧入口真实先失败，覆盖嵌套/失败分支、并发施放者、实际Morphia重载和原生4.6槽位1结构；服务夹具为空波次构造关卡。无条件默认缓存保留，完整修饰器生命周期、任意目标、原始全部技能及真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_conditional2152 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeConditionalMetadataTest.java", "src/test/java/org/hkrpg/server/gameplay/MazeConditionalMetadataBattleTest.java"]))


_maze_damage2189 = '探索属性判断追加：ByCharacterDamageType仅支持原始七种明确属性名及TargetAlias/Caster，按实际施放者自身数据比较，可组合和反向；缺失数据保持不可判断，未知目标/默认/数值/掩码拒绝。37项新增检查，最初35项有22项真实先失败，七条完整原始分支保留固定提交、路径、资源与blob SHA；两项进程内原生4.6/继承报文分发验证槽位1火属性试用者及秘技点5→4，队长为物理属性，攻击由捕获夹具终止。本机枚举字段和值有静态证据，不证明运行时掩码/默认语义；完整监听及原始技能、全剧情宇宙活动和真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_damage2189 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeDamagePredicateTest.java"]))


_maze_conditional_summon2202 = '探索条件召唤追加：先验证明确类型、服务器定义、施放者位置和固定时长，再登记既有入战策略；未知/缺失参数及客户端召唤不改变默认值。已支持分支中的有效创建只记录实际施放者本次结果，其他施放者/下一次未选中施放恢复默认，既有召唤物不凭空销毁。13项检查，修正缺失定义夹具后重新复现12项真实失败；原始Aglaea创建叶、表行及定义显式置于构造效果判断中，不证明原始完整技能或官方4.6禁止入战规则。全剧情宇宙活动、完整监听及真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_conditional_summon2202 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeConditionalSummonPolicyTest.java"]))


_maze_recovery2247 = '探索恢复参数追加：技能表按编号/等级加载，保留原始单位并拒绝缺失/损坏参数；固定HP比例及8001/8002两项限定SkillMaze绑定在实际施放读取当前参数，未知目标/运算/绑定不再回复固定15%。动态绑定由本机字符记录、字段与原始资源共同推断，完整角色二进制/表达式解释器仍未核对；没有将推断标成官方完整验证。45项新增检查，其中16项注册和23项恢复真实先失败；普通/宇宙生命隔离及两种试用角色请求处理器已核对，后者使用构造固定比例且在攻击边界停止。能量绝对单位/换算、完整原始技能、全部剧情宇宙活动与真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_recovery2247 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/emu/lunarcore/data/AvatarSkillParameterResourceTest.java", "src/test/java/org/hkrpg/server/gameplay/MazeRecoveryParameterTest.java"]))


_maze_sp2284 = '探索能量回复追加：停云限定SkillMaze参数及明确固定ModifyValue按实际角色基础SPNeed换算归一化能量，原始50/130产生3846增量，未知表达式/目标/运算或无效容量不补5000。明确Caster只写真实施放者，满条/零值不重复保存同步，宇宙能量不覆盖普通队伍/生命。37项检查中36项真实先失败。普通能量初始化SPNeed×输入/输入基数公式已从本机方法、类型槽、参数和枚举静态核对；绝对任务单位及动态绑定仍为受限推断，完整执行器/输入赋值链、容量修饰器/特殊能量、取整与SP正向KCP/H2重载/真实游戏UI仍待验收。全部其余玩法仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_sp2284 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeSpRecoveryParameterTest.java"]))


_maze_dispatch2318 = '探索任务分发追加：按完整类型名登记动作，明确IsClientOnly任务整枝不登记服务器动作/回调/入战标记；未知任务不凭SuccessTaskList展开，已知攻击/弹体任务按各自回调执行。显式非Caster修饰器目标不进入共享缓存，省略目标保留旧默认。34项检查，前33项中30项真实先失败；原始Cyrene动画加载对象保存固定来源/SHA并明确未实现。未知等待时序、完整动画/弹体/修饰器监听与全战斗剧情日常宇宙活动及真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_dispatch2318 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeTaskDispatchTest.java"]))


_maze_modifier2344 = '探索入战标记移除追加：明确Caster的RemoveAdventureModifier按动作顺序仅清除当前角色本次施放的指定标记，不改共享定义或其他角色；无条件添加不再提前于脚本位置生效，旧插件显式默认仍保留。26项检查，前25项中13项真实先失败；原始黄泉普攻移除完整对象固定来源/SHA并明确隔离调用。跨技能持久修饰器、原生创建销毁监听、OnAbort/OnStage分发、全部玩法和真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_modifier2344 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeModifierRemovalTest.java"]))


_maze_buff2391 = '探索增益时长参数追加：六个明确Caster原始秘技增益叶按实际角色、规范能力名、单一哈希和SkillMaze一级参数读取当前时长；知更鸟/赛飞儿原始15秒不再替换20秒。47项新增检查，修复前40项真实断言失败；18份原始角色/技能/任务记录固定来源和SHA，六份只读客户端记录受限推断且索引zigzag解码未完整核对。显式未知动态或固定时长不执行；仅省略时长仍保留旧20秒默认。完整动态解释器、原生默认/目标解析、全部玩法和真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_buff2391 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeBuffParameterTest.java"]))


_maze_buff_more2423 = '探索增益时长追加第二批：再接入七个明确Caster角色绑定和十一条原始叶，当前十三个角色；32项新增检查，18项真实先失败，首批47项保留。遐蝶/符玄/貊泽/希儿/驭空/云璃及资源角色1510的当前SkillMaze一级参数按源值读取，资源1510为30秒。新增25份原始记录/9份源文件和七份只读字符证据；外层条件不改但隔离调用，仍有索引推断。驭空的MDF_MoveSpeedRatio动态赋值等完整效果、其他目标/角色、全玩法和真实游戏UI仍未完成。'
for _req in ["SceneCastSkillCsReq", "RefreshTriggerByClientCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _maze_buff_more2423 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/MazeBuffRemainingParameterTest.java"]))


_daily_prop2444 = '日常真实破坏事件与活跃度修复：原始2100125/2100144的PropDestruct Equal无筛选规则各破坏3个真实物件后完成，重复实体/旧场景/复苏装置不能刷进度，未知参数不借用规则。原始奖励103001至103005使用Item23（50/100/150/200/500活跃度），按日常状态结算并拒绝重复领取；旧测试误用35已依据ItemConfig更正。21项新增检查，21项真实先失败；16份原始记录/6个源文件固定提交留证。新检查为实际Scene/Inventory与4.6响应转换，不代表新增KCP或真实游戏UI验收。完整日常/剧情/宇宙/活动仍部分支持。'
for _req in ["GetDailyActiveInfoCsReq", "TakeQuestRewardCsReq", "SceneCastSkillCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _daily_prop2444 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyPointRewardTest.java", "src/test/java/org/hkrpg/server/protocol/DailyPropDestruct46Test.java"]))


_daily_rogue2464 = '日常模拟宇宙通关联动：原始2100150的RogueFinishCnt NoPara无筛选目标，只在UniverseManager确认末房胜利并首次记录该次通关后推进，领取原始103005奖励500活跃度。20项新增检查，选择入口1项、事件链5项真实先失败；失败/退出/放弃/中间房/剩余怪物/旧战斗/未知规则/次日重放不完成目标。原始4份记录/4份源文件固定留证，战斗地图与房间为明确构造夹具。另新增原始日常三物件八项UDP/KCP验证，同UID首次/重连/正常H2重启保持300活跃度及两项已领取状态，11份来源SHA不变；首房和碎片钱包隔离，日常池/目标/奖励不改。没有宣称宇宙通关日常的网络正向验收或真实游戏UI、断电原子性；完整日常/剧情/宇宙/活动仍待完成。'
for _req in ["GetDailyActiveInfoCsReq", "GetQuestDataCsReq", "TakeQuestRewardCsReq", "GetRogueInfoCsReq", "SceneCastSkillCsReq", "PVEBattleResultCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _daily_rogue2464 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyRogueFinishTest.java", "scripts/smoke-daily-prop.py"]))


_daily_upgrade2515 = '日常真实升级与扣费补齐：六个原始AnyAvatarLevelUp/AnyEquipmentLevelUp/AnyRelicLevelUp单步NoPara目标只在正式所属目标实际增加等级后推进，原始100活跃度一次领取。三类升级在玩家锁内先准备材料/经验/费用，拒绝零负数、总量不足的重复条目、归属不符、锁定/装备中/自身材料、错误经验类型、溢出、满级及脱离正式库的临时角色；未升一级的有效经验不计日常。新增51项检查，选择6项、行为32项、遗器总经验溢出1项真实先失败，共39项；81份原始记录/18份固定源文件留证。角色1202/光锥20000/遗器31011及原始经验曲线/材料实际执行，前置任务完成与保存为明确测试替身。新升级目标没有KCP/UI或断电原子性验收，其他日常/剧情/宇宙/活动仍需继续完善。'
for _req in ["GetDailyActiveInfoCsReq", "TakeQuestRewardCsReq", "AvatarExpUpCsReq", "ExpUpEquipmentCsReq", "ExpUpRelicCsReq"]:
    if _req in REVIEWED:
        REVIEWED[_req]["gap"] = _daily_upgrade2515 + " 历史记录：" + REVIEWED[_req]["gap"]
        REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyUpgradeTest.java"]))


_daily_compose2577 = '合成与食物日常真实事件：五个原始单步目标仅由实际产出或实际食物使用完成后推进，原始100活跃度一次领取。普通合成与材料交换持玩家锁，在付款前合计材料、验证归属/锁定/类型/世界等级/单次上限/整数范围/输出容量，完整送达才计日常；没有限额记录或配方学习状态的受限分支先拒绝。62项检查、选择5项/行为20项/异常资源3项真实先失败，76份原始记录/7份固定源文件及本机Food502/Material601枚举留证；枚举匹配仍不等于原生完成条件执行。新增合成与食物日常没有KCP/UI验收，配方学习、周/月限额、自选遗器、跨文档原子性及其他玩法仍需继续完善。'
REVIEWED["ComposeItemCsReq"] = {"tests": ["src/test/java/org/hkrpg/server/gameplay/DailyComposeUseTest.java"], "gap": _daily_compose2577}
for _req in ["GetDailyActiveInfoCsReq", "TakeQuestRewardCsReq", "UseItemCsReq"]:
    REVIEWED[_req]["gap"] = _daily_compose2577 + " 历史记录：" + REVIEWED[_req]["gap"]
    REVIEWED[_req]["tests"] = list(dict.fromkeys(REVIEWED[_req]["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyComposeUseTest.java"]))


_formula_learning2603 = '配方书学习与原生库存接通：原始404005单本实际扣除、持久化已学书编号并解锁2102；原生背包字段13及使用响应字段15经本机消费者/序列化codec只读核对。26项回归、10项真实先失败，5份原始记录/2份固定源文件；12项KCP与同UID重连/正常H2重启通过，4份原资源SHA不变。重复/批量/非法归属/未知映射不扣书，待处理记录不重放；空邮件查询适配，未知分页拒绝，普通/自选遗器失败回包不再假报成功。初始库存邮件隔离，不证明官方剧情入口或全部83配方/UI；周/月限额、自选遗器和跨文档断电一致性及其余玩法仍待完善。'
for _req in ["ComposeItemCsReq", "UseItemCsReq", "GetBagCsReq", "GetMailCsReq", "ComposeSelectedRelicCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _formula_learning2603 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/FormulaLearningTest.java"]))


_composition_period2677 = '周/月合成限额接通：全部29个原始周限交换及1个月限配方有真实产出回归，按每配方20/2份持久化用量。74项检查、44项真实先失败、61份原始记录/2份固定源文件；14项KCP及同UID重连/正常H2重启核对实际材料/产物和原生30条快照、已用数通知。并发、中断、回拨、异常存档和未知源字段不绕过预算，在线跨周期tick一次刷新；中国4点周一/月初是明确服务策略，未确认官方原生刷新时刻。严格PILE成本及错误响应接通，未知convert/oneof拒绝；不证明官方等级门槛、网络跨周期/全部周限KCP/UI、自选遗器或断电原子性，完整其他玩法仍待继续完善。'
for _req in ["ComposeItemCsReq", "GetBagCsReq", "PlayerLoginCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _composition_period2677 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/CompositionPeriodTest.java"]))

_selected_relic2919 = '自选遗器实际入库与4.6入口补齐一批：62个原始配方的全部192组合、242项回归、26项真实先失败、490份原始记录/6份固定源文件。先验证实际材料归属/锁定、源数量/费用/容量、合法主副词条及唯一编号空间，冻结随机生成词条和成本后实际扣款入库；保存/部分交付中断保留尝试并阻止后续普通/周期/遗器合成，不伪造成功产物。13项KCP、同UID重连/正常H2重启三快照核对实际三件61011完整词条、235/236/237剩量和信用点，六份原SHA不变；四份完整本机原生函数字段/codec留证。237单/双副词条1/4费用及副词条须有固定主词条沿用项目规则，官方费用表达式/随机概率、6/14成本列表消费语义、全部192组合KCP和真实UI、官方剧情门槛及跨文档断电原子性仍待验收，其他玩法继续补齐。'
for _req in ["ComposeSelectedRelicCsReq", "GetBagCsReq", "ComposeItemCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _selected_relic2919 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/SelectedRelicCompositionTest.java"]))

_download2946 = '经典宇宙下载校验与正向网络补齐：27项新增检查、18项真实先失败；正式角色实际缓存身份/归属/UID/原始资源与命途关系校验，原生重复装置/错误线格式/uint32截断别名和未确认额外列表均拒绝，打包/非打包单人请求支持。16项KCP、同UID重连/正常H2三快照保持原始306/8000103/G22/300004装置Locked、可用1202/8001、双人出战与领队1、10000生命/5000能量及50碎片，普通队伍/能量保持原值；14份原始记录/九份固定源文件SHA及七份本机完整原生方法证据留存。首房/邮件是隔离夹具，一台一次免费正式下载为项目规则；特殊类型/上限、官方剧情取得、全部模式与真实UI、写入中断原子性及其余玩法仍待继续补齐。'
for _req in ["PickRogueAvatarCsReq", "ReplaceLineupCsReq", "EnterRogueCsReq", "GetRogueInfoCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _download2946 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/protocol/UniverseDownloadValidation46Test.java"]))

_dailyChallenge2976 = '忘却之庭日常2100119已接实际整关胜利：原始ListContain[1]/进度1和标题核对，仅Memory；原始200活跃度一次领取。30项回归通过、7项真实先失败，六份原始记录/六份固定源文件SHA留证。前置4010134保持，双半场仅最后胜利计数，失败/重复/错误遭遇及17种未知规则不推进；原生探针增加29条断言与同UID重连/H2恢复流程，执行结果见验证文档。单元遭遇/角色和保存是明确夹具；官方每日随机、网络跨4点、其他挑战类别、完整前置剧情/UI、断电原子性及其余玩法待继续补齐。'
for _req in ["GetDailyActiveInfoCsReq", "GetQuestDataCsReq", "TakeQuestRewardCsReq", "PVEBattleResultCsReq", "StartChallengeCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _dailyChallenge2976 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyChallengeFinishTest.java"]))

_dailyFarmType3016 = '拟造花萼金/赤日常2100157/2100158接实际胜利、体力扣除与掉落后推进：原始单步[6,7,8]/[5]，本机4.6 FarmTypeConfig常量及CocoonRow字段类型核对，保留原始FarmType；两项目标各200活跃度一次领取。40项回归通过、11项真实先失败；26份原始记录/八份固定源文件SHA。14条KCP断言覆盖两个独立金/赤场景、实际1001/1004一波错误颜色与两波匹配颜色、30体力扣除、奖励/重复及各三份同UID重连/正常H2恢复快照；八份原资源SHA不变。网络池选择与前置4010121/4010123是隔离夹具；全部地点/世界等级、其他FarmType类别、官方完整门槛/每日随机/网络跨4点/UI及断电原子性仍待继续补齐。'
for _req in ["StartCocoonStageCsReq", "QuickStartCocoonStageCsReq", "GetDailyActiveInfoCsReq", "GetQuestDataCsReq", "TakeQuestRewardCsReq", "PVEBattleResultCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _dailyFarmType3016 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/DailyFarmTypeFinishTest.java"]))

_itemSale3091 = '未强化遗器/光锥分解与原始日常2100109补齐：按SellType和原始返还预检整批唯一物品、归属/实际缓存/装备锁/重复编号/500上限/返还容量，再保存计划、实际删除和发放收据，未完成记录阻止自动重试。修复正式资源加载器遗漏equipExcelMap。75项回归通过、42项真实先失败，40条原始记录/12份固定源文件SHA；七份本机完整原生方法确认511请求、2/12字段和唯一物品oneof，返还方式语义参考Honey fe00bdb。16条KCP断言与三份同UID重连/正常H2快照确认光锥221×1、五星遗器235×10或232×3实际返还，原始100活跃度仅一次且非普通Item23。网络邮件库存/日常池/前置4010125为隔离夹具；强化经验/额外返还、叠影光锥、全部物品、官方剧情/每日随机/UI及断电原子性仍待补齐。'
for _req in ["SellItemCsReq", "GetBagCsReq", "GetDailyActiveInfoCsReq", "GetQuestDataCsReq", "TakeQuestRewardCsReq"]:
    _entry = REVIEWED.setdefault(_req, {"tests": [], "gap": ""})
    _entry["gap"] = _itemSale3091 + " 历史记录：" + _entry["gap"]
    _entry["tests"] = list(dict.fromkeys(_entry["tests"] + ["src/test/java/org/hkrpg/server/gameplay/ItemSaleTest.java", "src/test/java/emu/lunarcore/data/EquipmentResourceRegistryTest.java", "scripts/smoke-item-sale.py"]))

_expedition3240 = '4.6现代小时委托接23种原始HarvestData/RewardData与原始任务/队伍条件；查询不完成，首次派遣无即时收益，改派结算旧冻结收益、整小时/30小时上限与相同列表不重置。实际背包/信用点发放及唯一预留收据，失败/部分发放不计日常且禁止自动重试。原始日常2100002/2100133各100活跃度唯一领取。62项回归、92条原始记录/8份固定源文件与21份完整本机原生方法留证。17项KCP检查和五份同UID快照通过：查询不推进、真实派遣1001/1002、两项日常各100活跃度一次领取、重复派遣不重置、两小时原始111001/111011各4实际入库及重复领取拒绝，重连和stop命令正常关服/H2重启保留200活跃度、库存、时间锚点和已完成收据，九份原始资源SHA不变。前置1010403和两小时经过由仅测试类的隔离离线夹具设置，每次修改前后独立重开校验，不代表官方剧情自然完成或自然等候。通知2526、完整剧情前置、自然两小时、全部热修分支、断电原子性和实际游戏UI待完成/验收。'
for _req in ['GetExpeditionDataCsReq', 'AcceptMultipleExpeditionCsReq', 'TakeMultipleExpeditionRewardCsReq']:
    REVIEWED[_req] = {'tests': ['src/test/java/org/hkrpg/server/gameplay/ExpeditionHarvestTest.java', 'scripts/smoke-expedition.py'], 'gap': _expedition3240}

_mail3282 = '邮件领取状态补齐：已读与领取分离，先预留邮件、验证完整实际发放、保存钱包再确认已领；部分/异常发放保留待核对并阻止重试/删除。独立附件副本保留装备属性，实际成功响应不使用被修改的原附件。历史已读保守关闭，不自动重发或迁移；管理员邮件按数量展开独立装备。42项新增检查、14项真实先失败，8个原生类型字段常量/24份完整本机方法留证。原型22项KCP、四份同UID快照、三次stop正常关服及三份H2只读检查验证材料235×3/光锥20000×2/信用点1000一次到账、读取/领取/删除、钱包/装备身份/同一邮件收据恢复，五份原资源SHA不变。欢迎邮件配置是隔离夹具，最终同源码构建与同jar复验另行记录。全部附件类型、可选奖励、逐封失败结构、完整分页/模板/星标、历史已读迁移、部分发放网络/自动对账、断电原子性、国服UI及全部剧情宇宙活动待完成。'
for _req in ["GetMailCsReq", "MarkReadMailCsReq", "TakeMailAttachmentCsReq", "DelMailCsReq"]:
    REVIEWED[_req] = {"tests": ["src/test/java/org/hkrpg/server/gameplay/MailAttachmentClaimTest.java", "scripts/smoke-mail.py"], "gap": _mail3282}


REVIEWED["GetRogueHandbookDataCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/RogueHandbook46Test.java", "src/test/resources/fixtures/rogue-handbook-4.6.json", "src/test/resources/fixtures/rogue-handbook-client-4.6.json", "scripts/smoke-rogue-shop.py"],
    "gap": "原生5684/5640查询实际一级经典祝福/奇物收集，5679真实通知字段经本机15份完整方法/两类型槽核对；112原始图鉴配置与明确UnlockHandbookMiracleID关联，旧编号10/18及修复46不按外观猜测。32新增回归、15真实先失败、两次实际Player恢复；骰子预览不写入、提交合并临时骰子与替换历史，资源/历史变化拒绝，损坏/容量拒绝。商人合成KCP查询/购买/通知/重连/正常H2重启/结束探索后保留以本轮最终源码证明核对。首房/钱包/目录为隔离夹具；旧持有不迁移，奖励/事件/星神/其他模式/完整选池/国服UI/异常关服原子性待完成，见docs/rogue-handbook-4.6.md。"}

REVIEWED["TakeRogueMiracleHandbookRewardCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/RogueHandbookReward46Test.java", "src/test/resources/fixtures/rogue-handbook-reward-4.6.json", "src/test/resources/fixtures/rogue-handbook-reward-client-4.6.json", "scripts/smoke-rogue-shop.py"],
    "gap": "5641/5630依实际收集、整批预检与逐项持久收据接原始106011/星琼1×30；只确认实际支付，混合已领跳过、重复/未决不重发，查询已领从收据读取。45新增、12真实先失败、四原件/四來源、三完整本机响应方法/一类型槽及两次Player恢复；30/60星琼商人KCP/通知先于响应/重连/正常H2/结束探索保留依最终源码证明核对。请求列表2依固定协议，尚未定位本机UI发送类型；100项/4096字节为本服边界。其他图鉴奖励/模式/自动修复/完整客户端UI及异常关服原子性待完成，见docs/rogue-handbook-reward-4.6.md。"}

REVIEWED["DiceCombatMainPageRollDiceCsReq"] = {'tests': ['src/test/java/org/hkrpg/server/protocol/DiceMainPageRoll46Test.java', 'scripts/verify-client-dice-mainpage.py', 'scripts/smoke-dice-mainpage.py'], 'gap': '经典骰子首页报告已接入9262/9276空请求：本机3个PE函数/16个指令点核对发送命令、响应字段8和原始1500毫秒冷却；接受计数和时间保存到Player，旧存档从零开始。原始4055044前置4000001、20次投掷和奖励113000的5星琼只按合法来源历史推进/领取，查询与领奖重复核对来源，伪造完成、并发重复、冷却未到、非法记录、保存失败和资源改动不计数/发奖。56项Java检查含5项真实行为先失败，两次实际Morphia恢复通过。隔离KCP分段20次投掷、正常H2重启和实际奖励验证以build/dice-mainpage-roll-verification.json为准。首页计数不代表完整对战投掷/骰面/技能/回合演算，前置教程/页面放开的明确夹具不算官方剧情验收，实际4.6游戏UI与崩溃原子性仍待完成，见docs/dice-mainpage-4.6.md。', 'resourceVersion': True}

REVIEWED["ClockParkGetInfoCsReq"] = {
    "tests": ["src/test/java/org/hkrpg/server/protocol/ClockParkCatalog46Test.java",
              "scripts/tests/test_client46_clock_park.py", "scripts/smoke-clock-park-catalog.py",
              "docs/clock-park-original-stories-4.6.md", "docs/clock-park-opening-4.6.md",
              "src/test/java/org/hkrpg/server/protocol/ClockParkOpening46Test.java",
              "scripts/smoke-clock-park-opening.py"],
    "resourceVersion": True,
    "gap": "本机27份剧情配置及27份图完整读取，14类字段/10个任务分支/25个原生函数复核；"
           "本服原始对象包保留省略字段、动画、等待和TextID，不重建Unity JSON或执行剧情。"
           "四份固定Git Excel和对象包完整身份与六剧本/28章节/169回合/27故事引用核对后，"
           "7234/7206只读返回六个剧本，字段9只按已保存的实际开场返回到达章节，不把起始可用写成到达。"
           "44项Java含三项入口先失败、13项Python；最终原生与独立源码以同SHA完成报告为准。"
           "五个普通开场及持久化追加见docs/clock-park-opening-4.6.md；回合/骰子/分支/射击/电影/Buff/结算/奖励及真实UI仍待完成。"
           "见docs/clock-park-original-stories-4.6.md。"}

for request in ("ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq",
                "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    REVIEWED[request] = {
        "tests": ["src/test/java/org/hkrpg/server/protocol/ClockParkOpening46Test.java",
                  "scripts/smoke-clock-park-opening.py", "docs/clock-park-opening-4.6.md"],
        "resourceVersion": True,
        "gap": "五个原始普通剧本开场按真实等级/运营窗口/完成子任务校验，当前局与实际到达记录保存后返回，"
               "重复开始/错误退出/保存失败/坏来源和坏记录不覆盖；进行中原生状态、JSON及十种各两次Player/Morphia恢复有回归。"
               "61项Java含39项真实先失败，原生五局正常H2恢复及独立源码验证以build/clock-park-opening-verification.json为准。"
               "属性初值零为明确本服开场规则，前置及运营窗口为隔离夹具；普通骰子操作追加见docs/clock-park-dice-4.6.md。"
               "每章最后普通回合后续操作保留局；检查点/分支/小游戏/Buff/结局/积分/奖励/无尽模式与真实UI仍待完成。"}

for request in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq",
                "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    REVIEWED[request]["tests"] += ["src/test/java/org/hkrpg/server/protocol/ClockParkDice46Test.java",
                                  "scripts/smoke-clock-park-dice.py", "docs/clock-park-dice-4.6.md",
                                  "src/test/java/org/hkrpg/server/protocol/ClockParkPlacedFaces46Test.java"]
    REVIEWED[request]["gap"] = (
        "普通骰子回合追加：五个原始开场确认后保存三个服务端骰子，按原始CardAction/Effect计算属性，"
        "固定1001/1002/1003为明确本服牌组而非官方分布。面值数量/归属/回合/重复/多oneof/截断别名校验，"
        "保存失败回退、回执重新计算和正常恢复有66项新增Java（49项先失败，含本机面值字段更正8项）。"
        "合成KCP候选204次请求与18次实际推进、五局正常H2恢复；最终双源码产物以build/clock-park-dice-verification.json为准。"
        "每章最后普通回合不提前结算，检查点/分支/后续小游戏/其他牌/Buff/结局/奖励/无尽/完整剧情及真实UI未完成。"
        "见docs/clock-park-dice-4.6.md。") + REVIEWED[request]["gap"]


for _checkpoint_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _checkpoint_row46 = REVIEWED[_checkpoint_request46]
    _checkpoint_row46["tests"] = list(dict.fromkeys(_checkpoint_row46["tests"] + [
        "src/test/java/org/hkrpg/server/protocol/ClockParkCheckpoint46Test.java",
        "scripts/smoke-clock-park-checkpoint.py", "docs/clock-park-checkpoint-4.6.md"]))
    _checkpoint_row46["gap"] = ("首三章末轮结算原始骰子效果后保存111/211/311的实际检查点结果；23项新增回归含初始8项先失败、六种各两次Morphia重载、坏记录/保存失败/并发。确认操作13保留结果至分支/结局实现，后两章不跳过原始小游戏。当前牌组不增C、201的B最多24；完整玩法与真实UI仍待完成。历史记录：" + _checkpoint_row46["gap"])

for _branch_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _branch_row46 = REVIEWED[_branch_request46]
    _branch_row46["tests"] = list(dict.fromkeys(_branch_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkBranch46Test.java", "scripts/smoke-clock-park-branch.py", "docs/clock-park-branch-4.6.md"]))
    _branch_row46["gap"] = "原始首章检查点通过后保存单骰子ToChapter分支及下一章STORY/DICE；属性携带、历史回执与抵达证明复算、保存失败回退、防重及实际Morphia/H2恢复已覆盖。官方抽牌分布、其他分支/结局/小游戏和真实UI仍待完成。历史记录：" + _branch_row46["gap"]

for _ending_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _ending_row46 = REVIEWED[_ending_request46]
    _ending_row46["tests"] = list(dict.fromkeys(_ending_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkEnding46Test.java", "scripts/smoke-clock-park-ending.py", "docs/clock-park-ending-4.6.md"]))
    _ending_row46["gap"] = "103原始真结局：实际第二检查点121、1102分支和10301剧情确认后领取唯一任务物品140484并记2000进度；原生状态5/结果通知、保存中断重试、同物品身份恢复、查询凭据及实际库存核对与重复通关防重接入。40项Java及实际KCP/H2恢复边界见docs/clock-park-ending-4.6.md；唯一物品恢复布局为明确隔离夹具。其他结局、小游戏、官方抽牌/Buff/无尽/完整剧情、真实UI及断电原子性仍待完成/验收。历史记录：" + _ending_row46["gap"]

for _failure_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _failure_row46 = REVIEWED[_failure_request46]
    _failure_row46["tests"] = list(dict.fromkeys(_failure_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkFailure46Test.java", "scripts/smoke-clock-park-failure.py", "docs/clock-park-failure-4.6.md"]))
    _failure_row46["gap"] = "检查点真实失败确认后保存最近失败证明并结束本局，原生状态1/无奖励失败通知；通过返回状态2，真结局状态5。24项Java含回退/并发/损坏记录和六种各两次Player/Morphia重载；实际KCP/H2和结局回归边界见docs/clock-park-failure-4.6.md。累计失败历史、其他结局/小游戏/官方抽牌/Buff/无尽/完整剧情及真实UI仍待完成，正常重启不证明断电原子性。历史记录：" + _failure_row46["gap"]

for _balanced_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _balanced_row46 = REVIEWED[_balanced_request46]
    _balanced_row46["tests"] = list(dict.fromkeys(_balanced_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkBalancedPool46Test.java", "scripts/smoke-clock-park-balanced.py", "docs/clock-park-balanced-pool-4.6.md"]))
    _balanced_row46["gap"] = "新局明确本服v2牌组1001/1007/1008按原始效果增A/B/C；211可自然通过，跨章和失败证明按本局规则复算，旧局缺字段继续v1。27项Java含实际旧BSON读取、七阶段各两次Player/Morphia恢复、保存回退和并发；原生八阶段KCP/H2边界见docs/clock-park-balanced-pool-4.6.md。官方发牌、更多分支/结局/小游戏/Buff/无尽/完整剧情及真实UI仍待完成。历史记录：" + _balanced_row46["gap"]

for _conditional_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _conditional_row46 = REVIEWED[_conditional_request46]
    _conditional_row46["tests"] = list(dict.fromkeys(_conditional_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkConditionalBranch46Test.java", "scripts/smoke-clock-park-conditional.py", "docs/clock-park-conditional-branch-4.6.md"]))
    _conditional_row46["gap"] = "原始221按B≥55通过后，2102消费实际单骰面：1–2到204、3–6到203；扁平历史和抵达证明按面值复算。原始第三章231/241和2103/2104可进入205/206结局剧情，但结算未接入、未计进度/物品。32项Java含六项真实先失败、八种各两次Player/Morphia读取与并发；自然骰面KCP/H2和双源码边界见docs/clock-park-conditional-branch-4.6.md。小游戏、更多结局结算、完整剧情/官方发牌/Buff/无尽及真实UI仍待完成。历史记录：" + _conditional_row46["gap"]

for _slot_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _slot_row46 = REVIEWED[_slot_request46]
    _slot_row46["tests"] = list(dict.fromkeys(_slot_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkSlot46Test.java", "scripts/tests/test_clock_park_slot_sources.py", "scripts/smoke-clock-park-slot.py", "docs/clock-park-slot-4.6.md"]))
    _slot_row46["gap"] = "原始51008老虎机在四个普通回合后接入，预填5150卡和已保存权重票，原始零成本/一次抽奖/四档B增量；中奖ACK字段8只加一次，重启可缓存回复，退出或跳过复算真实501。43项Java含三项真实先失败、五阶段各两次Player/Morphia、三阶段回退/并发/坏请求/坏证明，完整来源表两项Python；十阶段KCP/H2和双源码边界见docs/clock-park-slot-4.6.md。5119及后续老虎机/其他小游戏、更多结局、官方发牌/Buff/无尽/完整剧情和实际UI仍待完成。历史记录：" + _slot_row46["gap"]

for _slot_chapter_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _slot_chapter_row46 = REVIEWED[_slot_chapter_request46]
    _slot_chapter_row46["tests"] = list(dict.fromkeys(_slot_chapter_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkSlotChapters46Test.java", "scripts/smoke-clock-park-slot-chapters.py", "docs/clock-park-slot-chapters-4.6.md"]))
    _slot_chapter_row46["gap"] = "原始四章老虎机、501/502/503和5119/5221/5327/5427接通；5221实际单面1–2到540、3–6到530，逐章重放中奖/骰面/检查点，抵达550/560原始剧情仍未结算。44项Java含三项真实先失败、八阶段各两次Player/Morphia、非截断并发增量、坏请求/历史、两条503失败；十四阶段自然KCP/H2和双源码边界见docs/clock-park-slot-chapters-4.6.md。更多结局、其他小游戏、官方发牌/Buff/无尽/完整剧情和实际UI仍待完成。历史记录：" + _slot_chapter_row46["gap"]

for _second_ending_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _second_ending_row46 = REVIEWED[_second_ending_request46]
    _second_ending_row46["tests"] = list(dict.fromkeys(_second_ending_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkSecondEnding46Test.java", "scripts/tests/test_clock_park_second_ending_sources.py", "scripts/smoke-clock-park-second-ending.py", "docs/clock-park-second-endings-4.6.md"]))
    _second_ending_row46["gap"] = "20501/20601原始结局剧情确认后分别结算205单件140483与2000进度、206空奖励与零进度；两条独立完成证明/凭据，预约、重试和实际唯一物品核对，整局重复不再发奖。45项Java含三项真实先失败、八阶段各两次Player/Morphia及并发/中断/损坏凭据；3项原始来源核对、十二阶段自然KCP/H2及双源码边界见docs/clock-park-second-endings-4.6.md。其他结局/小游戏、官方发牌/Buff/无尽、完整剧情和实际UI仍待完成。历史记录：" + _second_ending_row46["gap"]

for _fifth_ending_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _fifth_ending_row46 = REVIEWED[_fifth_ending_request46]
    _fifth_ending_row46["tests"] = list(dict.fromkeys(_fifth_ending_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkFifthEnding46Test.java", "scripts/tests/test_clock_park_fifth_ending_sources.py", "scripts/smoke-clock-park-fifth-ending.py", "docs/clock-park-fifth-endings-4.6.md"]))
    _fifth_ending_row46["gap"] = "第五剧本三个原始老虎机/实际检查点后，50501/50601剧情确认分别结算550单件140485和2000进度、560空奖励和零进度；独立完成历史/来源/凭据、中断重试及整局重复防重。45项Java含三项真实先失败、八阶段各两次Player/Morphia及并发/损坏/中断，3项原始来源核对；十二阶段自然KCP/H2与双源码边界见docs/clock-park-fifth-endings-4.6.md。第三/第四剧本、其他小游戏、官方发牌/Buff/无尽、完整剧情和实际UI仍待完成。历史记录：" + _fifth_ending_row46["gap"]

_pending_wire46 = REVIEWED["HandleRogueCommonPendingActionCsReq"]
_pending_wire46["tests"] = list(dict.fromkeys(_pending_wire46["tests"] + [
    "src/test/java/org/hkrpg/server/protocol/UniversePendingWire46Test.java",
    "docs/universe-pending-wire-4.6.md"]))
_pending_wire46["gap"] = (
    "5676原始uint32/单值字段/oneof/嵌套长度在DynamicMessage解析前验证；37项回归中22项断言先失败，"
    "34种异常请求保留菜单/货币/取得记录，3项合法祝福/奇物/省略批次重选仍实际执行。"
    "钟表小子packed/unpacked面值共用验证器；游戏UI、自然事件和完整宇宙待验收。历史记录："
    + _pending_wire46["gap"])


for _mini_game_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _mini_game_row46 = REVIEWED[_mini_game_request46]
    _mini_game_row46["tests"] = list(dict.fromkeys(_mini_game_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkMiniGames46Test.java", "scripts/tests/test_clock_park_mini_game_sources.py", "scripts/smoke-clock-park-mini-games.py", "docs/clock-park-mini-games-4.6.md"]))
    _mini_game_row46["gap"] = "30208射击、41009/42009电影保存原始卡面/来源并接零评级退出与真实检查点；原始四剧本分支零增量保留属性。69项Java含3项真实先失败、八状态各两次Player恢复与完整原始wire拒绝，5项Python和11份完整本机函数；十五阶段自然KCP/H2及双源码边界见docs/clock-park-mini-games-4.6.md。每次仍校验完整资源哈希的不可变配置缓存通过同尺寸/mtime变更回归；正评级属性公式、小游戏UI、第三/四剧本结局、官方发牌/Buff/无尽及完整剧情仍待完成。历史记录：" + _mini_game_row46["gap"]


for _last_ending_request46 in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
    _last_ending_row46 = REVIEWED[_last_ending_request46]
    _last_ending_row46["tests"] = list(dict.fromkeys(_last_ending_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkLastEndings46Test.java", "scripts/tests/test_clock_park_last_ending_sources.py", "scripts/smoke-clock-park-last-endings.py", "docs/clock-park-last-endings-4.6.md"]))
    _last_ending_row46["gap"] = "第三/第四剧本305/306/450/460补齐原始结局故事与完整两件/单件/空奖励，真结局各2000，原始普通目录九结局配方已接；预约后只补缺失唯一物品并保存已发/完成证明，保持既有来源。93项Java含四项真实先失败、18种状态各两次Player恢复及部分交付/并发/重复，4项原件核对；22阶段自然KCP/H2、双源码与旧结局回归边界见docs/clock-park-last-endings-4.6.md。正评级小游戏属性公式、官方发牌/Buff/无尽、完整剧情、其他活动宇宙及游戏UI仍待完成。历史记录：" + _last_ending_row46["gap"]


for _ranked_request46 in ("ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq", "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq", "ClockParkGetInfoCsReq"):
    _ranked_row46 = REVIEWED[_ranked_request46]
    _ranked_row46["tests"] = list(dict.fromkeys(_ranked_row46["tests"] + ["src/test/java/org/hkrpg/server/protocol/ClockParkRankedGames46Test.java", "scripts/smoke-clock-park-ranked-games.py", "docs/clock-park-ranked-games-4.6.md"]))
    _ranked_row46["gap"] = "原始射击/两场电影0至3评级按本服累计规则v2结算属性，保存实际评级、胜利、来源与原始检查点；旧零规则v1及来源不升级，历史各自复算。九项真实先失败，保存回退、封顶、并发、防重、旧存档/混合历史及真实Player恢复和25阶段自然KCP/H2边界见docs/clock-park-ranked-games-4.6.md。此规则是本服设计，官方正评级公式、发牌/Buff/无尽、Unity小游戏、完整剧情/其他活动和游戏UI仍待完成。历史记录：" + _ranked_row46["gap"]


_boxing_tests46 = ['src/test/java/org/hkrpg/server/gameplay/BoxingClubRounds46Test.java', 'src/test/java/org/hkrpg/server/protocol/BoxingClubCatalog46Test.java', 'scripts/tests/test_boxing_club_sources.py', 'scripts/smoke-boxing-club-rounds.py', 'docs/boxing-club-rounds-4.6.md']
_boxing_gap46 = '首季五关匹配、四轮原始事件/祝福、独立正式队伍、最佳回合及首次奖励防重接入；目录和占用重启恢复。固定12份来源blob/394条完整记录，305条运行绑定指纹与140个原始事件关卡核对；最终测试和原生/H2证据见build/boxing-round-verification.json。总回合预算为本服严格口径，客户端仍报告胜负；试用、第二季共鸣/可选祝福、true放弃参数、完整任务链和实际UI/输入验收待完成。'
for _boxing_request46 in (
    "GetBoxingClubInfoCsReq", "MatchBoxingClubOpponentCsReq",
    "StartBoxingClubBattleCsReq", "GiveUpBoxingClubChallengeCsReq",
):
    REVIEWED[_boxing_request46] = {"tests": _boxing_tests46 + ["src/test/java/org/hkrpg/server/gameplay/BoxingClubTrials46Test.java", "src/test/java/org/hkrpg/server/gameplay/BoxingClubSettlementRetry46Test.java", "scripts/tests/test_boxing_club_trial_sources.py", "scripts/smoke-boxing-club-trials.py", "docs/boxing-club-trials-4.6.md"], "gap": "首季原始试用/正式混队、独立试用窗口和成员类型恢复接入；进度保存失败可重试。35档配置及六份技能预设逐项核对；实际66项Java/3项Python与原生/H2边界见docs/boxing-club-trials-4.6.md，完整玩法和UI仍待完成。历史批次：" + _boxing_gap46}

_boxing_resonance_tests46 = [
    "src/test/java/org/hkrpg/server/gameplay/BoxingClubResonance46Test.java",
    "src/test/java/org/hkrpg/server/gameplay/BoxingClubResonanceTrials46Test.java",
    "scripts/tests/test_boxing_club_resonance_sources.py",
    "scripts/smoke-boxing-club-resonance.py", "docs/boxing-club-resonance-4.6.md",
]
_boxing_resonance_gap46 = "第二季七关共鸣、逐轮可选祝福、三轮实际结算、原始空奖励与98档试用混队接入，独立状态保存及正常重启恢复；原始335项绑定、前置主线和本机字段核对。192项新增Java/4项Python与原生网络边界见docs/boxing-club-resonance-4.6.md；实际UI/动作输入、完整电话/任务链、true放弃及跨文档崩溃恢复待完成。"
for _boxing_request46 in (
    "SetBoxingClubResonanceCsReq", "ChooseBoxingClubResonanceCsReq",
    "ChooseBoxingClubStageOptionalBuffCsReq",
):
    REVIEWED[_boxing_request46] = {"tests": _boxing_resonance_tests46, "gap": _boxing_resonance_gap46}
for _boxing_request46 in (
    "GetBoxingClubInfoCsReq", "MatchBoxingClubOpponentCsReq",
    "StartBoxingClubBattleCsReq", "GiveUpBoxingClubChallengeCsReq",
):
    _boxing_row46 = REVIEWED[_boxing_request46]
    _boxing_row46["tests"] = list(dict.fromkeys(_boxing_row46["tests"] + _boxing_resonance_tests46))
    _boxing_row46["gap"] = _boxing_resonance_gap46 + " 历史批次：" + _boxing_row46["gap"]

def main():
    lock = json.loads((ROOT / "references.lock.json").read_text(encoding="utf-8"))
    requests = {}
    for project in lock["projects"]:
        for name, paths in project["handlers"].items():
            row = requests.setdefault(name, {"request": name, "status": "planned", "sources": [],
                "acceptance": {"serverBehavior": False, "resourceVersion": False, "client46": False}})
            row["sources"].append({"project": project["name"], "commit": project["commit"],
                "license": project["license"], "paths": paths})
    # Native Java additions have protocol provenance when a reference has no handler.
    name = "ClockParkHandleWaitOperationCsReq"
    if name not in requests:
        requests[name] = {"request": name, "status": "planned", "nativeCommand": 7248,
            "protocolName": name,
            "sources": [{"project": "March7thHoney-OpenSource", "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083",
                "license": "AGPL-3.0", "paths": ["Proto/4.6/CmdClockParkType.proto"],
                "kind": "protocol-definition-and-local-client-static-evidence"}],
            "acceptance": {"serverBehavior": False, "resourceVersion": False, "client46": False}}
    for name in ("ParkourGetDataCsReq", "ParkourStartLevelCsReq", "ParkourEndLevelCsReq", "ParkourGetRankingInfoCsReq"):
        if name not in requests:
            requests[name] = {"request": name, "status": "planned",
                "sources": [{"project": "March7thHoney-OpenSource", "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083",
                    "license": "AGPL-3.0", "paths": ["Proto/4.6/CmdParkourType.proto"], "kind": "protocol-definition"}],
                "acceptance": {"serverBehavior": False, "resourceVersion": False, "client46": False}}
    for name, command, protocol_name in [("GetRogueShopBuffInfoCsReq", 5616, "GetRogueShopBuffInfoCsReq"),
                                          ("BuyRogueShopBuffCsReq", 5615, "NMMLGOOGHBD"),
                                          ("GetRogueShopMiracleInfoCsReq", 5675, "GetRogueShopMiracleInfoCsReq"),
                                          ("BuyRogueShopMiracleCsReq", 5656, "BuyRogueShopMiracleCsReq")]:
        if name not in requests:
            requests[name] = {"request": name, "status": "planned", "nativeCommand": command,
                "protocolName": protocol_name,
                "sources": [{"project": "March7thHoney-OpenSource", "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083",
                    "license": "AGPL-3.0", "paths": ["Proto/4.6/CmdRogueCommonType.proto"],
                    "kind": "protocol-definition-and-local-client-static-evidence"}],
                "acceptance": {"serverBehavior": False, "resourceVersion": False, "client46": False}}
    for name, command in [("GetBoxingClubInfoCsReq", 4234), ("MatchBoxingClubOpponentCsReq", 4205),
                          ("StartBoxingClubBattleCsReq", 4260), ("GiveUpBoxingClubChallengeCsReq", 4258),
                          ("ChooseBoxingClubResonanceCsReq", 4242), ("ChooseBoxingClubStageOptionalBuffCsReq", 4268),
                          ("SetBoxingClubResonanceCsReq", 4261)]:
        if name not in requests:
            requests[name] = {"request": name, "status": "planned", "nativeCommand": command,
                "protocolName": name,
                "sources": [{"project": "March7thHoney-OpenSource", "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083",
                    "license": "AGPL-3.0", "paths": ["Proto/4.6/CmdBoxingClubType.proto"],
                    "kind": "protocol-definition-and-local-client-static-evidence"}],
                "acceptance": {"serverBehavior": False, "resourceVersion": False, "client46": False}}
        requests[name]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/BoxingClub46.java"
    for path in (ROOT / "src/main/java/emu/lunarcore/server/packet/recv").glob("*.java"):
        for name in re.findall(r"@Opcodes\(CmdId\.(\w+)", path.read_text(encoding="utf-8")):
            if name in requests:
                requests[name]["status"] = "source-present-review-required"
                requests[name]["javaEvidence"] = path.relative_to(ROOT).as_posix()
    # This command is implemented directly by the new version adapter.
    gameplay = (ROOT / "src/main/java/org/hkrpg/server/protocol/Gameplay46.java").read_text(encoding="utf-8").split("public static boolean handle",1)[0]
    for name in re.findall(r'"(\w+CsReq)"', gameplay):
        if name in requests:
            adapter = "Music46" if name.startswith("MusicRhythm") else "Message46" if name in {
                "GetNpcStatusCsReq", "GetNpcMessageGroupCsReq", "GetMissionMessageCsReq", "FinishItemIdCsReq",
                "FinishSectionIdCsReq", "FinishPerformSectionIdCsReq", "FinishMultiPerformSectionIdCsReq"} else "Gameplay46"
            requests[name]["javaEvidence"] = f"src/main/java/org/hkrpg/server/protocol/{adapter}.java"
    for name in ["TakeQuestRewardCsReq", "ContentPackageGetDataCsReq"]:
        if name in requests:
            requests[name]["status"] = "partial"
            requests[name]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/VersionedPacketHandler.java"
    for name in ("ClockParkGetInfoCsReq", "ClockParkStartScriptCsReq", "ClockParkGetOngoingScriptInfoCsReq",
                 "ClockParkQuitScriptCsReq", "ClockParkHandleWaitOperationCsReq"):
        if name in requests:
            requests[name]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/ClockPark46.java"
    for name, adapter in {"MarkReadMailCsReq": "Mail46", "TakeMailAttachmentCsReq": "Mail46", "DelMailCsReq": "Mail46", "GetExpeditionDataCsReq": "Expedition46", "AcceptMultipleExpeditionCsReq": "Expedition46", "TakeMultipleExpeditionRewardCsReq": "Expedition46", "ReplaceLineupCsReq": "VersionedPacketHandler", "PVEBattleResultCsReq": "BattleResult46", "InteractPropCsReq": "SceneInteraction46", "GetUnlockTeleportCsReq": "SceneMap46",
                          "GetSceneMapInfoCsReq": "SceneMap46", "EnterSceneCsReq": "SceneMap46",
                          "GetFirstTalkByPerformanceNpcCsReq": "FirstTalk46",
                          "FinishFirstTalkByPerformanceNpcCsReq": "FirstTalk46",
                          "GetRogueTalentInfoCsReq": "RogueTalent46",
                          "EnableRogueTalentCsReq": "RogueTalent46",
                          "ReviveRogueAvatarCsReq": "RogueRevival46",
                          "GetRogueBuffEnhanceInfoCsReq": "RogueEnhancement46",
                          "EnhanceRogueBuffCsReq": "RogueEnhancement46",
                          "GetRogueShopBuffInfoCsReq": "RogueBuffShop46",
                          "BuyRogueShopBuffCsReq": "RogueBuffShop46",
                          "GetRogueShopMiracleInfoCsReq": "RogueMiracleShop46",
                          "BuyRogueShopMiracleCsReq": "RogueMiracleShop46",
                          "PickRogueAvatarCsReq": "RogueDownload46",
                          "ReplaceLineupCsReq": "RogueLineup46",
                          "ParkourGetDataCsReq": "Parkour46",
                          "ParkourStartLevelCsReq": "Parkour46",
                          "ParkourEndLevelCsReq": "Parkour46",
                          "ParkourGetRankingInfoCsReq": "Parkour46",
                          "StartRaidCsReq": "Raid46",
                          "LeaveRaidCsReq": "Raid46",
                          "GetRaidInfoCsReq": "Raid46",
                          "GetSaveRaidCsReq": "Raid46",
                          "GetAllSaveRaidCsReq": "Raid46",
                          "ComposeSelectedRelicCsReq": "SelectedRelic46",
                          "UseItemCsReq": "ItemUse46",
                          "GetStoryLineInfoCsReq": "StoryLine46",
                          "GetHeartDialInfoCsReq": "HeartDial46",
                          "SetPlayerOutfitCsReq": "PlayerOutfit46",
                          "DiceCombatFinishPveStageCsReq": "DicePve46",
                          "DiceCombatMainPageRollDiceCsReq": "DiceMainPageRoll46",
                          "DiceCombatUpgradeAvatarCsReq": "DiceProgression46",
                          "DiceCombatModifyAvatarDiceCsReq": "DiceProgression46",
                          "SubmitEmotionItemCsReq": "HeartDial46",
                          "ChangeScriptEmotionCsReq": "HeartDial46",
                          "FinishEmotionDialoguePerformanceCsReq": "HeartDial46",
                          "SetTrainWorldIdCsReq": "TrainWorld46",
                          "CycleScoreRewardGetDataCsReq": "CycleScore46",
                          "CycleScoreRewardTakeRewardCsReq": "CycleScore46",
                          "CycleScoreRewardDestroyExceedRewardCsReq": "CycleScore46"}.items():
        if name in requests:
            requests[name]["javaEvidence"] = f"src/main/java/org/hkrpg/server/protocol/{adapter}.java"
    for name in ["GetRogueInfoCsReq", "HandleRogueCommonPendingActionCsReq", "EnterRogueMapRoomCsReq",
                 "StartRogueCsReq", "EnterRogueCsReq", "LeaveRogueCsReq", "QuitRogueCsReq"]:
        if name in requests:
            requests[name]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/Universe46.java"
    if "GetRogueHandbookDataCsReq" in requests:
        requests["GetRogueHandbookDataCsReq"]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/RogueHandbook46.java"
    if "TakeRogueMiracleHandbookRewardCsReq" in requests:
        requests["TakeRogueMiracleHandbookRewardCsReq"]["javaEvidence"] = "src/main/java/org/hkrpg/server/protocol/RogueHandbookReward46.java"
    for name in ("StartWolfBroGameCsReq", "GetWolfBroGameDataCsReq", "ArchiveWolfBroGameCsReq",
                 "RestoreWolfBroGameArchiveCsReq", "QuitWolfBroGameCsReq"):
        row = requests.setdefault(name, {"request": name, "sources": []})
        row["sources"].append({"project": "March7thHoney-OpenSource",
            "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083", "license": "AGPL-3.0",
            "paths": ["Proto/4.6/CmdWolfBroType.proto"], "kind": "protocol-definition"})
        row.update(status="partial", acceptance={"serverBehavior": True, "resourceVersion": True, "client46": False},
            javaEvidence="src/main/java/org/hkrpg/server/protocol/WolfBro46.java",
            testEvidence=["src/test/java/org/hkrpg/server/protocol/WolfBro46Test.java", "src/test/java/org/hkrpg/server/protocol/WolfBroRestore46Test.java", "src/test/java/org/hkrpg/server/protocol/WolfBroControl46Test.java", "src/test/java/org/hkrpg/server/protocol/WolfBroTransitionComments46Test.java", "scripts/smoke-wolfbro-lifecycle.py"],
            gap="原始关卡3身份、坐标与原始控制组状态1/2/0持久化；存档捕获控制状态，恢复位置/旋转/控制后保存，失败回退，同状态重复不重写。旧存档不猜控制快照、损坏/矛盾保留拒绝。新增28项控制回归、13项行为先失败，真实KCP控制查询13次/请求4次/场景通知7次及正常H2恢复。弹药、完整物件恢复、模型变身、完整脚本及游戏UI未完成。其他16关未逐关联网验收。见docs/wolfbro-control-4.6.md。")
        for evidence in row["testEvidence"]:
            if not (ROOT / evidence).is_file():
                raise SystemExit(f"验证证据文件缺失: {evidence}")
    for name in ("DifficultyAdjustmentGetDataCsReq", "DifficultyAdjustmentUpdateDataCsReq"):
        row = requests.setdefault(name, {"request": name, "sources": []})
        row["sources"].append({"project": "March7thHoney-OpenSource",
            "commit": "fe00bdbda3c28ca33d01f171566aa35b5ab95083", "license": "AGPL-3.0",
            "paths": ["Proto/4.6/CmdMiscModuleType.proto"], "kind": "protocol-definition"})
        row.update(status="partial", acceptance={"serverBehavior": True, "resourceVersion": True, "client46": False},
            javaEvidence="src/main/java/org/hkrpg/server/protocol/RaidDifficulty46.java",
            testEvidence=["src/test/java/org/hkrpg/server/gameplay/RaidDifficultyAdjustment46Test.java", "src/test/java/org/hkrpg/server/gameplay/DefaultWorldOneRaid46Test.java", "src/test/java/org/hkrpg/server/gameplay/UnselectedWorldOneRaid46Test.java", "src/test/java/org/hkrpg/server/gameplay/HigherWorldRaidDifficulty46Test.java", "scripts/smoke-default-entry-mission-raid.py", "docs/raid-difficulty-adjustment-4.6.md", "docs/default-world-one-raid-4.6.md", "docs/unselected-world-one-raid-4.6.md", "docs/higher-world-raid-difficulty-4.6.md", "src/test/java/org/hkrpg/server/gameplay/StageEventDifficulty46Test.java", "scripts/smoke-stage-event-difficulty.py", "docs/stage-event-difficulty-4.6.md"],
            gap="原生4193/4173、4103/4129及ID8/来源11/难度12有本机静态证据；仅原始43306/source1/实际世界1至6，显式Default及合法空偏好冻结实际世界，Easy冻结实际世界减1，试用成长仍使用实际世界原件；实际NPC/事件战斗、暂停改选及正常持久化恢复。31项菜单/Easy、8项显式Default、15项未选择/损坏集合及75项更高世界Java；11份完整原件/29条记录和逐模式原生及源码边界见docs/higher-world-raid-difficulty-4.6.md。另接入原始20003011/source2普通事件的世界1至6默认/简单及空偏好，50项Java、三份完整原件/15条记录和75次候选原生/三次正常H2重启见docs/stage-event-difficulty-4.6.md；其他source2事件/分组、其他副本及范围外世界、完整官方规则、剧情演出与游戏UI仍待完成。")
    for name in ("DifficultyAdjustmentGetDataCsReq", "DifficultyAdjustmentUpdateDataCsReq"):
        row = requests[name]
        row["testEvidence"].extend([
            "src/test/java/org/hkrpg/server/gameplay/SingleStageEventDifficulty46Test.java",
            "docs/single-stage-events-4.6.md"])
        row["gap"] = (
            "原始28个source2单事件及196个世界关卡保存独立偏好；查询排序、损坏集合整份拒绝，"
            "20321050在43306副本沿用source1已冻结难度。565项新增Java、三份完整原件/420条记录，"
            "两份源码各6595项Java与132项Python、最终产物共2845次原生请求通过；"
            "完整集合和正式队伍经正常H2恢复。14个分组语义、NPC入口、完整剧情/宇宙/活动和UI待完成。"
            "见docs/single-stage-events-4.6.md。历史验证范围：" + row["gap"])
    for name in ("DifficultyAdjustmentGetDataCsReq", "DifficultyAdjustmentUpdateDataCsReq"):
        row = requests[name]
        row["testEvidence"].extend([
            "src/test/java/org/hkrpg/server/gameplay/GroupedStageEventDifficulty46Test.java",
            "docs/all-stage-event-difficulty-4.6.md"])
        row["gap"] = (
            "原始42条目/57个实际EventIDs分别保存偏好，14个分组的29事件不互借选择；"
            "原始顺序、唯一归属、七档关卡及整份存档核对，旧单事件来源指纹兼容。"
            "593项新增Java先失败368项，三份完整Git证明/840条原始记录、四种57偏好各两次Player恢复。"
            "原生和独立源码以同SHA完成报告为准；NPC放置/入口、自然剧情、完整能力及游戏UI仍待完成。"
            "见docs/all-stage-event-difficulty-4.6.md。历史验证范围：" + row["gap"])
    panel_review = REVIEWED["GetActivityScheduleConfigCsReq"]
    panel_review["tests"] += [
        "src/test/java/org/hkrpg/server/gameplay/ActivityPanelUnlock46Test.java",
        "scripts/smoke-activity-panel-unlock.py", "docs/activity-panel-unlock-4.6.md"]
    panel_review["resourceVersion"] = True
    panel_review["gap"] = (
        "原始10061三分支和10025 QuestClose接入实际记录；|任选、&/逗号全部满足，"
        "完整预检拒绝未知/空分支/null/溢出。52项Java含12种各两次实际Player恢复，"
        "七份完整Git证明/10条原始记录及本机表达式/状态3静态证据。"
        "两份源码各6647项Java/132项Python与最终产物共40次原生请求、各三次正常H2重启通过；"
        "前置CLOSED完成状态与运营窗口为明确隔离夹具。"
        "完整活动玩法、剧情/奖励取得和游戏UI仍未完成；见docs/activity-panel-unlock-4.6.md。"
        "历史验证范围：" + panel_review["gap"])
    lottery_note = '银河大乐透63取得、物件抽奖收据、获奖奇物UUID、99%当前生命损失、修复保留防重与正常Player恢复接入。5%获奖/5%损毁/奖池10004/取整为明确项目规则；34项Java含7项真实先失败、四种各两次Morphia恢复，候选48次KCP/30检查/3次正常stop/2次同UID重启/3份H2双读及2274输入SHA不变。完整双源码同产物验证见build/rogue-galactic-lottery-verification.json；真实UI、官方概率/奖池和崩溃原子性待验证，见docs/rogue-galactic-lottery-4.6.md。'
    for request in ["SceneCastSkillCsReq", "GetRogueInfoCsReq", "GetRogueShopMiracleInfoCsReq", "BuyRogueShopMiracleCsReq"]:
        review = REVIEWED[request]
        review["gap"] = lottery_note + " 历史记录：" + review["gap"]
        for evidence in ["src/test/java/org/hkrpg/server/gameplay/RogueGalacticLottery46Test.java", "scripts/smoke-rogue-galactic-lottery.py", "docs/rogue-galactic-lottery-4.6.md"]:
            if evidence not in review["tests"]: review["tests"].append(evidence)
    living_review = REVIEWED["SceneCastSkillCsReq"]
    living_review["gap"] = '存活施法预检追加：4.6与继承入口在扣费/执行前拒绝当前阵容死亡角色；20项新增回归含8项实际先失败，相邻44项通过，实际正式8001构造结算状态后核对死亡拒绝/存活施法、点数和通知。普通登录队长20%恢复为既有规则；最终完整双源码构建与原生/H2验证见build/maze-living-caster-verification.json，边界见docs/maze-living-caster-4.6.md。构造报告不等于官方输入回放，完整战斗/剧情和真实UI继续partial。' + " 历史记录：" + living_review["gap"]
    for evidence in ["src/test/java/org/hkrpg/server/gameplay/MazeLivingCasterPreflightTest.java", "scripts/smoke-maze-living-caster.py", "docs/maze-living-caster-4.6.md"]:
        if evidence not in living_review["tests"]: living_review["tests"].append(evidence)
    hp_review = REVIEWED["SceneCastSkillCsReq"]
    hp_review["gap"] = '当前生命比例条件追加：显式AdventureByCompareHPRatio/Caster使用当前队伍归一化生命，六种固定浮点比较/反向/复用/组合接入；生命与MP在同一玩家锁读取一次，不借用普通队伍的正式角色生命。缺失身份/越界存档/未知目标与表达式不选择分支。45项新增Java含26项实际先失败、四种各两次Player与Avatar Morphia读取，相邻156项及三项来源Python检查；原始阿那克萨完整监听任务与64字节本机构造器/原生R4字段保留。最终双源码及存活施法/H2等回归见build/maze-hp-predicate-verification.json。判断器显式验证，不证明原始Fear/目标列表/监听已执行或官方求值与真实UI；完整战斗剧情宇宙活动仍partial，见docs/maze-hp-predicate-4.6.md。' + " 历史记录：" + hp_review["gap"]
    for evidence in ["src/test/java/org/hkrpg/server/gameplay/MazeHpPredicateTest.java", "src/test/resources/fixtures/maze-hp-predicate-4.6.json", "scripts/tests/test_maze_hp_predicate_sources.py", "docs/maze-hp-predicate-4.6.md"]:
        if evidence not in hp_review["tests"]: hp_review["tests"].append(evidence)
    for name, review in REVIEWED.items():
        if name in requests:
            row = requests[name]
            row["status"] = "partial"
            row["acceptance"]["serverBehavior"] = True
            if "resourceVersion" in review:
                row["acceptance"]["resourceVersion"] = review["resourceVersion"]
            row["testEvidence"] = review["tests"]
            row["gap"] = review["gap"]
            for evidence in review["tests"]:
                if not (ROOT / evidence).is_file():
                    raise SystemExit(f"验证证据文件缺失: {evidence}")
    catalog = {"designTarget": "4.6.0", "referenceSnapshot": lock["snapshotDate"],
        "note": "名称合并仅提供逐项移植入口；未审查的源码入口不计为已实现玩法，客户端验收均待完成。",
        "entries": [requests[name] for name in sorted(requests)]}
    output = ROOT / "src/main/resources/feature-backlog.json"
    output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# 全部参考项目的逐项补全清单", "", f"目标 4.6.0；固定参考实现与本地新增原生入口合计 {len(requests)} 项；新增跑酷、祝福/奇物商店和剧情难度等入口记录协议或本机静态证据来源，不算参考处理器。", "",
        "来源包含用户指定的八个项目和 LunarCore development 补充参考。名称、接口和版本可能并不等价；每项均需验证配置、状态、正常/异常流程、领取防重、重启和客户端表现。", "",
        "`源码待审查` 包括继承实现和占位响应，不能当作已完成。主功能状态见 [features.md](features.md)，协议差异见 [protocol-4.6.md](protocol-4.6.md)。", "",
        "已审查项记录服务端测试证据；这不会自动通过资源语义或客户端验收。登录、初始数据、任务与心跳当前均为部分实现，边界见 [verification.md](verification.md)。", "",
        "| 请求入口 | 当前情况 | 参考项目 | Java 入口 | 服务端证据 |", "|---|---|---|---|---|"]
    labels = {"planned": "待实现", "source-present-review-required": "源码待审查", "partial": "部分实现"}
    for row in catalog["entries"]:
        sources = "、".join(source["project"] for source in row["sources"])
        evidence = row.get("javaEvidence", "")
        link = f"[{Path(evidence).name}](../{evidence})" if evidence else "—"
        tests = "、".join(f"[{Path(path).name}](../{path})" for path in row.get("testEvidence", [])) or "待审查"
        lines.append(f"| `{row['request']}` | {labels[row['status']]} | {sources} | {link} | {tests} |")
    (ROOT / "docs/feature-backlog.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Generated {len(requests)} individually traceable request entries")


if __name__ == "__main__":
    main()
