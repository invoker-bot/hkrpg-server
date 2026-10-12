# 遐蝶 1407 原 Out 的有限清理入口

本批接通 `LocalPlayer_Castorice_MazeSkill_Out` 的空目标原 Submit：在同一活动会话、实际场景、当前领队及真实 base 释放的 20 秒 140704 来源仍匹配时，以新 Ability UseID 清理该施放者的 140704。该入口不调用 `resolveAttack`，不扣秘技点、不触发日常秘技计数，也不创建战斗。

## 完整来源及字段

资源固定于 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。`maze-castorice-out-4.6.json` 保留 12 份完整原件的无损压缩、原长度及 blob SHA，包括完整 Ability、LocalPlayer、AvatarConfig、MazeSkill、AvatarMazeBuff、AvatarSkillConfig/LD 和角色依赖。既有 Input 来源夹具的 SHA `2a77f5ded624778f828a33febd8908a876f817cfa82cda518d0f8955a7706f93` 保持不变。

完整 Ability 的 Input.OnAbort 指向 Out，Out.ManualTriggerType 为 Free。Out 的 OnStart[2] 在 `Caster == LatestAdvTeamLeader` 成立时依次执行 AdventureSubmitSkill、RemoveMazeBuff(140704, Caster)。原 Out 没有 Attack、费用或投射物声明。原 LocalPlayer 没有 SkillCostMpPhase；本批继续使用真实 base1460 的既有未预付兼容行为，不制造 1408 预付款。

原生核对读取安装的 4.6 DLL、global-metadata 和 startup-metadata，未加载或执行游戏 DLL。完整函数及泛型实例记录证明：`ALGDMNMOPPK` 的父类为 `ImmediateTaskBase<AdventureSubmitSkill>`，其 OnTaskBegin 调用 TrySendSceneCastSkillCsReq。DGO 填充的默认标签数组为 `[1,2]`；调用者的两个 null 分别写入 DGO+0x20/+0x28，没有覆盖 DGO+0x18 的标签。PHBADCBNPAK 将同一 DGO 写入实际 1460 消息；完整 Writer 的 field11 是标签，field13 是新 Ability UseID，field15 是 Out 能力名，field4 为技能索引 1。字段 13 不填领队实体 ID。

早期空标签草稿没有编译或执行，已明确废弃。不能把那个草稿称为行为 RED，亦不能把 `[1,2]` 统一解释成 Attack。

## 实际回归记录

- 冻结 f586 包：SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。
- 实际不可变包 RED：新 Out 40 项中 22 项纯 AssertionFailedError、18 项对照通过；原 Input 40 项全部通过。不存在 setup 错误或跳过。
- 独立 Java 21 候选 GREEN：新 Out 40 项、Maze peers 870 项，共 910 项全部通过。仅覆盖三个候选生产文件，排除当前 main 输出，逐类 CodeSource 和输入前后 SHA 核验通过；没有运行 Gradle 或服务器。
- 原 Input 测试中单条 Out scope guard 被明确更新为受本批原条件允许；unknown 仍拒绝，原 Input 正向、费用、准入失败和快照断言保持。旧 40 项基线与 superseded 记录分别留存。

回归覆盖零 MP、原领队条件、错技能槽、未知及反序/重复标签、非法或旧 UseID、攻击和助攻输入拒绝、重复 Out、base 刷新后旧 Out 不可借用、会话/场景更换、失效/刷新/移除的 buff、八份完整原件缺失/变更后同 ID 恢复重试，以及实际 caster 清理异常时 buff/绑定恢复后同 ID 重试。140701 和其他角色的 140704 均保持。

## 当前验收边界

已对统一候选包完成本批 Out 的真实 KCP/H2：SHA256 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`，报告 `build/maze-castorice-out-current-native.json` 的 SHA256 为 `08feceb9582f2e69029ef3e2f32b1f530a0907c26ebeb95c46a351313f5c2658`。实际 290 请求对应 290 observedReplies，319 检查、3 次正常停服、2 次同 UID H2 重启；只读检查完成 9 次真实 Morphia Player 和 9 次 Avatar 恢复，保存文件 SHA 前后不变。原生运行时全量构建仍在进行；随后根代理已确认正式全量 516 suites / 14,106 tests，零失败/错误/跳过，2,573 个编译与资源输入前后 SHA 稳定；最终包 SHA 与本次 Native 的 `39601d34…23c5ab` 逐字一致。

三个实际进程分别为 218/228 请求与检查、MP1；41/52、MP0；31/39、MP0。7 次实际 base1460 共产生 7 次兼容点费/通知；5 次新 Out 各产生一次实际 caster140704 删除，Out 没有额外点费通知、没有 BattleInfo 或目标结算行。真实领队切换、不同 actor/slot、旧 ID、未知/反序/重复标签、实际 NPC 攻击输入、缺 LocalPlayer 原件后同 ID 恢复、完整 21 秒墙钟等待和已计数只读心跳、实际场景切换与新 Session 均按预期响应。

运行使用 13 个完整物理资源副本，其中 12 个固定原 blob 在每次 H2 读前复核；上述39601验证时LocalPlayer只补入受控目录；后续已将同一完整原件补入运营目录，来源及既有文件保护见[角色配置安装](maze-player-config-install.md)。临时 buff、caster metadata、UseID/来源账本没有持久化。资格、MP 或完成状态没有直接写库；有限 MP 恢复来自已注明的受控回收箱摆放及真实 1460 请求。

可重复工具为 `scripts/smoke-maze-castorice-out.py`、`NativeMazeCastoriceOutProbe.java`、`MazeCastoriceOutFixtureInspector.java` 和对应 14 项保护测试。它们是新独立文件；原 Input 工具未改。复制的旧 Input 探针曾把成功 Out 的删除数计成 0，该工具语义先以 1 项 guard RED 锁定并留存原 Java，随后改正，14 guards 全绿。另有 6 项原生来源守卫通过。descriptor/javac 预检明确未启动服务器、不能计 Native。正式工具交付副本与此次实际执行的四个源 SHA 完全相同。

本批只清理原 caster 的 140704。原 radius modifier.OnDestroy 中的 14071 销毁、14071 创建/跟随/触发区域、整套 modifier 生命周期、动画、距离与官方客户端 UI 尚未验收。原召唤 Duration 省略，不能由 buff 的 20 秒推造召唤持续时间。旧 Input 的原生 294 请求/330 检查属于已提交的上一批，不作为本批 Out Native 结果。

运行只读取已配置资源目录内、完整物理路径闭包中的固定原件，缺件或变更时拒绝。上述Out批的LocalPlayer原件仅用于受控夹具；后续两份原配置补充不覆盖已有运营文件。当前遐蝶八份完整物理来源的规范LF SHA已核对，记录为 `build/maze-player-config-installed-source-pins-v2.json`；references仍不成为运行依赖，配置检查不等于完整玩法或新包客户端验收。
