# 4.6 施放、初遇、棋盘商店与哈努弹药验证批次

本批完成有限的四条服务端路径：原 UseID 预付秘技回调、G61 原 NPC 初遇与同日活跃晚解锁、原 Chess 教程至休息商店、普通哈努枪械与弹药账本。Java 内部协议基线仍为 LunarCore lite 4.2.0；新增 4.6 线协议采用已核对的字段及命令适配，没有手改生成协议、修改版本常量来宣称兼容，也没有启动官方游戏客户端。

## 共同冻结产物与全量测试

实际网络使用 `build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar`，SHA256 为 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`。测试与打包期间 2,525 个源码、测试、资源及构建输入摘要稳定，基础 Git 为 `494cae72736a59805401405de2f4c745dedef145`。

`scripts/build.ps1 -RerunTests` 已实际完成，497 类 / 13,622 项 Java 测试全部通过，零 failure、error、skip。新 XML 已独立归档到 `build/gameplay-cast-g61-chess-ammo-fixed-full-build-tests/`；检查报告 `build/gameplay-cast-g61-chess-ammo-fixed-full-build-verification.json` 确认构建成功、输入不变，日志 SHA256 为 `df6f586861d9b9e26682a521d5d38f10e53c61fee5a4c17414f4d414254c0af6`。取消的旧 full build 与旧批次 13,444 项结果均不计作本批验证。

验收后仅更新两份功能目录资源，再执行 `scripts/build.ps1 -SkipTests`。最终目录包 `build/gameplay-entry-final-catalog-runtime.jar` SHA256 为 `422db1928aea3effe08ef10fc57a60eb3a8c11d466e7f39904a3b4f4fc2861ac`；与实际测试包逐条比较，22,399 个 `.class` 字节全部相同，整个 archive 只有 `features.json`、`feature-backlog.json` 两项变化。`build/gameplay-entry-final-catalog-equivalence.json` 保存比较证明，这一步没有重新执行或重复计量 Java 测试。

最终目录包还实际运行 Dispatch/登录/令牌/组合登录及同 UID 进程恢复 smoke，两轮通过，见 `build/gameplay-entry-final-http-summary.json`；独立目录为 `build/smoke/dispatch-5mdxlno_`。HTTP summary SHA256 为 `fa6f48755f5af0d391e4532c80c50336a155395a08e78fe74ed3261f9b15bc4a`，不把它记为正常玩法 stop 或客户端游戏验收。本批七份工具测试共72项通过，日志 `build/gameplay-entry-final-tools-tests.log`；其中 mock 预检仅验证工具边界，没有执行新的 Native 玩法。

## 实际网络与存储恢复

以下四份报告均由共同冻结产物实际执行，不把静态分析、构造日志或先前失败计入成功。

| 路径 | 请求 / 检查 | 正常停服 / 同 UID H2 恢复 | 本批通过报告 |
|---|---|---|---|
| 秘技施放身份与预付回调 | 187 / 212 | 3 / 2 | `build/maze-cast-sequence-current-native-third.json` |
| 哈努激活、普通射击、拾取与恢复 | 110 / 149 | 3 / 2 | `build/wolfbro-ammo-current-native-third.json` |
| Chess 原四战、下一层与奇物4采购 | 92 / 252 | 3 / 2 | `build/chess-rogue-merchant-current-native-fifth.json` |
| G61 初遇、同日晚解锁与两轮 Phone | 140 / 160 | 5 / 4 | `build/g61-first-talk-current-native-third.json` |
| 合计 | 529 / 773 | 14 / 10 | 各独立目录、会话及原报告保留 |

停服资格来自各服务进程实际正常 stop 日志。H2 检查对停止后的数据库采用独立物理副本、只读 MVStore 及真实 Morphia Player 读取，检查器不写入原文档；恢复通过独立新进程、同 UID 及实际 4.6 登录完成。HTTP smoke 的进程退出不归入上述玩法正常停服。

秘技预付记录绑定当前会话、场景和当前队伍，同一 UseID 回调不重复付费，重连及进程恢复后瞬时付款记录为空。G61 原初遇完整观察对象跨五次停止稳定，同一个受控 D0 先登录保存空池、后解锁原 2100003；两轮原 Phone 各实际取得 5,000 信用点及 200 活跃度。前置完成和受控时钟是明确隔离夹具。

Chess 四个 Battle ID 与四份实际 WIN 收据独立保存；742 队伍通知每次实际一次，重放不增加。Start 自动产生的 5,000 信用点来自原资格任务 8013104 的 reward18013104，各场胜利没有普通信用或星琼掉落。实际奇物4取得通知使用命令5647、mode201、action.detail.case894，购买保存成功后先发布取得动作，再同步与返回。恢复不重发取得通知；普通队伍、库存及经典 Rogue 域均保留。

哈努合法 packed / unpacked 目标列表及完整 uint32 被验证，击败凭据和弹药先保存再发布；原始数字词法、完整来源与原出生身份保持。弹药数、每次上报扣弹和单个目标掉落为已公开的本服政策，不冒充官方数量公式。

## 失败基线、来源与运营数据

本批 Java 行为修复保留真实失败与夹具失败的区别。最小联合 RED 为 110 项中 15 项纯 Assertion：日常 19 项中12项、Chess 61项中2项、哈努30项中1项；修复后的最终小集合 187 项全部通过。日常另增两项保存失败保护；首次正向 run 中两项重复 FinishSection 误期望 SUCC，已只修正既有拒绝码预期，不计新生产 RED。更早初遇目录、非法源表复制、协议分派及 Native 工具编码错误均在各功能文档注明，不能累计为玩法缺陷。

来源固定为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`；全部运行时来源保护保留。未导入该仓库运行时代码。项目保留 LunarCore 来源、`emu.lunarcore` 包名及 AGPL；参考仓库继续位于忽略的 `references/`。本机客户端 DLL/metadata 仅静态读取，没有执行参考下载二进制。

哈努最新本机静态核对 439 项通过，数字词法及工具 Python 25 项通过。原 G21 科学计数法经过浮点重写会改变完整来源摘要，因此按真实 Gson 原对象更正 86 份文件中的27份错误摘要，41条原行摘要未改。G61 十份完整本机图与固定原件全文相等；六图曾经缺失、之后加装的历史事实分别保留。Chess 所有原棋盘和物理组均保留，没有改 Act 或追加格子制造原路径。

`build/gameplay-cast-g61-chess-ammo-before-operator.json` 与 `build/gameplay-cast-g61-chess-ammo-fixed-after-operator.json` 核对本批开始时的 70,624 个运营文件，完整字节 SHA256 无变化，未改运营 `config.json`、数据库或已有资源。本批安装需求早先的加装记录另存于对应文档，不用本次零变化覆盖以前的事实。

## 尚未通过的边界

四条成功路径仍不能证明完整玩法。秘技未预付多次 HIT 成本、输入变体、官方动作顺序及物理时序继续开发；G61 没有执行官方菜单演出，3000603 等其它日常及完整剧情仍缺；Chess Query 当前缺客户端读取的永久玩法资料字段10，真实首次 UI、玩法退出、骰子/完整三层生成、祝福与奇物4后续效果继续补齐；哈努爆炸6502、移动怪物 AI、其余关卡机关仍待补齐。受控 WIN/HP/SP 回报不证明官方战斗结算算法，正常停服恢复不证明断电原子性。

最终功能目录仍为 `partial`，4.6 官方游戏客户端验收均为 false。每个新增入口记录实际命令、字段、来源、测试与缺口；没有用处理器存在或空回包把完整剧情、宇宙和活动算作完成。
