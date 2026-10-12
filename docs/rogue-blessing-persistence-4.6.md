# 经典宇宙战后祝福选择与重置的保存回退

普通战后祝福选择与重置在首次 `Player.save()` 抛出异常时，恢复原菜单及其冻结价格、碎片、奇物、永久收集和共鸣队列。原有活动事件奖励的专用保存路径沿用既有实现。成功保存后才发送祝福、地图、队列或支付效果通知，再返回原生成功回包；失败保留同一请求重试所需的菜单和批次。

`UniverseManager` 的两处变更在首次奖励或支付变更前复制完整 `GameplayState`，失败时调用已有 `restoreMiracleShop`。恢复保留当前 `GameplayState`、`RogueRun`、共鸣 `Plan` 和缓存队伍的对象身份。没有新增公共挂钩、奖励配方、命令、底层效果壳或经典宇宙快捷入口。

## 原始来源与隔离边界

固定资源来源是 `TurnBasedGameData` 的 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。新夹具保留 13 份完整原表：RogueBuff、RogueMazeBuff、ConstValueRogue、RogueTalent、RogueBuffGroup、RogueMiracle、RogueMiracleEffect、RogueMiracleGroup、RogueAeon、BattleEventData、BattleEventConfig、BattleEventSkillConfig、RogueHandbookMiracle。生成时分别记录 Git 原字节与运营原字节 SHA256，并比较完整 JSON 对象。

战斗使用既有固定来源 `rogue-monster-selection-4.6.json` 的 29 条原件：房间 200112、入口 8100201、楼层 P81002_F81002001、G58 及完整怪物、NPC、Stage 和波次。新来源夹具另对其 10 份完整原文件比较 Git/运营 JSON 和原字节 SHA，并核对全部 29 条指针与完整记录相等。加载时沿用现有正常资源适配；没有修改原资源行。

单元测试仅加载原 G58，使用测试角色 199961、显式非终点 Site1→2 路由、显式保存的原天赋 11 资格。奇物 1/2/13/69/70 的持有与两个原始普通祝福为显式存档夹具，不表示生产取得、官方路线或资格剧情已完成。无关库存掉落通过 `BattleService.grantDrops` 测试替身隔离；实际 `Battle`、源 NPC/Stage/波次、`BattleService.finishBattle(WIN)`、房间 clear、菜单生成及保存都执行生产方法。首次保存故障仅在这些准备成功后开启。

协议沿用已经匹配的 4.6 请求 5676：OFEDNNJIJFL 的批次 field5、选择 oneof1474/BPLNKHGAILK 的 buff field3，以及空重置 oneof51/DEMENEAAGAA。测试经过实际 `VersionedPacketHandler`，核对 HandleRogueCommonPendingActionScRsp 的原生 retcode、当前回包与重置菜单 callback。没有手改 generated 或新猜字段。

原始默认天赋价格 `[31:30]` 是无奇物控制；原持有信仰债券 13 后为 21，钟表 70 后为 37，奇物 2 的免费次数和奇物 1/69 的双选/强化菜单沿用已有策略。重载公开费用/资格不会重定价已存菜单。折扣与钟表叠加的取整、共鸣的 3/6/10/14 阈值是已声明的本服规则，不声称完整恢复官方随机或所有费用规则。

## 独立真实回归

不可变基线是 `build/out-followup-core-material-runtime.jar`，SHA256 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`。`red-frozen-v1` 的 24 项结果为 11 项真实断言失败、13 项正向/拒绝控制通过，零错误、跳过或中止；`green-frozen-v1` 的同一 24 项全部通过。基线 12 个生产 CodeSource 均为该 jar；绿色运行仅由 build 目录覆盖 UniverseManager，其余 11 个生产 CodeSource 仍为同一 jar。测试及帮助类、夹具、runner、artifact 前后 SHA 固定；这两次运行观测到的正式源码差异为空，不将其他代理此前或后续的合法变更称为全局冻结。

```powershell
python build/rogue-blessing-persistence-draft/run-blessing-tests.py --label <fresh-red-label> --red-failures 11
python build/rogue-blessing-persistence-draft/run-blessing-tests.py --label <fresh-green-label> --green
```

回归比较完整 Gameplay JSON 树及保存前文档、原菜单、批次、权重/等级/候选/排除集合、永久收集、共鸣 Plan/队列/SP、奇物价格/计数/取得编号和钟表破裂奖励收据。覆盖同请求重试、重复领取、失败后六路并发重试只消费一次、原绑定缺失/错误批次/出场/战斗中/坏 wire/余额不足拒绝。第三普通祝福创建共鸣队列、随后共鸣基本祝福激活 SP 都有精确失败回退；成功通知顺序必须为 save 在前、成功回包在后，失败不得发送通知或成功回包。

实际 Player 文档回归使用隔离内存 Mongo/Morphia。保存失败前后的完整 BSON 相等，失败后与成功重试后各两次独立读取 Player，核对完整 Gameplay、原运行身份、共鸣来源及永久记录。这里的 Player 保存为显式测试替身，仅在没有故障时执行真实 datastore.save(document)；它不证明真实 H2 重启或生产 Player.save 所有相关文档的事务原子性。

保留的无效尝试：`red-minimal-v1/v2` 仅是测试夹具方法可见性编译失败；`red-final-v1` 是 BSON 读取 API 编译错误，均未执行测试。`red-expanded-v1` 的 9 个失败中只有 8 个行为失败，第 9 个是夹具误用奇物 68。正确烽火编号为原 69，纠正后 `red-expanded-v2` 得到 9 个真实行为失败；`green-expanded-v1/v2` 保留同一误用证据，`green-expanded-v3` 的 22 项全部通过。没有用错误夹具或旧 XML 充当最终 RED/GREEN。

## 未完成的验收

根级完整构建实际通过 520 类 / 14,226 项，零失败、错误或跳过；2584 个输入稳定，完整构建产物与下述 Native 使用的 3eee 候选逐字相同。根级汇总脚本在 Gradle 成功后的参数覆盖失败，根代理保留原 runner/log 并只读归档 520 份实际 XML，没有重跑或虚构未捕获的 psutil 指标。故障证明只限于首次保存尚未写入时的本地回退，不包含保存已部分落库、跨文档断电原子性、通知投递失败重发或自动对账。没有给实际服务器注入保存故障。

真实游戏 UI、完整角色操作和官方战斗计算没有验收。博士之袍 11 到战斗效果的确切绑定尚未闭合，仍未实现；`offerMiracles` 没有生产调用者，本批没有伪造奇物选择入口。其他经典事件奖励、全部奇物效果、完整宇宙玩法继续待完成。


## 同一新包的实际 KCP / H2 验收

`build/rogue-blessing-persistence-current-native-v3.json`（SHA256 `003a61fd4ced2d71b0a833a385bc5760cee7af062f633a265fa57ddd5368a9e6`）使用唯一产物 `build/feixiao-selected-item-blessing-console-candidate-runtime.jar`，SHA256 `3eeeab41ec750f60b92c2ab3a536760afab14eb53c71581d34e18bafb02fa05c`。独占目录是 `build/smoke/rogue-blessing-h0ux37tg`；9 个生产 CodeSource 全为该 jar，helper classpath 没有生产覆盖或可变 main 输出。

实际 47 个 KCP 请求 / 105 项检查全部通过：第一进程 33/74，重启进程 14/31；6 个拒绝控制。2 次正常停服、1 次同 UID 进程重启、2 次物理只读 H2 检查与 4 次独立实际 Player 读取通过。原始默认首世界 110/地图101/site1/房间304 的 G18/19/20 三场真实 1460 普通攻击和 PVE WIN 按原 NPC/Stage/全部波次核对；攻击用独立 Ability UseID，未把场景实体编号冒充 UseID。受控 WIN 网络报告不表示重演官方战斗输入或计算。

三场结算由生产代码创建并保存三选菜单。真实 5676 重置按原价 30 扣除一次，109 碎片保留到同 UID 重启，批次 1 和菜单 `[612754,612642,612050]` 原样恢复；重复重置和旧排除候选被拒绝。重启后选择 612754 实际获得 1 级祝福及永久收集，菜单消失且碎片仍为109，原生取得通知先于成功回包；同请求重复被拒绝。查询与拒绝操作前后完整 live/BSON 快照相等，普通角色、队伍、实际物品和钱包无附带变更。既有原祝福册回调只允许源关联的 questStates 更新，并保留全部实际差异，没有以广泛 ignoredFields 隐藏变化。

每个快照屏障检查产物、隔离 config、工具、帮助类及2258份动态收集的源文件 SHA；没有把运营总文件数写死。原始资格图 `12001→11/11007`、`11→4030001+4030002`、`11007→1010403` 及完整原 MainMission 行另有整源审计。上述三个原 CLOSED 资格记录及原天赋11持有是明确隔离夹具，未执行剧情或购买天赋，没有种入 offers、choices、奖励结果或奇物。资格脚本先写完整 before/after 观察，再验证来源和 gate。

重启阶段另有一次明确 fixture checkpoint：真实 Player.save 将仅待写登录时间 `lastActiveTime` 从1791770366刷新为1791770385，完整live/BSON及差异落盘。它不计作只读屏障、祝福行为或保存故障；之后锁定新基线再检查选择。`nativeSaveFailureInjected=false`，保存失败和重试仍只由前述 Java 回归证明，不向实际服务器注入故障。

失败 cohort 保留且不计入 PASS：v1 的 `build/smoke/rogue-blessing-h2a3i5w5` 在发送 KCP 请求前错误比较 Java `file:/` 与 Python `file:///` URI 字面值，0请求、1正常停服、0H2检查；修正为两边 URI→Path.toRealPath 精确比较。v2 的 `build/smoke/rogue-blessing-gqi1lw_k` 只有4个登录/只读请求，资格夹具只种4030006，遗漏原 gate 的三个任务；0 Start/战斗/菜单请求、1正常停服、0H2检查。其实际帧、错误marker、日志及停止后的H2保留，但该失败 seed 的完整live before/after当时在断言后才写，未保留；v3已改为先写完整seed观察，并由离线guard锁定。两次失败均是工具/夹具问题，生产代码未修改。

实际 v3 工具的20项离线guard通过，四个Java帮助类和描述符/fastutil实际存档codec预检通过。正式工具布局仅为guard增加路径fallback并重新执行同20项；五个实际Native工具（wrapper及四Java）逐字相同。正式guard包装与本页新增说明没有冒称用于原v3运行。完整游戏客户端UI、全部路线/事件/祝福奇物及断电或部分写入原子性仍待完成。
