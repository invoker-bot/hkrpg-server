# 4.6 迷宫攻击的入战与投射物回调

本批让实际 `SceneCastSkillCsReq` 入口区分投射物命中、投射物寿命结束和普通攻击，并在战斗通过真实 stage/story 准入后执行 `OnBattle`。运行代码继续使用 Java 21 和 LunarCore 的 `emu.lunarcore` 包与 AGPL 来源；协议仍是项目的实验性 4.6 配置，未更改 `GameConstants.VERSION` 或生成协议类。

4.6 原始资源提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。固定回归 fixture 保留 8007 的完整 `LocalPlayer_PlayerBoy_30_NormalAtk01`、1310 的完整 `LocalPlayer_Firefly_00_MazeSkill` 和 1009 的原始普攻及角色记录，每条记录包含源文件 SHA256。1009 的差异回调测试明确选取其原始投射物节点，并使用可区分的测试叶。未知任务及未知条件仍按既有支持范围处理；声明扫描只确认同一角色所选技能确实拥有投射物，不授予未知条件的成功叶。

| 实际请求或阶段 | 本批行为 |
| --- | --- |
| 无 `skill_extra_tags` 或单个 `SCENE_CAST_SKILL_NONE` | 保留普通请求流程，不执行投射物命中或结束回调 |
| 单个 `SCENE_CAST_SKILL_PROJECTILE_HIT` | 执行所选技能自己的 `OnProjectileHit` |
| 单个 `SCENE_CAST_SKILL_PROJECTILE_LIFETIME_FINISH` | 执行所选技能自己的 `OnProjectileLifetimeFinish` |
| 重复、互相冲突、未知事件，或角色技能没有原始投射物声明 | 在 MP 消耗和施放效果前拒绝 |
| 攻击执行完，但没有成功准入的战斗 | 丢弃该请求的待执行 `OnBattle` |
| stage/story 准入成功 | 执行本次实际攻击分支留下的 `OnBattle`，随后冻结角色入战效果 |

`AdventureTriggerAttack` 的 `OnAttack` 与 `OnBattle` 由独立容器持有。后者不会在攻击时提前施加。每个请求的待执行列表保存在施放者的临时状态里，并在请求结束时清空；成功入战只接收构造时实际形成波次的 NPC 实例。NPC 效果直接绑定该实例的真实波次，避免在构造结束后再加入无法被收集的临时效果。施放者效果在 `player.setBattle` 收集快照前执行，因此原始 1310 的 131001、131002 和 1000112 都能出现在实际战斗快照里。

旧 `AdventureFireProjectile.Projectile` 是 concrete `ProjectileData`，原始 Asta 节点仅有 `Behavior=Static` 等属性，没有多态 `$type`；其合法行为名称已与本机 `ProjectileMoveBehavior` 核对。`NewAdventureFireProjectile.NewProjectile` 则保留原始多态声明。回调执行使用客户端明确上报的事件，不根据攻击发生时间推测寿命结束。已有非原生调用保留单次命中派发的兼容行为。

本机原生证据来自当前安装的 4.6 `GameAssembly.dll`，SHA256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`；元数据 SHA256 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`；冻结描述符 SHA256 `a9de1824e9892e236d7ce0f2d19f5531ffb259c125c4d22da89dfb7ac23c08fd`。

只读脚本 `scripts/inspect-maze-callback-phases-client.py` 复核了 53 个原生检查和 12 个原始资源检查，覆盖 9 个类型与 6 个方法。证据包括两个独立原生回调成员、构造函数的独立 delegate 存储、命令 1460 的实际发送、请求类型槽、字段 11 的 enum codec 与 `WriteTo`。冻结枚举为 NONE=0、HIT=1、LIFETIME_FINISH=2。脚本不加载或运行客户端 DLL，固定原生证据存于 `scripts/fixtures/maze-callback-phases-native-4.6.json`。

复跑只读证据：

```powershell
python -s -X utf8 scripts/inspect-maze-callback-phases-client.py --client-dir C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current
```

测试优先经过统一 RED：`MazeCallbackPhases46Test` 13 项中 7 项失败、0 错误、0 跳过，复现原生事件被拒绝、未标记请求误执行两个回调，以及失败入战污染和入战回调提前执行。修正后包含本批 13 项的 706 项迷宫技能回归全部通过。对应报告分别保存在 `build/callback-daily-monster-red-tests` 与 `build/callback-daily-merchant-red-tests`。

外部探针 `scripts/smoke-maze-callback-phases.py` 已在本批冻结产物 `build/parallel-gameplay-second-runtime.jar` 上通过实际 KCP 验证，jar SHA256 为 `02e5a8c622c785fe126a28789f8d7055a0e280ceaa618d0e2b06c71cdd890ddd`。报告 `build/maze-callback-phases-fourth.json` 保存 92 个请求、90 项检查、16 次实际施放（11 场准入战斗和 5 次拒绝），全部 11 场战斗均核对当前 battle ID、原始 Stage80030231 阵容，随后提交胜利并确认重复结算拒绝；服务端正常停止 1 次。jar 和 2260 份原始资源的前后哈希相同。

探针使用新隔离 H2、资源副本和只给新 UID 10001 的角色前置，检查原始 8007 两种事件、原始 1310 成功与失败入战、受控 Asta 差异回调，以及逐次坏事件拒绝后的实际状态。场景位置、目标集合和 Asta 测试叶是控制夹具，标记寿命设为一小时以消除连续验证速度的影响；原始 8007/1310 技能、NPC 表和 stage 阵容保持原样。正式队伍使用已验证的 `ReplaceLineupCsReq`，请求与读取字段先核对冻结描述符；正式队员为 8007、1310、1009。报告保存每次实际发送的事件、技能索引、状态、入战结果，以及每场完整效果行和角色槽位，保留重复效果 ID。

流萤普通攻击本来会产生元素入战效果 1000112，原始秘技 `OnBattle` 的角色快照也使用同一编号。网络检查按完整行区别来源：两次普通攻击均只有 owner/target=1、`SkillIndex=1` 的元素行，没有 131001、131002 或无动态值的流萤角色快照；成功秘技才产生 NPC 两行、`SkillIndex=2` 的元素行和无动态值的角色快照。两条 NPC 效果的 owner=1、waveFlag=1，与该次真实形成的一波 NPC 阵容一致。Asta 的受控标记 199998102 保留在 owner/target=2，未被混作流萤效果。`SkillIndex` 是既有技能加载器的内部 1/2 编号，请求 `skill_index` 为 0/1。

早期探针失败均保留：`build/smoke/maze-callback-phases-122tmz_v/probe.log` 是误用队伍请求字段；`maze-callback-phases-l9prf4po/probe.log` 是错误禁止原有元素效果；`maze-callback-phases-lsfpcap7/probe.log` 是将内部效果编号误作请求编号。第三次完整观察另存于 `build/maze-callback-phases-third-observations.json`。上述修正只涉及探针与预期，未为取得网络通过改变运行代码，也未删去对失败入战污染的断言。

本批网络复跑命令：

```powershell
python -s -X utf8 scripts/smoke-maze-callback-phases.py --jar build/parallel-gameplay-second-runtime.jar --output build/maze-callback-phases-native.json
```

官方客户端动作画面、实际投射物事件调度、多包投射物秘技的 MP 生命周期、其他未知条件/任务、`OnHit`/`OnKill` 的独立执行链，以及活动中战斗恢复和崩溃原子性仍待验证或实现。现有单请求秘技的 MP 语义保持既有行为；上述静态与服务端回归仅证明本批明确记录的执行链。

本批统一完整构建实际通过490类、13,304项回归，失败、错误与跳过均为零（`build/parallel-gameplay-second-full-tests/test`）。构建前后的2,489份编译输入零变更，完整构建产物与上述冻结jar哈希相同；现有70,616份配置及资源字节未变（`build/parallel-second-compilation-audit.json`）。
