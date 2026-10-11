# 4.6 首次宇宙查询、昔涟输入与三段短信日常

本批在既有 Java21 / LunarCore 核心上接入原棋盘首次查询、昔涟后续输入、日常3000601，并减少钟表存档查询的重复资源读取。内部协议基线仍为4.2.0，4.6线协议仍为实验适配；全部战斗、剧情、宇宙、活动和游戏客户端验收继续待完成。

## 实现范围

| 范围 | 本批行为 | 验证边界 |
| --- | --- | --- |
| 棋盘首次5547 | 无局时省略可空field8，提供完整永久资料field10；保留原资格、普通场景来源和受损旧局拒绝 | 不创建局、UUID、首奖、Nous或队伍；损坏任务资格的既有缺口已先锁定回归，下批修复 |
| 昔涟1415输入 | 原1460真正释放后的141501、当前施放身份和原30秒寿命，绑定新的独立UseID与原Free END分支 | 不借用1408付款，不推定投射物物理或客户端动作时序；原增益没有UseType，不能推定单战消费 |
| 日常3000601 | 原300060001、300060101、300060102及电话1211400的三条原回复路径；当前日、本轮任务身份、保存失败回滚 | G84真实实例是报告门槛；客户端CustomString/Act及演出轨迹尚未完整执行或验收 |
| 钟表存档查询 | 一次校验内共享已通过完整SHA核对的定义，下一查询重读当前字节，公开加载入口继续核原源 | 每份卡牌表在目录查询从16次降至1次；不把读取次数解释成网络延迟或官方评级公式 |

完整源数据固定为TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。本机GameAssembly及元数据的完整SHA仍由只读解析器验证；未执行参考仓库中的下载二进制，未修改生成的协议类、版本常量或运营资源。

## 统一验证

正式联合专项14类 / 414项全部通过，零失败、错误或跳过。2,544份源码、测试、资源及构建输入的前后SHA一致，`test --rerun`和实际`shadowJar`已经执行；详见`build/initial-query-cyrene-daily-origin-clock-root-focused-verification.json`。

共同产物为`build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256 `258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1`，包含22,407个类文件。冻结记录明确保留当时全量尚未开始的事实。随后`scripts/build.ps1 -RerunTests`实际重跑全量507类 / 13,815项，零失败、错误或跳过，2,544份输入稳定；报告为`build/initial-query-cyrene-daily-origin-clock-full-build-verification.json`。专项已执行shadowJar，全量复用同包，shadowJar为UP-TO-DATE。

独立验证另包括昔涟26项与相邻秘技回归共827项、首次查询与既有棋盘回归83项、日常及观察身份184项、钟表11项新回归与838项既有回归。首次查询、昔涟和初段日常已在不可变旧产物上产生真正行为断言失败；编译、夹具旧预期与工具错误分别保留，不计生产失败。

| 同包实际KCP路线 | 请求 | 检查 | 正常停服 | 同UID H2恢复 |
| --- | ---: | ---: | ---: | ---: |
| 棋盘首次查询 | 48 | 96 | 4 | 3 |
| 昔涟原Free END | 258 | 262 | 3 | 2 |
| 3000601原settled case0 | 196 | 239 | 8 | 7 |
| 钟表既有评级/查询/恢复 | 1,901 | 未汇总 | 25 | 24 |
| 合计 | 2,403 | 已汇总部分597 | 40 | 36 |

棋盘首次查询完成4份只读H2检查和8次独立Player读取。四轮查询前后完整Player、钱包、物品和队伍保持相等。资格种子与实际生产refresh按原奖励18013104的5000信用单独归因；首次奖励109011没有被认领。原报告`build/chess-rogue-initial-query-current-native.json`的第三次登录比较曾把明确资格奖励重复归为登录变化；纯JSON守卫先断言失败再修正，23项全绿。只读补充`build/chess-rogue-initial-query-current-native-attribution.json`确认三次真正登录无增量，四次Query仍零写；原报告与执行字节保留，没有把改后脚本冒称原48次执行工具。

昔涟首轮实际117条回复及两次入战通过后，工具在31秒等待中超过25秒KCP空闲上限，未进入到期断言；失败目录保留，整体未计通过。工具先锁定保活回归，用只读心跳保持连接后重跑，正式源码保持冻结。唯一整体通过报告为`build/maze-cyrene-ability-current-native-second.json`：三个阶段分别201/31/26请求，9次Player和9次Avatar实际Morphia读取。真实31,017毫秒等待、五次只读保活，四次Free END WIN与重复结算保持141501且不再扣点，其中一次MP为0；到期普通攻击对照不带141501。临时身份和UseID在两次正常H2恢复后清除。

3000601报告为`build/daily-message-3000601-current-native.json`，只实际执行settled case0：受控D0原回复121140102、D1原回复121140104，三目标和四个观察UUID同轮保持、隔日更新，两日各5000信用/200活跃防重。11份网络快照、8次只读存档检查和32次独立Player读取通过；raw日志及来源另由`build/daily-message-3000601-current-native-audit.json`复数核对。另两回复及expired没有新增Native，不与已有单元/工具守卫混计。原CustomString、Act和客户端演出仍未执行。

钟表报告为`build/clock-park-query-ranked-games-current-native.json`，原1至3评级、等待恢复和范围外拒绝均通过，共25份网络快照和25份H2检查。首份prepare明确写隔离等级/任务前置，后24份inspect不修改Player/Lineup；它们核BSON和还原ClockParkState，不是独立Morphia双读。312次真实摆放，原311/411检查点自然经过；初始属性、计分和卡池仍有本服规则及未闭合官方来源。报告没有统一检查数，表中明确留空，不把请求数当检查数；详见[查询说明](clock-park-query-resource-4.6.md)。

## 运营数据与剩余工作

运行前与全部验证结束后分别核对根config.json及70,623份运营资源，共70,624个文件全部SHA不变，根data目录仍不存在；报告为`build/initial-query-cyrene-daily-origin-clock-before-operator.json`及`build/initial-query-cyrene-daily-origin-clock-final-operator.json`。全部新数据库和进程仅使用各自新建的build/smoke目录及loopback端口。

最终目录包`build/initial-query-cyrene-daily-origin-clock-catalog.jar`的SHA256为`90d1c594cb384b5b1e5b7555f919f683d5e76466dbe8557405cf61a4b3053582`，与受测258b包的22,407个类及所有其他资源逐字节相等，只有features/backlog两份JSON变更。目录记录39项功能、487项唯一入口，未提升本批玩法的partial或任何client46验收。目录再打包不冒称新的全量或Native；最终包的两轮`scripts/smoke-server.py`通过真实HTTP登录、校验与同账号进程恢复。三套新工具在根任务再执行23+10+19共52项守卫全部通过，无新增Native重计。

总核验为`build/initial-query-cyrene-daily-origin-clock-final-verification.json`；原Native仍准确引用258b，各分支实际执行工具与迁入版本的SHA差别分别记录于工具冻结清单。钟表首次夹具写入与后24次只读检查单独列示，597仅为三个已汇总路线的检查数量，未补造全局检查总数。

完整客户端界面、输入变体、动作与召唤物理、所有剧情脚本与演出、自然跨日、棋盘结束和首奖、Nous、全部三层及奇物后效、全部活动关卡继续待补齐。新查询显示资格与损坏QuestState之间的既有问题已在旧包上锁定：后续完整Query/Start对照24项中16个纯断言失败、8个合法完成/DOING对照通过；仅build副本的最小草稿修复24项通过，尚未写本轮生产。它不属于本批已修复范围。
