# 飞霄原始秘技寿命与普通攻击中断清理

本候选补入原 `LocalPlayer_Feixiao_00_MazeSkill` 的122001增益寿命，以及原普通攻击 `OnAbort` 启动的独立 Free 清理。清理只移除施放者的122001，不启动战斗，不再次扣MP。已按冻结来源和旧目标 SHA 精确集成正式 Java；独立946项回归、统一520套件/14,226项完整构建及同新包584次KCP请求/473项检查通过。官方客户端演出、移动速度和完整召唤物生命周期未验收。

## 完整原件与真实载荷

固定资源提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。夹具保存14份完整原始 Git blob，以无损zlib保存原始字节、长度、SHA256和Git对象SHA1，解码合计11,837,698字节；没有把JSON数值重序列化为来源指纹。四份角色/能力原件、七张完整表、三份原召唤物配置用于评审。引用数据未成为 references/ 运行时依赖，未导入额外可执行代码。集成后仅以完整固定 Git blob 补入运营目录缺失的飞霄、遐蝶两份 LocalPlayer 配置，独立补充清单及原文件保护见[角色配置安装](maze-player-config-install.md)。

原 LocalPlayer 的 `MazeSkill` 明确为 `SubmitSkillWhenCast=true`；本机完整 `UseSkill` 普通体在读取配置 `+0x19` 后调用 `SummitSkillToServer`，随后调用实际 `TrySendSceneCastSkillCsReq`。原122001的动态寿命为单哈希692437857；完整 LocalPlayer `ReadInfo` 明确指定 `SkillParam/SkillMaze/index0`，原122007一级 `ParamList[0]=20`，没有该技能的LD覆写。

原 `NormalAtk01.OnAbort[0]` 是 `AdventureTriggerTargetAbility`，明确指向 `LocalPlayer_Feixiao_00_NormalAtk01_RemoveMazebuff`，并以 Caster 为能力目标。该任务省略 `SkillType`；完整原构造函数不写该整数字段，已核定原 `NormalAtk=0` 枚举。构造函数的默认目标通过实际literal槽 `0x96f6270` 读取为 `Caster`，没有增加来源不包含的leader条件。

TaskFactory映射使用本机实际 startup registration 闭合：`CBMPNJHKPNN` 的原泛型父类为 `ImmediateTaskBase<AdventureTriggerTargetAbility>`；`ALGDMNMOPPK` 的原父类为 `ImmediateTaskBase<AdventureSubmitSkill>`。均保存原父类类型字节、startup行、实参类型字节及完整函数指纹。

原 Free 清理的 `OnStart` 只有两个动作，顺序为 `RemoveMazeBuff(122001, Caster)`、`AdventureSubmitSkill`。没有攻击、费用或投射物声明。13份完整PE函数覆盖原 base Submit、Free 新 UseID、实际 Submit、DGO 填充、最终Writer和构造。已核对的无 `LastFinishProjectile` 路径使用 `[1,2]` 默认组合；1460请求的原能力名为field15、新Ability UseID为field13、技能槽为field4、组合标签为field11。尚未捕获官方客户端的实际中断发包，来源静态证据不等于官方客户端验收。

## 有限运行方式

新 helper 逐请求检查八份完整原件的物理路径、大小及SHA，仅允许受控资源根内的原件；缺失、变更、逃逸路径均拒绝。实际loaded角色技能列表、122007参数、122001绑定与实际Remove动作ID同时匹配，完整原文件不能替代错误的loaded对象。

122001的20秒寿命只用于原1220、原base能力、明确Caster和原单哈希任务。Free清理只注册到原正常攻击定义，在原normal槽接受原组合标签。每次清理需要同Session、同实际Scene、同持有且存活的Caster、同技能对象的真实base释放记录，且记录的增益到期时间仍与当前增益一致。这个来源限制是本批有限服务端准入；没有把原Remove动作解释为一般用途删除接口。新UseID不得复用base或已接受的清理ID；切场景、换Session、来源期满、伪造actor或改loaded定义均不能借用旧来源。

清理在 `resolveAttack` 前返回；没有目标或助攻列表，不处理NPC、道具、碰撞、战斗结算或日常委托。只有正常成功施放可以消费本次清理绑定。原秘技配置省略付款phase，本批未制造1408预付款；测试保留实际未预付base1460既有兼容扣点1及通知，清理无MP变化或MP通知。没有更改其他角色的付款或回调规则。

## 真实回归层次

- immutable39601第一轮：23项新回归，8项纯AssertionFailedError、15项对照通过；旧1407 Input40和Out40全部通过。基础功能缺口为未创建122001及合法原清理被返回5。
- 完整36项来源与行为回归在同一immutable39601上：21项纯断言失败、15项通过，无错误/中止/跳过；旧80项对照通过。新增范围包含完整八原件恢复重试、期满、新Session、同floor另一实际Scene及另一实际Caster。
- 首限定修复23项及旧80项先通过；随后两项新的loaded一致性问题在该候选上真实失败：同名清理中错误Remove ID、loaded122001被改为TriggerBattle仍被接受。加入只读动作ID getter和严格loaded绑定核对后保留原断言通过。
- 最终独立Java21：36项新回归及38类910项Maze peers共946项通过。所有生产类分别来自五个明确候选编译目录或immutable39601 jar；当前main输出完全排除，全部已加载项目类CodeSource及输入前后SHA审计通过。未启动Gradle或服务端进程。
- 八项独立来源保护通过。一次候选编译使用了错误的单键buff lookup，未执行行为测试；该编译失败已保留，改为原 `(122001,1)` lookup 后重跑。它不计入基础行为RED。

immutable jar SHA256为 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`。来源和结果保留在 `build/battle-cast-draft/next-source/feixiao-candidate-v1/`。上述独立候选回归与前批516类/14,106项分别记录；正式集成后的本批520套件/14,226项完整构建与新冻结jar的Native已通过，详见下方实际证据。

## 未完成边界

原122004具有另一条动态表达式，不在本次122001单哈希绑定内；其刷新及召唤12201的创建/销毁、客户端视觉召唤12202/12203、速度赋值、吸引、碰撞、移动、动画及完整普通攻击条件树不宣称完成。飞霄原LocalPlayer已完整补入运营资源；八份实际物理原件的规范 LF SHA、路径和存在性均核对通过，证据为 `build/maze-player-config-installed-source-pins-v2.json`。这属于配置可读性检查，实际loaded对象与新包请求继续按统一 Native 验证；没有据此宣称完整运营玩法。没有用此候选推广所有Free能力、原生付款默认phase或完整战斗玩法。

## 新包实际验证与失败尝试

本批冻结包 `build/feixiao-selected-item-blessing-console-candidate-runtime.jar` 的 SHA256 为 `3eeeab41ec750f60b92c2ab3a536760afab14eb53c71581d34e18bafb02fa05c`。根代理完整实际 Gradle 通过 520 类 / 14,226 项，失败、错误、跳过均为零，2,584 个输入保持不变；同一包的第三轮隔离 KCP/H2 报告为 `build/maze-feixiao-abort-current-native-third.json`，SHA256 `5f4865e06a89ef943e257372b5e13f14ef563fe549b7ce9ad2579e0c477a0354`。第三轮实际通过 584 次请求 / 473 项检查 / 3 次正常停服 / 2 次同 UID H2 进程重启，以及 9 次真实 Morphia Player 与 9 次 Avatar 只读恢复。生产类全部来自该冻结 jar，辅助编译目录仅含独立 probe/inspector，未覆盖生产类。

这条流程真实执行 15 次基础施放、15 次相应 MP 通知、5 个原恢复物件的各 2 MP 恢复，以及 13 次合法普通槽 0 清理。原配置省略付款 phase，本次未发送 1408；15 次 base 在当前兼容入口实际各扣 1 MP。每个非法标签对照采用真 base → 拒绝 → 同 ID 合法重试，所有成功清理的来源年龄上界不超过 8,501 ms；20 秒原参数保持不变，21 秒期满等待以真实只读队伍请求保活，心跳也纳入 584 次请求。清理无额外 MP 或战斗，日常对照为本次账户 `GetDailyActiveInfo` / `GetQuestData` 的全文前后比较，未预置任务完成/领取状态；这不代表所有日常资格组合。

首轮失败与独立计时复现分别保存在 `build/smoke/maze-feixiao-abort-hn3bjc1e`、`build/smoke/maze-feixiao-abort-08uzlqj6`，各有 203 条 observed replies、1 次正常停服、零 H2 读取，不纳入第三轮成功计数。首轮从 base97410 到失败 cleanup97411 间隔 100 条实际请求，但未记录精确逐请求墙钟，其时长保持未知。原序列的计时复现记录 source-origin 年龄下界 24,740 ms / 上界 24,786 ms，超过原 20,000 ms；实际返回 5，MP 3→3、零新增 MP 通知且无战斗，确定是该复现的工具时序越界，没有据此认定游戏行为 bug。两旧输出 lease、日志与各版工具均独立保留。

复现入口为 `scripts/smoke-maze-feixiao-abort.py --jar <冻结jar> --output <新的build报告>`，两个专有辅助文件为 `NativeMazeFeixiaoAbortProbe.java` / `MazeFeixiaoAbortFixtureInspector.java`；`--preflight-only` 只编译与核对 21 种 schema，明确不计 Native。20 项工具保护全部通过；交付时仅把已执行的 5 项来源/时序保护并入 15 项隔离保护文件，Python wrapper 与两个实际执行 helper 的字节保持一致。镜像物化 15 份整件原始资源，14 份来源原件每次 H2 读取重新核对；LocalPlayer 是否已安装按实际观察记录，不依赖 operator 文件总数，也不修改既有硬链接。来源短暂缺失、精确恢复后的同 ID 重试、切场景和重启后的临时来源拒绝均按实际请求验证，临时 buff / UseID / origin 未持久化。原件安装说明与本段是不同证据层级。

本次仍是原 NormalAtk.OnAbort 清理的有限服务端闭环；122004 的另一动态表达式、12201 完整召唤生命周期、客户端 12202/12203 视觉召唤、物理、移动、演出及真实游戏 UI 未验收。协议来自固定 4.6 字段与本机调用链，合成 KCP 请求不等于官方客户端已实际触发这一操作。
