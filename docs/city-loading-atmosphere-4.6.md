# 行政区加载页停滞与气氛组加载归属

## 实际故障

2026-11-02 用户重新进入行政区后，画面从黑屏变为加载条接近结束但无法进图。本轮 PID 5292 的脱敏客户端日志确认，`AdventurePhase._EnterStep2` 创建九个 `MapProp:100` 时，连续读取 `Config/Level/Mission/9999801/BubbleChat_W1_1` 至 `_8` 失败，随后在 `LevelJsonTable.LoadLevelGraphConfig` 抛出空引用，九个异步工作失败。它们对应原始行政区 `P10101_F10101001 / G120 / CrowdChat` 的九个物件实例；运行时编号 195–203 不是原始实例编号。

同一进程的只读等待记录显示：登录响应等待已经结束，加载期间没有网络回包等待项，`loading=true` 持续存在。该证据与本次物件加载失败一致，不能把所有加载停滞都归为未处理查询或服务器性能不足。上一轮只解决雪原到行政区的任务切图接续，不能据此证明城市已成功显示。

## 来源与原因

原始 G120 的 `LoadSide` 和组级 `LoadOnInitial` 均省略。固定客户端的 `RtLevelGroupInfoBase` 与 `RtLevelGroupInfo` 构造函数分别位于 RVA `1da323e0`、`1da31530`；完整构造函数均不写入偏移 `0x74` 的 LoadSide 和 `0x78` 的 LoadOnInitial。字段布局及 `LevelGroupLoadSide` 枚举确认缺省分别为 `Client=0` 和 false。当前设计数据索引可完整读取 G120，九个物件原始脚本引用与服务器资源一致，但被日志点名的 BubbleChat 脚本不在该索引中。

Java 继承数据类的缺省为 `LoadSide=Server`。原有资源读取未区分这类原始气氛组的缺省值，普通场景加载又据此将 G120 下发为服务端实体，触发了本来不应在初始场景创建的脚本。

## 修复范围

`AtmosphereGroupSourceAdapter` 只处理带原始 `$type=RPG.GameCore.RtLevelGroupInfo`、分类为 Atmosphere、没有显式 LoadSide、没有加载/卸载/气氛条件，且 LoadOnInitial 省略或明确为 false 的数据，将其归属解析为 Client。显式 Server/Client 保留；条件控制及初始启用的现有气氛互动、Normal、Mission、无原始类型标签及旧版数据的继承行为保持。原资源盘点有 477 组满足前半段缺省归属和未初始启用条件，其中只有 11 组没有上述激活条件；最终归属修正仅落在后者。没有扩大到全部组的缺省值或完整客户端气氛系统。

首次进图继续按实际归属选择组；`Scene.loadGroup` 的显式入口也拒绝 Client 组，`AtmosphereGroups.allowed` 与任务准入保持一致。正常的显式服务端 NPC 商店 G2 和调查物件 G13 必须继续存在；没有按 PropID 100 批量过滤，没有删除原始资源、补造 BubbleChat 或清空加载遮罩。

夹具保留行政区原始楼层、G1/G2/G13/G120/G164 及实际 MazePlane、MazeProp、NPCData 行和九份来源文件 SHA256，来源为 `DimbreathBot/TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`。静态客户端证据与故障日志位于 `build/city-loading-20261102/`，不是运行依赖。

## 验证边界

初始七项回归在旧实现下有五项因错误归属、首次/显式加载、刷新重建及原生 SceneInfo 泄漏而失败；随后增加原始活跃 G164、显式初始启用标志与条件控制的对照。初次测试编译错误和正向夹具遗漏原始 PropID 120 的记录单独保留，不算作根因失败证据。第一次扩大到全部缺省气氛组时，全量检查发现已有互动 NPC 准入受影响，该轮中止并保留在 `full-broad-role.log`；下一轮资源盘点发现条件激活组也应保留，新增边界测试先失败，较早中止的记录为 `full-before-condition-guard.log`。最终规则限定为没有上述激活条件的未初始启用组。

`scripts/smoke-silvermane-battle.py --city-transfer` 在两场合成胜利和演出回执之后，检查同连接进入行政区、查询和重启时的场景中没有 G120，G2/G13 仍存在；沿用独立端口、合成账号与 H2。该网络流程不播放实际客户端，完整城市画面和后续剧情需要实机验收。

最终九项新回归另在原正式包 `c55605c491d6dd2bee62b5766298de3e60f36a7ca81c8a43435f3080eecbdf3e` 上运行，六项因缺失归属修正失败，三项对照通过；没有通过重新准备玩家进度消除失败。对应证据为 `baseline-red.json` 和同名日志。

组合发布包 SHA256 为 `c27fa1c725e2aa75997859f68dd5ebcbd19a7574a64ab35bd0325f17b251858e`。该包单独通过七类、168 项相关回归，以及 29 次真实 KCP 请求、三次正常停服和三次实际 Player/Morphia 读取；行政区查询及重启均不含 G120，G2/G13、原始落点、已完成到达目标与待进行的后续对白保持，24 份网络流程来源摘要不变。Dispatch 两轮冒烟及账号重启恢复也通过。

发布包更新四个既有运行类、加入两个适配器运行类并更新对应功能目录，其余 23,948 个既有条目内容不变。六个运行类与全量源码构建字节一致；整份组合包的直接验证范围是上述 168 项及网络/Dispatch 流程，不将源码全量测试重复计为组合包全量执行。

最终 `scripts/build.ps1 -TestForks 8 -RerunTests` 完成 674 类、16,304 项，失败、错误和跳过均为 0，用时 11 分 52 秒。最终对照的 3,180 份记录输入未变，全量源码产物 SHA256 为 `88bcebd41861b2173612b63af824f89a5cfade94656106c5bffcc4f3844f120f`；完整 XML 和摘要分别为 `full-xml/`、`full-summary.json`。

## 本地发布

同 SHA256 组合包已更新到 `hkrpg-local-server-1`，镜像为 `sha256:e1c209709be6d544892690ebaf3f061710a67c4b96fec5dbffcb88b4dbe9b116`。正常停服后逐文件备份 53 份运行文件及凭据，位置为 `.local/backups/city-load-20261102-130746/`；15 份配置和保护文件摘要及其他容器身份不变。只读就绪、Dispatch 和游戏心跳通过。

部署前后直接数据库投影相等：原始落点、全部主/子任务，以及玩家和玩法字段摘要一致；`101020101` 仍为 DOING，没有代替玩家完成城市对白。核对记录为 `progress-preservation.json`，停服后的原始数据库保留在上述备份中。

## 实机复核中断

Computer Use 已实际观察到 PID 5292 的游戏窗口仍停留加载页；更新后仍显示加载与网络等待动画，尚未观察到城市画面。准备通过正常窗口关闭并重启客户端时，用户按物理 Esc 停止了 Computer Use；本轮随即停止客户端交互，没有确认退出动作是否执行，也没有继续输入或把网络验证标为实机成功。需由用户从原桌面入口重新启动后，核对新会话能否进入行政区、加载完成，以及新日志不再出现九个 BubbleChat 空引用。此次不声明完整客户端进图或后续剧情已验收。
