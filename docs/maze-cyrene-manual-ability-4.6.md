# 昔涟原 Free 普攻 END 的有限接入

本批补齐角色1415的 `LocalPlayer_Cyrene_00_NormalAtk02`：真实 base 1460 释放产生原141501增益后，新的独立 Ability UseID 可以执行原 Free END 分支。只登记这一份完整原能力及其可核对的来源；1510输入、未知名字、其他角色的Free能力和投射物物理不在本批扩展范围。

## 完整来源与扣点边界

来源固定为 `TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，参考仓库仍在 ignored `references/` 下。`src/test/resources/fixtures/maze-cyrene-manual-ability-4.6.json` 保存以下五份完整原blob及其原字节；安装副本的比较仅规范化CRLF→LF。

| 完整原件 | 原LF字节数 | 原LF SHA256 |
| --- | ---: | --- |
| `LocalPlayer_Cyrene_00_Ability.json` | 58,554 | `b74952ea9bc8545b6a85b2a8f617cd451231fc42388179e31ab1926ed4179777` |
| `AvatarConfig.json` | 232,938 | `9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e` |
| `MazeSkill.json` | 55,434 | `16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e` |
| `AvatarMazeBuff.json` | 100,273 | `31e8a9a6cf52b932435666b657796ea6b00de2d26bf57c1fbd2ccd001afb0ae8` |
| `AdventurePlayer.json` | 41,092 | `aeec485081918f278fe2164ff55242f7eca07258c0296b0144bf801605ccffdd` |

原 base `LocalPlayer_Cyrene_00_MazeSkill` 的 `OnStart` 添加Caster增益141501，明确固定寿命30秒，随后执行 `AdventureSubmitSkill`。原 modifier `ADV_StageAbility_Maze_Cyrene` 的 `WaitSkillInput` 经 `AdventureTriggerTargetAbility` 指向 `LocalPlayer_Cyrene_00_NormalAtk02`。该能力声明 `ManualTriggerType.Free`；`ByHaveAbilityTarget` 的成功和失败枝都声明静态投射物，均没有HIT任务，并在 `OnProjectileLifetimeFinish` 执行相同的 `AdventureTriggerAttack`。两枝除 `HitConfig` 外的服务器动作契约相等，本批没有模拟其附着偏移、碰撞或寿命差异。

原普通攻击表行141501的 `MPCost` 省略，为0；END任务没有显式 `CostMP` 或 `CostMpPhaseType`。原秘技表行141502的费用为1。固定源树缺少其完整Player phase配置，不能据此确认官方base付款阶段。本批真实探针不发送1408：当前Java未预付base1460兼容入口扣1点，Free END不转移该付款、不扣点、不发新的MP通知。未命名field8没有被解释成费用。

原141501增益没有 `UseType` 字段。它在真实30秒寿命内可供不同新UseID使用，不能推定为单战消费；本批没有改成入战后移除。客户端已有普通体证据确认Free分配新UseID，投射物快照保留该UseID并由1460 Writer写field13；能力名字使用field15。此次KCP发送由原调用链构造的请求，未运行官方客户端或观察实际按键序列。

## 服务端守卫与回归

`SkillAbilityInfo` 只登记完整原base→modifier→输入的有限变体；`MazeSkillCosts` 核对五份完整原件、实际所属角色及零费用END契约。`MazeCastState` 要求同一活动Session、当前Scene/lineup、实际持有且存活的caster及真正base1460释放快照。当前增益的到期时间必须与该次释放快照一致且仍有效；手工附加同编号增益、刷新到期时间、另一caster对象或上一Scene的来源不能借用。

`Battle46` 只接受普通攻击槽0、原完整名字及单项LIFETIME_FINISH枚举2。子施放必须使用新的有效UseID；base ID、0、负数、未知名字/标签、HIT和组合标签拒绝。合法不同UseID可以共享仍有效的原增益来源，不能把整个Free阶段恒定去重。失败准入不消耗输入身份或费用，真实原Stage恢复后可重试。来源、临时增益和UseID账本均不持久化；没有新增TTL或协议字段。

不可变旧包 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be` 的实际入口产生20项独立回归、8项纯行为断言失败、12项对照通过；主类输出排除并逐类记录CodeSource。当前 `MazeCyreneManualAbility46Test` 共26项，另6项覆盖身份/寿命边界。修复后26项及既有Maze回归合计827项独立通过，记录在 `build/battle-cast-draft/cyrene-independent-green-v1/verification.json`。根正式联合414项全通过，并确认2,544份构建输入SHA稳定；随后完整构建核实507类/13,815项零失败、错误或跳过，2,544份输入仍稳定。冻结jar时全量尚未开始；全量的shadowJar为UP-TO-DATE，复用此前414项专项实际生成的同SHA产物，不声称全量又生成了第二份jar。

## 本次真实KCP与H2证据

共同冻结产物为 `build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256 **`258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1`**。第二轮实际报告为 `build/maze-cyrene-ability-current-native-second.json`，SHA256 **`f641f22d3600328df21b7ba69b47e137eb8a816625f075c82b4b409180c44d05`**；独立复核为 `build/maze-cyrene-ability-current-native-second-verification.json`。

| 独立进程阶段 | 真实请求/原始回包 | 检查 | 期末MP | MP通知 |
| --- | ---: | ---: | ---: | ---: |
| 首次施放与边界 | 201 / 201 | 194 | 1 | 6 |
| 同UID首次H2重启 | 31 / 31 | 38 | 0 | 1 |
| 同UID第二次H2重启 | 26 / 26 | 30 | 0 | 0 |
| 合计 | **258 / 258** | **262** | — | 7 |

实际完成3次正常停服、2次同UID H2进程重启，UID均为10001。每次正常停服后通过原数据库codec进行3份独立真实Morphia Player读取及3份GameAvatar读取，合计9+9；余额保持1→0→0，原增益、输入来源和UseID账本不在存档中。新Session拒借旧来源，重新真实释放base后才接受新Free ID；第二次恢复MP为0，base不足返回2213且不发MP通知。

网络实际完成5场原Stage战斗：4场Free END（其中1场MP为0）保留原141501战斗快照，均无额外扣点或通知；1场到期后的原普通攻击HIT对照没有141501。每场核对当前真实battle ID、Stage `80030231`、实际NPC对应的完整原波次，实际WIN结算及重复结算拒绝。双目标请求保留两个实际目标的原Stage波次。原始名字/枚举拒绝、同ID合法重试、失败Stage准入重试、不同新UseID、真实无门槛Train入口100000301换场后的来源失效也已通过。

原30秒增益到期使用实际墙钟等待 **31,017毫秒**。工具自己的KCP空闲上限为25秒，因此等待分段发送5次真实只读 `GetCurLineupDataCsReq` 保活；这些请求全部先写原始回包，并计入258次请求。没有通过改时钟、缩短增益寿命或注入过期状态代替等待。

夹具仅在新建 `build/smoke/maze-cyrene-ability-*` 中运行。P20001/F20001001/G91、恢复箱实例300001至300008和故障Stage event为明示控制摆放；真实NPC1002020和原Stage表行未改。隔离console仅授予正式角色1415，不写MP、原增益、来源、UseID、付款或胜利账本。必要7份完整原件物理复制到闭合资源目录，拒绝junction/hardlink借用；2,265份原资源指纹及隔离配置、jar在运行后保持不变。HTTP使用禁代理的loopback opener，HTTP/Mongo端口必须不同，报告及lease均拒绝覆写。

首轮工具在117条实际回包、2场真实Free战斗通过后，直接等待31秒超过自身25秒KCP空闲上限，连接在到期断言前被关闭。它只验证前段和1次正常停服，**不计到期行为或整体Native通过**。原失败完整保留在 `build/smoke/maze-cyrene-ability-3r1ax0gr`。原等待算法先产生1项真实工具守卫失败，再用分段只读请求修复；新10项工具守卫通过。本次258/262统计只来自第二轮，不合并首轮计数。

## 可重复工具与冻结记录

新工具为 `scripts/smoke-maze-cyrene-ability.py`，两份Java helper位于 `scripts/fixtures/java/org/hkrpg/server/`，守卫为 `scripts/tests/test_smoke_maze_cyrene_guard.py`。`scripts/fixtures/maze-cyrene-ability-tool-freeze.json` 记录实际second执行输入与交付文件SHA：两份Java逐字相同；Python仅改helper位置，守卫仅改import、helper位置和临时目录。纳入仓库后10项守卫及同SHA的javac/16份schema/12项descriptor预检通过；该额外预检没有启动服务器，不增加Native计数。首次迁移路径的CRLF匹配错误单独保留，不是行为回归。

每次复跑必须使用新的输出名，旧报告及lease不能复用。例如：

```powershell
python -m unittest discover -s scripts/tests -p test_smoke_maze_cyrene_guard.py -v
python scripts/smoke-maze-cyrene-ability.py --jar build/initial-query-cyrene-daily-origin-clock-runtime.jar --output build/maze-cyrene-ability-repeat-preflight.json --preflight-only
python scripts/smoke-maze-cyrene-ability.py --jar build/initial-query-cyrene-daily-origin-clock-runtime.jar --output build/maze-cyrene-ability-repeat-native.json
```

本批保持LunarCore来源、AGPL与Java21要求，不导入未知许可代码，不执行参考二进制，不手改生成协议或改变版本常量。官方客户端输入/UI/动画/碰撞、完整modifier监听、其他Free变体、额外队伍费用修饰器、崩溃原子性和全部战斗玩法仍未验收；原base付款阶段仍明确未知。
