# 探索直接击败的场景与标签限制

直接击败动作现在检查当前场景、实际注册怪物、Minion/MinionLv2 等级、挑战场景和原始放置标签。挑战场景及 `ResistInstantKill` 放置保持存活，不移除实体、不计算掉落、不发布死亡或推进击杀任务。混合目标只处理允许击败的实际怪物，重复目标仍只计一次。

`MonsterInfo` 保留原始 `TagList`；省略字段按空列表保持既有资源行为，明确 null、未知标签或含 null 的列表关闭直接击败。重复的已知标签等同同一标记。只接受本机 EntityTag 的六个已核对名称；未识别的新标签不能被忽略后照常击败。这里使用运行时 `PlaneType.Challenge` 名称，不混用客户端 GameModeType 数值：Java Raid 的 6 并不是客户端 RogueChallenge 的 6。

正常战斗胜利沿用独立的死亡路径。抗瞬杀和挑战限制没有加到通用 `Scene.defeatMonster`，真实接受的胜利仍能移除怪物并触发死亡；失败和退出保持怪物存活，重复结算不重复计数。

## 来源和验证边界

固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 中 P44202 的 F44202002/G3 和 F44202003/G3 各有一处完整原始抗瞬杀放置，分别对应 8001010/Minion 与 2002030/MinionLv2。夹具保留两条完整放置、两条完整 NPC 记录、安装与原始 Git blob SHA256，没有删去不支持的字段。

已只读检查固定国服 4.6 DLL 和元数据。`AdventureStatic.TargetAllowInstantKill` 的 559 字节完整 PE 函数检查 NPC Rank、当前模式 4 及 EntityTag 的位 8；7 字节 `MapDef.get_CurrentGameMode` 独立确认模式字段偏移，1006 字节 `AdventureCharacterDataComponent.GetEntityTag` 保留完整 PE 范围。元数据确认 Challenge=4、ResistInstantKill=3。未运行 DLL 或修改客户端。NPC 的 ConfigCharacter/ConfigEntity 文件仍缺失，额外角色配置标签没有得到完整映射，因此该限制不等于官方全部瞬杀资格。

新增 30 项 Java 行为回归，11 项实际断言先失败后修复，覆盖两个未改写原始放置、挑战、非法标签、正常标签、不同场景名称、混合目标和击杀任务，以及六种普通战斗胜负/退出组合。最初缺少 import 的编译失败不计作行为回归。相邻 25 项场景死亡检查一起通过；三项 Python 检查核对来源、完整函数字节与未完成边界。测试场景和战斗报告是明确组装的夹具，没有把测试当作官方输入或游戏 UI 验收。

`ByAllowInstantKill` 条件分支、全部角色的完整动作、额外角色标签、剧情/宇宙/活动及真实客户端 UI 继续逐项补齐，功能保持 partial。

本批最终验证见 uild/maze-instant-kill-verification.json：61 项新增 Java、24 项真实断言先失败，完整 8258 项/341 类和181 项 Python 通过。目录文本重打包只改变 features.json，HTTP 复验通过；同产物既有 51 次 KCP/36 检查、3 次正常 stop、2 次同 UID H2 恢复及各六次 Player/Avatar 实际读取通过，2254 输入资源哈希不变。网络检查并未正向执行本批原始瞬杀分支，完整玩法和游戏UI仍待完成。
