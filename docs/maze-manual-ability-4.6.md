# 1510 原 Input 能力与命中费用的有限接入

本批修复真实 `Battle46` 入口拒绝 `LocalPlayer_HimekoNova_MazeSkill_Input` 的缺口。只接入角色 1510 的这一份原 `Free` 手动能力及其单项 HIT/END 请求；其他角色、`Out`、额外队伍费用修饰器、完整召唤物生命周期和客户端技能界面仍未完成。

## 原件与客户端调用链

资源来源固定为 `TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。测试 fixture 保存完整原字节，而不是重写技能片段：

| 文件 | 原 LF 字节数 | 原 LF SHA256 |
| --- | ---: | --- |
| `LocalPlayer_HimekoNova_00_Ability.json` | 66910 | `ac339d288f866ec7ecac96b90e6bf0a494e5098ec5b635570ace5ddd7f4da6c3` |
| `AvatarConfig.json` | 232938 | `9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e` |
| `MazeSkill.json` | 55434 | `16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e` |

`scripts/freeze-maze-manual-ability-source.py` 只读固定 Git blob、参考 checkout 和安装原件，并比较完整字节。安装副本允许 CRLF；运行时 pin 比较只规范化 CRLF→LF，不改 JSON 数值或字段。完整技能还保留 `GlobalModifiers` 和未实现的原任务。

原 base 技能添加 `ADV_StageAbility_Maze_HimekoNova_AttackRadius`。该 modifier 的原 `WaitSkillInput` 保留 `CheckAllowUseSkill=true`、`CheckMPNeed=true`，并通过 `AdventureTriggerTargetAbility` 指向 `_Input`。该能力为 `ManualTriggerType.Free`，原 `NewAdventureFireProjectile` 的 HIT 与 END 都有 `AdventureTriggerAttack`，明确 `CostMP=2`、`CostMpPhaseType=OnFirstHitEnemy`。

本机 4.6 的 15 份完整 PE 函数证明：`ManualTriggerAbility` 分配新的 Ability UseID，传入 `UseAbility`；投射物快照继续携带同一 UseID；最终 Writer 写到 1460 的 field 13。原能力名字通过 field 15 发送，技能槽为 field 4，阶段枚举为 field 11。field 8 仍是未命名原字段，本批不把它解释成费用。原 `ManualTriggerAbility` 普通体没有直接调用 `_CostMazeSkillMp`；实际 Attack 的 `PreCostMpWhenHitValidTarget` 在有效目标数为 0 时退出，phase 2 按每次 Attack 把数量限制为 1，然后先记本地费用，再发送 1460。没有据此推断整个 UseID 的所有 HIT 只能扣一次。

`scripts/inspect-maze-manual-ability-client.py` 已完成 108 项只读核对，报告 `build/maze-manual-ability-client-second/verification.json`。第一次工具因缺少 analyzer 的 `disassemble` 参数失败，保留 `build/maze-manual-ability-client-first/verification.json`，不计源码或行为失败。2 项 Python 来源保护回归已通过。这些证据不执行官方 DLL、客户端或参考库二进制。

## 服务端行为

1408 的原费用仍只属于 base 能力；其 field 13 已由上一批验证为 UseID。本批要求原 base 已在同一活动 Session、同一实际 caster、相同技能槽与当前 Scene/lineup 内付款并释放，且释放快照包含原输入 modifier。该记录只证明 Input 的来源权限，不转移付款。Input 使用自己的新 UseID 与 field 15 原名字；不能拿 base UseID、另一角色或重登录之前的记录支付。

每个合法 HIT/END 只执行选中的原回调。有效敌人触发原费用 2 点；同一次 Attack 命中两个敌人仍扣 2 点，合法多次投射物请求分别处理。空阶段不扣点、不发新付款通知。点数不足和非法标签在绑定/执行之前拒绝；成功扣点通知回显 Input 自己的 UseID。原 `ByAllowInstantKill` 分支继续产生逐目标原结果；不能瞬杀的实际 NPC 进入其真实 Stage，并在战斗波次收集前应用原 `OnBattle` buff `151002`。失败战斗准入不提交命中费用，可重试，这是本批有限服务端事务边界，不是官方客户端退款流程的验收。

`SkillAbilityInfo` 只从这一份原 base→WaitSkillInput→Input 链登记对应变体；`MazeSkillCosts` 核对上述三份完整原件、实际持有角色的技能所属和原 Attack 费用。未知名字、变更原件、普通攻击槽、未验证的 assist/组合标签都拒绝。本批不增加 TTL，不用 caster+skill 恒定去重，也不覆盖 `src/generated`。

## 回归与网络证据

正式 `MazeManualAbility46Test` 首轮为 **19 项，10 项纯 AssertionFailedError，9 项兼容/来源对照通过，无错误或跳过**，失败均锁定原 Input 返回 5。证据为 `build/manual-ability-current-root-red-verification.json`。修复后根正式 focused 共 433 项全部通过，包含这 19 项；后续完整构建结果由根统一记录。

独立 Java 21/Lombok 验证使用冻结 F2 jar 加本批五份生产类覆盖，明确排除 `build/classes/java/main` 与主资源输出，逐类确认 CodeSource：新 19 项和旧 Maze/场景战斗 782 项全部通过。证据为 `build/battle-cast-draft/manual-independent-green-v2/verification.json`。v1 的 5 个 Windows AF_UNIX selector 环境错误已保留；仅独立运行器补项目已有 TCP fallback 后恢复通过，未修改或放松行为断言。这一独立运行不代替正式 Gradle 或部署产物验收。

新的 `scripts/smoke-maze-manual-ability.py` 使用独立目录、账号、KCP/HTTP/Mongo 端口及排他输出 lease，完整物理复制三份来源和另外两份引用表。原 1510 技能文件保持完整字节，不使用回调 marker 替换其原任务。G91、恢复箱摆放与失败 Stage event 是明示测试场景；正常可瞬杀 NPC `1002020`、Elite `1003010` 和 Stage `80030231` 的原表行不改。只通过隔离 console 授予真实正式角色 1510；不写 MP、UseID、付款或战斗胜利账本。所有实际回包先记录再断言；停服后只读 H2 并执行两次独立真实 Morphia Player 读取。

9 项新脚本守卫已通过；首次 mock 全局 Popen 导致只读 Git 无法 communicate 的工具夹具错误保留在 `build/battle-cast-draft/manual-native-guard-first-failure.json`。新 jar `build/manual-sibling-save-blast-runtime.jar`（SHA256 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be`）已通过 javac/descriptor 预检。首轮实际网络在 Input status 0、MP 3→1、一次自身 UseID 通知正确后，因探针误用不存在的 `DEAD` 名称停止；真实原结果为枚举 3 `MONSTER_BATTLE_TYPE_DIRECT_DIE_SKIP_BATTLE`。该失败与正常停服 1 次保存在 `build/smoke/maze-manual-ability-nn5o448t`。新增枚举语义预检确实先失败，再改成原名字并通过，证据分别为 `build/maze-manual-ability-current-enum-red.log` 与 `build/maze-manual-ability-current-enum-green.json`。

第二轮游戏行为、3 次正常停服和 2 次同 UID H2 重启全部通过，原报告保留为 `build/maze-manual-ability-current-native-second.json`。只读复核发现其 `Map.of` 在最后一次余额查询之前读取计数，报告 240 次而实际回包记录有 243 次。对这一份真实报告的回归产生三个子断言失败（169≠168、54≠53、20≠19），保留 `build/battle-cast-draft/manual-native-counter-red.log`。探针改为先查询最后余额再封存计数，并在 Java/Python 两端核对 `requests == observedReplies.size()`；没有修改生产代码或旧报告。

第三轮同 SHA 新包已完成 **243 次真实请求、230 项检查、3 次正常停服、2 次同 UID H2 进程重启**，且计数与每条原始回包完全一致：`build/maze-manual-ability-current-native-third.json`，报告 SHA256 `90c29ba46427a8b31e74e86b047b6323a5e0e52c4fcc1f0d73827ba0ea2a0f31`。三个进程分别为 169/54/20 次请求、153/52/25 项检查，期末 MP 都为 1，通知分别为 8/2/0 次。检查数包含各进程的协议预检与玩法断言。

实际网络已覆盖未释放 base 拒绝、原 base 1408 扣 2 点并 NONE 释放、Input 新 UseID/field 15 且无额外 1408、不能借 base 付款 ID、非法名字/槽/枚举后同 ID 合法重试、有效 HIT/END 扣 2 点、两目标同次扣 2 点、同一合法 Input UseID 两次 HIT 分别执行和扣点、空 END 不再扣点、不足点数后通过真实恢复箱重试、真实 Elite 的失败 Stage 准入不扣点及原 Stage 重试、原 `OnBattle` buff `151002`、真实胜利/重复结算拒绝。正常停服后的只读 H2 检查确认 MP 与网络一致，每次执行两份独立实际 Morphia Player 读取，未持久化 origin/UseID 账本；新 Session 和第二次恢复均不能借用旧 base 释放权限。来源资源、隔离配置、冻结工具和生产输入的 SHA 复核保持不变。

KCP 测试发送的是由已核原调用链构造的请求；没有运行官方游戏或观察其真实按键事件。正常 H2 恢复不等同于崩溃原子性、客户端物理/动画、菜单显示或完整战斗完成。
