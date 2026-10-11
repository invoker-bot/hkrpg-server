# 卡斯托里斯原始 Free Input 有限流程

本批接通 1407 的 `LocalPlayer_Castorice_MazeSkill_Input`：实际基础秘技已释放、20 秒原始范围 buff 仍有效时，新的 Ability UseID 可以通过 1460 的原始名称字段和有序 `[1,2]` 标签进入实际战斗。Input 不额外扣秘技点；成功后移除本角色的 140704。战斗 buff 快照保留其原始 `OnBattle` 添加的 140701，并排除本次即将移除的 140704。

这只覆盖原件可以闭合的直接攻击与清理流程。原始 `Out` 分支、召唤物创建与移动、距离检测、镜头、动画以及实际游戏客户端操作没有完成验收。

## 原件与时序

来源固定为 `Dimbreath/TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。完整夹具位于 `src/test/resources/fixtures/maze-castorice-manual-ability-4.6.json`，包含 12 个完整 blob、逐件 SHA256 和字节数。采用 `zlibBase64` 无损存储，解压后的原始字节与实际失败基线一致，没有重新序列化原始 JSON。原始未压缩夹具为 15,772,338 字节，交付夹具为 387,969 字节；交付文件 SHA256 为 `2a77f5ded624778f828a33febd8908a876f817cfa82cda518d0f8955a7706f93`。

| 原件位置 | 确定的行为 |
| --- | --- |
| `LocalPlayer_Castorice_00_Ability.json /AbilityList/1/OnStart/0` | 基础秘技给实际 caster 添加 140704；寿命来自动态参数哈希 `-1733965089`。 |
| `LocalPlayer_Castorice_00_Config.json /DynamicValues/Floats/-1733965089/ReadInfo` | 原值为 `SkillParam / SkillMaze / Index 2`。完整 140707 等级 1 参数行第 2 项为 20；LD 表没有覆盖。 |
| `GlobalModifiers/ADV_StageAbility_Maze_Castorice_Radius/ModifierTaskList/0` | 原 `WaitSkillInput` 核对允许使用技能，其 `OnInput` 触发原始 Input 名称。 |
| `AbilityList/3` | 原 `ManualTriggerType=Free`，没有投射物声明。 |
| `AbilityList/3/OnStart/14` | 原 `AdventureTriggerAttack` 没有 `CostMP` 或 `CostMpPhaseType`；其 `OnBattle/4` 添加 140701。 |
| `AbilityList/3/OnStart/18` 与 `/20` | 在原 Attack 后移除 140704，并声明销毁召唤单位 14071；本批复用已有可执行清理动作，未实现完整召唤生命周期。 |

原始本机 4.6 发送器证明无 `LastFinishProjectile` 的直接攻击按 `[1,2]` 顺序发送；原始 Manual Free 分配新的 Ability UseID，经投射物或目标上下文快照写入 1460 field13，能力名写入 field15。此处依赖已经冻结的 `scripts/fixtures/maze-cast-sequence-native-4.6.json`，SHA256 为 `987b779207b2d8745404d5f35ec2adf1dcff94a2d6aea2326a9f11054231b8f0`。它是原始调用链证据，没有采集官方客户端的本角色请求。

原始 LocalPlayer 的 `MazeSkill` 行省略 `SkillCostMpPhase`，本批没有据此推断官方基础秘技的 1408 付款阶段。回归使用现有 1460 基础 `NONE` 释放入口，仍按已有兼容行为消费一次基础 MP，并明确验证它不是预付收据。Input 的 Free 不借用基础 UseID 的付款，也不制造 1408 请求。

140704 原表没有 `UseType`，140701 的原值是 `TriggerBattle`。原 Input 的 140701 未写 `LifeTime`，现有 Java 添加动作的默认寿命属于兼容实现；不能把它称为官方 140701 寿命或一次战斗消费规则。

## 接入与失败边界

入口同时核对当前活动 session、同一个实际 Scene、实际存活且持有的 caster、当前队伍、真实基础能力释放记录，以及 140704 的相同快照与剩余寿命。单独写入同 ID buff、刷新其到期时间、切换场景、重登、错误 caster、旧基础 UseID、未知名称以及旧已释放 Input 都不能获得授权。成功 Input 清除原来源后，新 UseID 需要新的实际基础释放。

仅接受此有限直接攻击的有序 `[1,2]`。单项 HIT、单项 END、反序、重复标签和未知枚举均拒绝；非法请求不能先消耗后续合法同 UseID 的授权。实际 NPC 目标交给现有 BattleService 生成原波次，不替换为离线战斗模拟。

基础范围 buff 的移除在实际战斗准入成功后执行。为保持原始战斗快照，新的 opt-in 激活挂钩只排除本次 caster 的 140704，不能传任意 modifier ID，不能屏蔽其他角色或 140701。普通旧调用不启用该挂钩。

已锁定并验证的失败路径包括：真实 Stage 准入失败、BattleService 返回失败、激活前挂钩抛异常，以及实际 `Player.setBattle → prepareAvatarBuffs` 读取失败。这些失败保留原 140704 到期值、清除本次刚创建的 battle 指针、恢复本 caster 的受影响 buff 和临时能力元数据，使同一合法 UseID 可以重试。这里没有证明所有 Scene、实例回调或后续异常的完整事务回滚；`Scene.onBattleStart(actual)` 可能已经移除召唤单位并发送通知。

## 资源闭包

运行入口每次完整读取并核验八件原件的物理路径与 SHA，只允许 CRLF→LF 的行尾转换。固定 blob 的解析树可以复用，完整字节核验仍逐次执行。只有新 1407 来源契约允许完整 10,833,839 字节 `AvatarSkillConfig` 表，大小上限 16,000,000；旧 1510、昔涟的来源门槛不变。

本轮没有安装或改写运营资源。当前运营资源缺少以下完整原 LocalPlayer，它是动态 20 秒绑定的必要证据；缺失时有限 Input 必须拒绝，不从 ignored `references/` 回退读取：

`Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json`，3,481 字节，SHA256 `b35fc7cf48389440eccec5dca1377d0aa36861a65c36ad9de3b3f8f6f48c2caa`。

完整 Avatar 和 Manikin 原件只随受控夹具保留来源，有限运行入口不要求安装。完整召唤原件与表也保留在夹具中，未把来源齐备等同于召唤、物理或演出完成。

## 验证记录

不可变基线为 `build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256 `258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1`。Java 21 独立 JUnit runner 排除当前 main 输出，并核对所有已加载项目类的 CodeSource。最终 40 项中有 19 项纯 `AssertionFailedError`，21 项对照通过，没有编译或夹具异常冒充行为失败；实际缺口为合法 Input 返回 5。记录保留在 `build/battle-cast-draft/manual-free-castorice-v1/immutable-red-v5/verification.json`。

最初直接清理的实现使两个实际战斗快照断言失败，记录为 `owned-green-v2/verification.json`。随后激活前清理原型在三个异常/重试回归中有两个失败，记录为 `activation-boundaries-red-v1/verification.json`；这属于原型的失败边界回归，不能算不可变基线的新增功能 RED。最终实现先限定快照排除，成功后再实际释放和清理。

交付版完整压缩夹具通过 classpath 默认路径实际运行：40 项 `MazeCastoriceManualAbility46Test`、3 项 `MazeCastoriceActivationFailure46Test` 及 827 项旧 Maze 回归，共 870 项全部通过。报告为 `owned-green-v5/verification.json`，逐类来源及 owned 输入前后 SHA 一致。已导入四个 owned 生产文件、两个测试和夹具；共享 Battle/BattleService 的两个窄挂钩由根代理审核接入，交付代码与该独立 GREEN 输入逐字一致。

根代理随后完成正式专项 51 类 / 1,308 项与全量 512 个测试套件 / 13,954 项，失败、错误和跳过均为零；2,037 份编译与资源输入前后稳定。实际新包为 `build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`，专项、全量与下述网络验证使用同一产物。

## 新包实际 KCP/H2 验证

`build/maze-castorice-ability-current-native.json` 与同名 `.log` 保留首轮成功，报告 SHA256 `1e4ebaa85b577f42a8ae2b2b661647e3cd32efbfaca182cf8eb0a2b41c49c984`。独占目录为 `build/smoke/maze-castorice-ability-1xk48ydr`，累计 294 次实际请求、330 项检查、3 次正常停服、2 次同 UID H2 进程恢复。三个 snapshot 的请求数为 227/36/31，均严格等于逐条 `observedReplies` 数量；检查包括每个实际进程中的 schema 核对，不将它们额外计为请求。

实际运行只在隔离账号使用 `give 1407` 获取正式角色，采用隔离 P20001/F20001001/G91 的受控 NPC 与补给摆放；原能力、原 Stage80030231 与完整波次未改，也未预写 UseID、buff、付款或胜利存档。七次实际基础 NONE 释放保持既有未预付扣点兼容，从未发送 1408，官方基础付款 phase 仍未知。

三场实际 NPC 战斗入战 packet 均保留 140701、排除 140704。成功 Input 的实际 140704 移除通知为 4/1/0 条，包括一次空目标清理；失败请求没有额外 MP 或移除通知。真实缺 Stage 准入失败后，同 Input UseID 换到合法原 Stage 重试成功；旧 Input 不能借新的基础来源，MP0 Free 输入、错误标签/身份、实际换场景与新 session 的拒绝通过。

原始 20 秒来源采用 21 秒真实墙钟等待，每段以实际只读 `GetCurLineupDataCsReq` 保持 KCP 活跃，心跳全部计入实际请求和 reply。第 125 请求后仅暂时移走隔离 LocalPlayer 原件，第 131 请求后恢复相同字节，有限 Input 先拒绝再同 UseID 成功。原完整能力未植入计数 marker。

每次正常停服后完成三次真实 Morphia Player 与三次 Avatar 独立读取，总计 9+9，MP 存档为 1/0/0。临时 UseID/origin 账本、buff 与能力元数据未持久化；每次 H2 检查均核对实际 `resourceDir` 和 12 份完整原件的物理闭包。额外完整 MultiplePath 表使启动前隔离物理件总数为 13。运营 LocalPlayer 仍缺，运营 resources/config/database 均未修改。

可复现工具为 `scripts/smoke-maze-castorice-ability.py`、`scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeCastoriceAbilityProbe.java`、`scripts/fixtures/java/org/hkrpg/server/gameplay/MazeCastoriceAbilityFixtureInspector.java` 与 `scripts/tests/test_smoke_maze_castorice_guard.py`。当前导入由根代理执行；审核副本及 add-only patch 位于 `build/battle-cast-draft/castorice-native-v1/delivery-layout-v2`。两个 Java helper 逐字等于本次实际执行源码，Python 只适配路径布局；晋级版再次通过 13 项 guard 与同 f586 上 Java21/18 份 schema descriptor-only 预检，该预检没有另启服务器。原已执行工具仍按 `native-tool-freeze-v1.json` 保留，不覆盖旧失败复制原型或旧 258b 的纯 schema 记录。

旧批网络成功不计入本次新增流程。这里验证了服务器的实际 handler、原入战信息和正常存储恢复；没有采集官方客户端本角色请求，也未声称客户端 UI、物理、召唤创建或完整演出已验收。前述 Scene/instance 任意后续副作用的全面事务回滚边界不因本次成功扩大。
