# 4.6 战斗、日常与首世界事件集成验证

2026-10-11，本批完整运行 `scripts/build.ps1 -RerunTests`，487 类、13,215 项测试全部通过，失败、错误和跳过均为零。17 类、479 项定向检查也全部通过。完整测试 XML 保留在 `build/parallel-full-build-tests`；2,477 份编译输入在运行前后没有变化。

全部正向网络验证使用同一份 `build/parallel-gameplay-runtime.jar`，SHA256 为 `3948948ca7cba698c4a6c4e0fdb1c40bb45f7a3815f8753ddb00f43a5c8d9163`。全量运行后的 `build/libs/hkrpg-server.jar` 与它相同。汇总证据为 `build/parallel-gameplay-verification.json`。

| 流程 | 新增专项 | 原生请求 | 正常停止 | 同 UID H2 进程恢复 | 实际覆盖 |
| --- | --- | --- | --- | --- | --- |
| 场景攻击逐目标结果 | 18 | 110 | 4 | 3 | 三次原始关卡普攻入战、正目标结果、实际战斗 ID、胜利及错误/重复报告拒绝 |
| 十五条原始日常资格、两条移动委托 | 50 | 212 | 6 | 4 | 原始对话和实际楼层移动，受控两日每轮 5000 信用点与 200 活跃点、防重、归档与恢复 |
| 原始首世界事件 40398 | 28 | 127 | 3 | 2 | 原始七站进入、八场来源战斗、祝福菜单暂存/取得、最终首领与终局恢复 |
| 已提交的吉祥物流程复查 | 15，包含在全量中 | 229 | 4 | 3 | 原始十二段交互、来源战斗、三个新宝箱与活动目标，保留前序进度 |

初始行为失败基线分别是攻击 9 项中的 6 项、日常 50 项中的 36 项、宇宙事件 4 项中的 4 项。后续扩大专项范围并验证保存失败、身份校验及重入保护。初版配方重新加载用例错误清空了隔离地图；最终用例只重载配方，当前场景、来源和唯一租约校验继续保留。

攻击字段另对本机固定 4.6 DLL 和元数据完成 47 项只读核对，不加载 DLL 或启动游戏。两组新增来源 Python 共 6 项通过。来源交付检查保留 151 份冻结协议，未修改生成协议类或版本号。70,615 份既有资源的指纹保持不变；参考数据固定为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`，运行 Java 为本项目实现，未引入参考仓库的二进制或未知许可运行代码。

新增默认 `RogueDialogueRewards.json` 在缺失时安装，现有运营文件保留。40398 的原始三种选项类别为碎片、随机祝福和不领取；默认碎片 100、抽取策略及生产房间池属于本服配置，原版奖励公式尚未验证。事件配方可显式关闭；关闭分支另通过 `build/parallel-rogue-disabled.json` 检查钱包不变和拒绝请求。旧负向脚本强制终止进程，不能计入上表正常停止次数。认证、默认安装及存储的 HTTP 检查见 `build/parallel-dispatch-summary.json`。

两个日常并行会话曾共用汇总输出路径，本批只计保留报告及匹配目录的一次成功结果。16 个快照、时钟视图和正常停止日志已逐一核对，见 `build/daily-travel-report-log-check.json`；未声称该汇总可区分并发会话归属。

这些网络测试使用隔离账号、配置、数据库和明确声明的前置夹具。战斗胜利来自协议探针上报，日界由隔离时钟控制；它们验证服务端闭环，不等于真实客户端 UI、剧情动画、真实跨日或断电原子性。原七站路线的奇物商店仍缺入口，投射物独立阶段与全部战斗动作、完整剧情及全部活动仍有未完成项，功能目录继续保持 `partial`。

分项规则和来源见 [攻击结果](maze-attack-results-46.md)、[原始日常](daily-travel-4.6.md)、[宇宙事件](rogue-dialogue-rewards-4.6.md) 和 [吉祥物](mascot-4.6.md)。


## 第二批：投射物回调与三条击怪日常

第二批完整 `scripts/build.ps1 -RerunTests` 已通过490类、13,304项，失败、错误与跳过为零；XML为 `build/parallel-gameplay-second-full-tests/test`。2,489份编译输入在完整运行期间无变更，产物与 `build/parallel-gameplay-second-runtime.jar` 相同，SHA256为 `02e5a8c622c785fe126a28789f8d7055a0e280ceaa618d0e2b06c71cdd890ddd`。70,616份现有配置及资源字节不变；审计为 `build/parallel-second-compilation-audit.json`，分层汇总为 `build/parallel-gameplay-second-verification.json`。

| 实际通过流程 | 报告 | 请求 / 检查 | 正常停止 / 同UID H2恢复 | 证明范围 |
| --- | --- | --- | --- | --- |
| 投射物命中、寿命结束、真实准入OnBattle | `build/maze-callback-phases-fourth.json` | 92 / 90 | 1 / 未检查 | 16施放、11来源战斗及逐波效果快照、失败准入无污染 |
| 三条击怪委托各完成两轮 | `build/daily-killmonster-settled-second-fixed-v2.json` | 458 / 368 | 9 / 6 | 30快照；三原怪、5000信用和200活跃点逐轮领取、防重和归档 |
| 3000213旧轮未交付到期 | `build/daily-killmonster-expired-g95-second-fixed-v2.json` | 119 / 95 | 3 / 2 | 7快照；保存的原楼层离场前出现新轮原怪，旧主奖励未支付 |

两份日常报告独占不同输出、lease及隔离目录，共12次正常停止后的只读H2检查、24次独立Morphia读取，数据库/config字节未变。前三次战斗探针失败和日常前两次探针失败保留，但不计入通过数量。普通战斗36信用来自本服既有MonsterDrop算法，源资源只声明AvatarExpReward=36和无数量的货币展示项；完整21条原源证明另见 [掉落边界](daily-monster-drop-source-4.6.md)，不能把普通掉落算作主委托奖励或官方掉率。

迷宫回调13项先有7项实际失败，击怪日常首15项先有12项实际失败；后续同场景未交付跨日34项中1项先失败，修正真实组卸载后通过。回调字段另有65项本机与固定源只读核对。这些证据仍不包括客户端动作画面、完整剧情脚本、全部日常、多包秘技MP生命周期、真实墙钟跨日或断电原子性。

首世界休息区奇物采购在同一产物A的42项场景回归通过后，隔离原生请求发现真实FloorInfo.onLoad会清除一个门框ValueSource，导致完整组校验拒绝合法原NPC。新增第43项使用完整原楼层/索引18/post-load先实际失败，再通过既有onLoad规范化源组修复，保留完整树与磁盘来源校验。修复产物B为 `build/rest-merchant-floorload-runtime.jar`，SHA256为 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b`；B的36类735项定向回归及shadowJar已通过（`build/rest-merchant-floorload-green-tests/test`）。A的13,304项不能作为B修改后的全量验收；B的购买/H2恢复已另行通过：`build/rogue-rest-merchants-floorload-native.json` 的6快照、156请求、26次拒绝、4正常停服和3同UID H2恢复；4次只读H2检查含8次独立Player读取。原七站/G22、原事件祝福612143和最终首领结算均实际核对，钱包258→58只扣一次200，奇物UUID/库存/图鉴/收据跨恢复保持；2,273份来源资源字节不变。该结果使用B，故单列、不混入上表A的数量。本服追加商店配方默认关闭，原G22/N1013客户端菜单只有强化及退出，不据此声称原始奇物商店UI已接通。

分项规则见 [回调](maze-callback-phases-46.md)、[击怪日常](daily-monster-4.6.md) 和 [追加商店](rogue-rest-merchants-4.6.md)。功能目录继续记录partial。


## 第三批：原骑兽目标、每轮独立手机日常与休息区完整图

本批完整 `scripts/build.ps1 -RerunTests` 实际通过492类、13,444项，失败/错误/跳过为零，用时22分56秒；完整XML为 `build/cavalry-phone-full-tests/test`，证据为 `build/cavalry-phone-full-verification.json`。完整期间2,494份编译输入前后未变，验收产物D为 `build/cavalry-phone-runtime.jar`，SHA256 `8d81c933127d7f62b8c022051d5b1d8dd4d8a1a30a4ec917134109c3fd8d7412`。

骑兽目标6029860按原Town/Floor/维度/加载NPC与骑兽标签验证915，保存原接取身份和来源收据，再由原预约领奖路径发放20星琼与物品213×4。104项专项通过；修正夹具后的旧B基线有96项纯行为断言失败。骑兽网络验收使用独立产物C `build/cavalry-beast-runtime.jar`，SHA256 `1bffb1356ea7a719675d06eee8ffe6ea5cb618d507a414bf6239bd61e412f58a`，49请求/74检查、3正常停服/2同UID H2恢复，3次只读存档检查；另1次明确前置补齐不能计作只读。C的原NPC与20/4实际库存防重证据不混入D的网络数量。

原始消息日常3000602绑定每个接受日与主/子任务观察身份，保留全局旧历史，隔日重做原Talk、两次真实回复及最终提交。旧B的35项基线有16项纯行为断言失败；原Plane20002实际为Maze，修正该错误限制后35项全部通过。D的四条回复路径及未回复/一次回复/两次回复未提交三个到期案例共615请求/728检查/35快照、24正常停服/17同UID H2恢复；24次只读MVStore检查含48次独立Morphia Player读取。旧轮零奖励、新轮5000信用点/200活跃度、完成时间、接取UUID、归档和防重均来自实际保存。

根集成独立逐份比较七个目录的探针日志、24份正常停止日志、实际h2-check文件与报告；审计为 `build/cavalry-phone-native-root-audit.json`。HTTP认证/存储的D检查为 `build/cavalry-phone-dispatch-summary.json`，两轮通过且账户恢复；该旧脚本终止进程，不能计作正常停服。70,616份原运营配置/资源逐字不变（`build/cavalry-phone-operator-audit.json`）。本批22项Python来源/脚本保护检查通过，3,557源码文件与151份冻结协议检查通过。

原G22/G34的四份完整休息区原图独立恢复并与固定Git原件相等：97项相关Python通过，本机13个完整函数、7类型/5任务分支/1谓词，以及枚举双zigzag32消费已核对。原菜单仍为强化与退出，不能称原奇物商店入口已接通；工具不安装脚本、不执行客户端DLL。证据 `build/rogue-rest-graphs-root-verified.json`，分项见 [休息区完整原图](rogue-rest-graphs-4.6.md)。

全部新增运行代码为Java21本项目实现，固定资源来源 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`；未引入未知许可运行代码、未修改生成协议或版本号。隔离账号/资格/运营窗口/时钟均明确为夹具，原目标、回复、任务完成与奖励未预写。实际骑乘输入、手机客户端与NPC演出、其他剧情/宇宙/活动、真实墙钟跨日及断电原子性仍待完成或验收，功能状态保持partial。

分项说明见 [骑兽](cavalry-beast-46.md) 与 [手机日常](daily-message-3000602-4.6.md)。
