# 剧情目标中的实际试用角色校验

Java 核心现在能够识别已提供到当前队伍的试用角色，并推进对应的简单 GetTrialAvatar 目标。这一批没有接入自动创建、加入、替换和销毁角色的全生命周期，功能仍标记为部分支持。协议、命令号和版本声明保持冻结。

## 原始数据与支持范围

数据固定在 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。夹具保留十条完整原始子任务、十份原始脚本、对应世界等级零的 SpecialAvatar 行和原始角色行；22 份完整来源文件的 SHA256 单独记录。实际安装资源与来源只归一化 CRLF/LF 后比较，内容必须一致。

| 原始子任务 | 单一声明任务 | 已提供角色时的校验 |
|---|---|---|
| 100010110、102040213、102040214、102140143、200070318 | CreateTrialPlayer | 当前队伍包含对应真实试用角色 |
| 103600414 | AddTrialPlayer | 当前队伍包含对应真实试用角色 |
| 102140142 | ReplaceTrialPlayer | 当前队伍仅保留目标试用角色 |
| 104020708、104021212 | ReplaceVirtualTeam | 实际临时队伍仅保留目标试用角色 |
| 103420827 | ReplaceVirtualTeam 与 ClientFinishMission | 保持待完成，不能略过后续动作 |

参考 March7thHoney-OpenSource 的固定 AGPL-3.0 提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，读取 `GameServer/Game/Task/LevelTask.cs` 中创建、替换和临时队伍分支，以及 GetTrialAvatar 的完成处理。使用自有 Java 校验，没有导入参考仓库运行时代码。参考处理器直接完成任务的行为没有作为本项目完成证据。

`TrialAvatarMissionConditions` 要求完整脚本结构只有已识别的单一任务；未知字段、初始化动作、额外任务、非数组字段和空类型均拒绝。还要求任务原始参数形状、当前位面及楼层、实际已接取的父子任务、有效 DOING 阶段和未取消状态。完成前必须核对临时角色缓存绑定、玩家对象及 UID 归属、SpecialAvatar 与角色表身份、性别及当前队伍成员和队长槽位。正式角色表中的同 ID 项不能冒充临时角色。

校验本身不创建角色、修改队伍、保存玩家、执行动作或发奖。只有已经存在的真实角色和队伍可以提供条件证据，随后由既有任务管理器推进一次。未经额外动作支持的脚本保持待处理。

## 验证与尚未完成的部分

新增 54 项 Java 回归。原逻辑有九项有效目标不推进的真实断言失败；脚本结构检查有四项异常的真实断言失败；队伍与替换语义有七项真实断言失败。早期资源夹具初始化错误和探针初始化错误不计入行为回归证据。覆盖原始九条简单目标、混合脚本拒绝、取消和损坏存档、缺失或异主角色、缺失资源、错误场景、错误队长、并发刷新与重复调用。

单元夹具显式预置父子任务、当前位置和当前队伍，并以隔离技能计划检查角色身份；没有自然完成所有官方前置。另有独立进程检查读取实际安装的资源，通过生产 TrialManager 构建十个真实试用角色，使用实际等级、晋阶、技能预设、技能树及装备，核对核心编队和战斗消息。九个简单条件通过，混合脚本继续待完成。检查前后核对 2,282 份输入资源的完整字节 SHA256，不修改资源或运营数据。该检查仍手动发布隔离队伍，不是自动接入、客户端入口或游戏 UI 验收，也不是本批新增 UDP/KCP 正向流程。

```powershell
./scripts/build.ps1
python -s -X utf8 scripts/probe-provided-mission-trials.py
python -s -X utf8 scripts/audit-gameplay.py --output build/provided-mission-trials-gameplay-audit.json
python -s -X utf8 -m unittest discover -s scripts/tests -p 'test_*.py'
```

当前文件引用缺口仍为零，GetTrialAvatar 仅进入部分支持集合；还存在 103 类未实现 FinishType 和两类非空 FinishActionType。试用角色自动创建与发布、持久化和清理、GetTrialAvatarList、DelTrialAvatar、混合脚本动作以及完整剧情、日常、宇宙、小游戏和真实客户端验收继续补齐。不能把已提供角色的条件校验算作全剧情试用功能完成。
