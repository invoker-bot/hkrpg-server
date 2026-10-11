# 4.6 探索施放 UseID 与预付款

本批补齐实际 `1408 → 1460` 预付秘技点、同一原生 UseID 的独立回调及 `[1,2]` 默认事件组合。它延续原来的实际 Scene、BattleService 和战斗结算入口，没有新增协议字段，也没有将动画任务离线重演称作战斗完成。Java 源码继续使用 LunarCore 包名与 AGPL 许可；参考资源只用于固定来源和自有夹具，没有启动参考仓库内下载的二进制。

## 固定来源

客户端位于本机 `hoyo/hkrpg/versions/current`。`GameAssembly.dll` SHA256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，metadata 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`，4.6 descriptor 为 `a9de1824e9892e236d7ce0f2d19f5531ffb259c125c4d22da89dfb7ac23c08fd`。资源来自 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。

[`inspect-maze-cast-sequence-client.py`](../scripts/inspect-maze-cast-sequence-client.py) 对照固定 profile、完整 PE 函数边界、原始函数字节 SHA 和实际指令，核对 14 个类型、16 个原生函数及协议 Writer。最终只读运行有 224 项检查；它没有加载或执行客户端代码。

[`maze-cast-sequence-native-4.6.json`](../scripts/fixtures/maze-cast-sequence-native-4.6.json) 保存函数边界及原生类型/枚举。profile SHA256 为 `987b779207b2d8745404d5f35ec2adf1dcff94a2d6aea2326a9f11054231b8f0`。[`maze-cast-sequence-4.6.json`](../src/test/resources/fixtures/maze-cast-sequence-4.6.json) 保存原始 Asta1009、HimekoNova1510 的 AvatarConfig、正常/秘技能力及 MazeSkill 行，SHA256 为 `23b2deeedd11d7f66bad1c6b23a90cc787bb4981b49ea420d7238796eed5bb35`。每份原文件分别记录 Git blob LF 和本地 CRLF SHA；脚本核对它们只差换行，没有把不同哈希写成同一份字节。

## 已核实的原生路径

| 实际节点 | 可核对的含义 |
| --- | --- |
| `AdventureSkillCharacterComponent.UseSkill` / `e55e310` | 原生 uint 计数器分配 UseID，跳过 `0xffffffff`，写入使用参数 `+0x2c` |
| `AdventureAbilityComponent.UseAbility` / `e5369d0` | 同一参数的 UseID 写入 Ability 实例 `+0xa4`，区别于内部索引与基类全局编号 |
| `NCGKGFAJMGM.OnTaskBegin` / `bb09c10` | 原投射物 Task 从 SourceAbility 或 OwnerAbility 读取 `+0xa4`，快照写入 `OHHPOGMFPBF +0x1c` |
| 投射物创建链 / `bb0a360`、`15daba00`、`b14be40`、`baa38b0` | 实际寄存器与参数转发保留该快照；中间有间接虚调用，未把邻接方法当成已解析的唯一动态目标 |
| `DGOAKJNHIFJ.MHAHLFKCLKI` / `e84dab0` | 普通发送读取 Ability UseID；有 LastFinishProjectile 时改用其创建时的 UseID，避免新 Ability 覆盖旧投射物 |
| `NetworkManager.PHBADCBNPAK` / `d9c4ce0` 及 `INBDPGBJIFA.WriteTo` / `1e690a10` | `1460` 中 `attacked_by_entity_id=2`、`skill_index=4`、`cast_entity_id=13`；field13 是 UseID |
| `_CostMazeSkillMp` / `e575700` 及 `GDGMINPNACD.WriteTo` / `e41c990` | `1408` 同样携带 UseID field13，施放者 field2、技能索引 field7；索引是0/1，不是 MazeSkill 表编号 |

`DGOAKJNHIFJ` 的静态构造保存 `[1]`、`[2]` 与 `[1,2]` 三份数组。没有 LastFinishProjectile 的实际构造路径选 `[1,2]`；投射物具体事件根据 IsHit 选 `[1]` 或 `[2]`。因此本批接受原顺序 `[1,2]`，保留无标签/显式 NONE 的旧直接入口，并拒绝 `[1,1]`、反序 `[2,1]`、NONE 混用及未知枚举。普通原生默认组合不要求角色拥有投射物声明；单项 HIT/结束声明仍要求原技能确实声明投射物。

原生 `CostMpPhase` 为 OnUse0、OnHitEnemy1、OnFirstHitEnemy2。UseSkill 的 OnUse 分支先读取 MazeSkill 行 MPCost 并发送1408，随后启动 Ability 和可选1460。`PreCostMpWhenHitValidTarget` 在 HIT 阶段调用成本函数时，实际 `sendRequest=false`，这是本地预测，不是另一次1408。原完整 AdventureCharacterConfig 的各角色 SkillCostMpPhase 配置不在固定资源中；不能从默认枚举0推断所有角色都采用 OnUse。

## 实际服务器实现

1408 验证当前活动连接、当前 Scene 的真实存活角色、当前技能和索引，以原 AvatarConfig.SkillList 关联唯一 MazeSkilltype2/SkillTriggerKey=MazeSkill 行。Asta 原 MPCost 为1，资源角色1510原 MPCost为2。资源缺失、损坏、字段错误或归属歧义拒绝付款；不足不创建已付收据。

已付记录绑定实际 GameSession、UseID、Scene、Lineup、GameAvatar、MazeSkill 和技能索引。重复1408返回相同 UseID 成功回复，余额和通知不重复；后续相同 UseID 的合法1460即使余额为0也能执行。本次施放的 `onCast` 只运行一次；每颗合法投射物 HIT 仍独立执行其目标动作。旧 UseID 的回调恢复对应 CastMetadata 快照，避免下一次同技能施放覆盖旧投射物上下文。

记录为 Java 与 Morphia 双重 transient，没有写入玩家存档，没有猜 TTL，也没有把 caster+skill 当作恒定去重键。当前每个活动连接的服务器准入预算为4096份 UseID；耗尽时拒绝新记录，不驱逐已付款记录。这个数值属于项目资源上限，不是官方 Ability 生命周期。场景、队伍、角色、技能或连接变化不能借用旧付款。

组合标签在一次实际攻击中选择 HIT 和结束动作，共享的同一个已编译动作对象只执行一次，不将请求拆成两次入战。正常 NPC→Stage 波次、失败入战及 OnBattle 的构造 buff 收集顺序保持既有链路。

未收到1408的1460保留原项目简化的逐请求扣1点规则及1308例外。这不等于解析出所有角色的 OnHitEnemy、OnFirstHitEnemy、CostMP、命中数或技能变体。尤其1510的完整 Input 技能变体与原始外层条件仍不在本批完整支持范围。

## 验证记录与可重复命令

`MazeCastSequence46Test` 的26项真实入口回归在生产修改前实际17项断言失败、9项兼容检查通过，0错误/跳过，输入前后2498份无变化。回归覆盖：默认组合、单项、重复/反序/未知、NONE、两个原始 MPCost、同一次 NONE/HIT/结束、连续同技能、新ID旧HIT、不同目标多投射物、非法先到、点数不足重试、失败入战及重试、跨技能/角色和新连接，以及大于 signed int 上限的合法 uint UseID。

第一轮891项/38类统一回归仍有4项失败并保留日志：跨角色测试给第二演员手写的 `actor.id+1` 与下一只真实 Scene NPC 撞号，已改用实际 Scene 分配器并增加无碰撞断言；三项旧生命入口检查需要原 BasePacket 发送路径，已恢复该路径，由既有 ProtocolBridge 的 `attacked_group_id→cast_entity_id` 映射携带真实 UseID。原行为断言均保留。

修正后的 `build/cast-g61-ammo-chess-tests` XML 中，32份 Maze 套件合计732项、0失败/错误/跳过，其中新施放26项、旧存活施放者20项均通过。`build/cast-g61-ammo-chess-verification.json` 记录2513份构建输入前后不变；同一整批的其他功能 dispatch 异常另行保留和修复，这里的 GREEN 只对应上述 Maze 范围。

根代理实际执行的 `build/maze-cast-sequence-current-native-second.json` 已通过187次网络请求、212项检查、三次正常停服及两次同 UID H2 进程重启。冻结包 `build/gameplay-cast-g61-chess-ammo-runtime.jar` 的 SHA256 为 `eda71266f09ce2a489d67a8d49a3b53e38c0d134d21eb9d5ff995446bc2d4e2a`。三份真实快照的请求数为160/18/9；停服 H2 中普通 MP 为1/0/0，UseID 已付账本均未持久化，两次新 Player 读取均为空。后续其他功能窄修复将生成新包，此记录是该 SHA 的已执行有限验收，最终联合包需另行复测。

包含日常同日资格刷新、WolfBro 原件 pin 修正及 Chess 取得通知的最终冻结包 `build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar` 也已实际复测同一路径：`build/maze-cast-sequence-current-native-third.json` 为187次请求、212项检查、三次正常停服、两次同 UID H2 进程重启，全部通过。包 SHA256 为 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`；报告 SHA256 为 `719d5e9b4ef0372324ed94424cc09adfc74613504773c6591b4830ce7f45a143`。三阶段仍为160/18/9次请求、普通 MP 1/0/0，原始资源和运营数据未修改。这个结果验证既定预付 UseID 网络闭环，未补充官方实际发包序列或未预付命中费用语义。

只读来源核对：

```powershell
python -X utf8 scripts/inspect-maze-cast-sequence-client.py --client-dir "$env:LOCALAPPDATA/hoyo/hkrpg/versions/current" --output-dir build/maze-cast-source-new
```

脚本环境边界8项已通过，两份自有探针已 javac 编译通过，12份发送/消费消息 descriptor 预检通过。资源准备自检固定2262份原文件 SHA，受控 Scene、放置及 Asta 标记文件在独立目录生成。1408/1460 即使返回码与预期不符，也先保存实际返回码、余额、通知数和回调计数再执行断言，避免失败报告只留下先前成功请求。

第一次实际 Native 在原普攻与 WIN/重放通过后，1510的1408返回5。记录保留于 `build/smoke/maze-cast-sequence-t9d0t7ji`：夹具的 ExcelOutput junction 令原 MazeSkill 表 realpath 越出隔离根，被既有闭包校验拒绝；请求 skill_index 已为1，不是字段编号错误。工具先以真实 prepare 路径锁定8项保护中的1项 AssertionFailure，再将 MazeSkill208行、AvatarConfig94行、MultiplePathAvatarConfig12行、AvatarSkillConfig7040行四张完整原表逐字节复制到隔离目录。准备、每阶段及结束都复核原件/副本 SHA 和物理归属；没有放宽服务器的来源校验。第二次上述 Native 在这份修正夹具上通过，第一次失败不计功能通过。

```powershell
python -X utf8 -m unittest scripts.tests.test_smoke_maze_cast_guard -v
python -X utf8 scripts/smoke-maze-cast-sequence.py --jar build/FROZEN-RUNTIME.jar --output build/maze-cast-sequence-new.json
```

网络脚本只绑定 loopback，HTTP禁用系统代理，HTTP/Mongo TCP端口去重，输出预先排他保留 lease，已有报告不能覆盖。清理只针对本轮启动的进程，停服失败附加到首异常；未确认 ready 的进程不计正常停服。使用新的实际 `90001/90101...` UseID，独立于施放者实体编号。

上述第二次已执行的网络路径包括：原普攻 `[1,2]` 入战/胜利；1510原MPCost2的独立1408正向扣款；Asta实际1408后NONE、两个不同目标HIT及结束；重复付款与跨角色/技能拒借；失败Stage准入/重试；新ID启动后旧投射物晚到；非法组合先到不污染；MP0已付请求与不足付款、实际破坏原MP恢复箱后同ID重试；新连接/H2重启重新付款及双重transient检查。两只原MP恢复箱复制到受控 Town 放置，不能据此称该 Town 官方就有该箱。

回调计数只使用明确受控的 Add/Remove 私有标记叶，每次实际移除通知分别证明释放、HIT和结束调用。原Asta投射物声明、原PlayerBoy普攻、原NPC/Stage/MazeSkill表行及原始资源保持不变。官方请求序列、真实客户端UI/动画/物理、全部未预付成本语义、断电原子性及全战斗/全玩法仍未验收。
