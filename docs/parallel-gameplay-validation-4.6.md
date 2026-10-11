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
