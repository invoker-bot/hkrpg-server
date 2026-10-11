# 4.6 场景攻击逐目标结果

`SceneCastSkillScRsp` 的 `monster_battle_info` 现在来自这一次服务端技能实际执行结果。普通入战使用实际参战怪物；瞬杀使用 `Scene.defeatMonster` 成功时保存的正实体 ID；未入战的存活目标返回 `NO_BATTLE`。混合瞬杀与抗瞬杀目标时，已击败对象不会再次进入战斗 NPC 名单和关卡波次。

技能回调之前按目标、助攻顺序保存实体身份并去重。直接死亡只由已有瞬杀动作在 `defeatMonster` 成功后登记，行政移除不能变成死亡证明。收集器只存在于同步攻击调用作用域，退出时清除；技能定义和玩家持久化数据不保存回复状态。错误回复没有成功目标行，普通战斗继续使用原有战斗 ID、结算和重复报告拒绝流程。

协议继续使用已经冻结的 4.6 描述符，不修改生成类、命令表或 `GameConstants.VERSION`。本功能使用的值为 `TRIGGER_BATTLE=1`、`DIRECT_DIE_SKIP_BATTLE=3`、`NO_BATTLE=4`；`DIRECT_DIE_SIMULATE=2` 没有在这里实现。4.2 的 `BattleService.attack` 仍发送一次原有回复，4.6 的 `Battle46` 序列化一次实际执行返回值。

## 来源与只读原生复核

- 命名协议：`March7thHoney-OpenSource`，提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，`Proto/4.6/CmdSceneType.proto`；只用它确认已冻结协议的含义，没有导入参考服务器实现。
- 原始瞬杀分支和事件关卡：`TurnBasedGameData`，提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。回归复用项目现有原始记录，演员与怪物在测试场景中的组合是显式 fixture。
- 本机 `GameAssembly.dll` SHA256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`。
- 本机 `global-metadata.dat` SHA256：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- 冻结描述符 SHA256：`a9de1824e9892e236d7ce0f2d19f5531ffb259c125c4d22da89dfb7ac23c08fd`。

`scripts/fixtures/maze-attack-results-native-4.6.json` 保存精确类型、原生偏移、字段/枚举常量和调用点。`scripts/inspect-maze-attack-results-client.py` 复用项目已有只读 IL2CPP 分析器，核对 47 项事实。目标行写入 tag `0x28` 和 `0x58`，对应字段 5 和 11；回复的重复目标字段是 13。两个消费者使用同一个原生回复类型槽，目标核对方法读取每行实体 ID 与结果，并把 2/3 作为同类直接死亡结果。旁边 Raw proto 的旧实例偏移注释没有用作事实来源。

```powershell
python -s -X utf8 scripts/inspect-maze-attack-results-client.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current'
```

报告写入 `build/maze-attack-results-client/native.json` 和 `verification.json`，输出必须位于项目 `build/`。指纹、字段、枚举或调用点改变即拒绝复用结论。脚本不加载 DLL、不启动客户端、不修改客户端。

## 验证边界

`MazeAttackResults46Test` 通过真实 `VersionedPacketHandler` 请求入口、原始技能分支、真实 `BattleService` 和已冻结动态协议检查逐目标结果。第一次回归 9 项中有 6 项失败，确认了缺少目标结果及混合路径重复加载已击败怪物的问题；扩展后的 18 项在本批专项回归全部通过。覆盖普通入战、混合瞬杀/抗瞬杀、非入战、助攻去重、行政移除、缺失关卡、非法目标、怪物伏击、错误战斗 ID、合法胜利/失败/退出及重复结算拒绝。

同一份冻结服务器 jar（SHA256 `3948948ca7cba698c4a6c4e0fdb1c40bb45f7a3815f8753ddb00f43a5c8d9163`）通过真实本地 KCP 的战斗/日常弱点联合探针，报告为 `build/parallel-battle-native.json`：110 个请求、42 个检查、4 次正常停止、3 次同 UID 的 H2 恢复。三次普攻分别确认实际主目标的 `TRIGGER_BATTLE` 行、回复战斗 ID 与当前战斗相同、原始 Stage 阵容与入战元素增益正确，且实际胜利、错误 ID 和重复结算分别走预期分支。该脚本使用隔离测试实例和动态 4.6 协议，以上结果只证明这些请求的网络闭环。

只读原生解析说明目标字段和消费代码已经核对；测试和 KCP 探针说明已覆盖的服务端闭环行为。它们不等于官方客户端动作、动画或 UI 实际验收，也不表示完整战斗系统、完整瞬杀角色能力或所有 `MonsterBattleType` 分支已经完成。整批全量构建另由集成验证记录。
