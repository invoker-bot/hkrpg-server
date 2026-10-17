# 4.6 登录后初始化异常修复

## 实际故障

2026-10-18 用户确认原桌面入口可以正常启动并显示 `HKRPG Client Debug`，账号登录通过，但随后出现初始化异常。01:43:00 启动的客户端在 `Player.log` 留下三项明确故障：

- 第 1765 行：`RogueTournModule._OnCmdRogueTournQueryScRsp` 空引用，调用栈地址 `e002c2b`。
- 第 1791 行：`TrainPartyGameInstance._CheckIsTurnLocked` 空引用，调用栈地址 `e2de32a`。
- 第 1820 行：`Failed to get GridFightDivisionConfig for ID: 0`。

服务端同一轮还拒绝了 `GetFriendLoginInfoScRsp.friend_uid_list`，原因是新版字段名包含前导下划线。原始客户端日志不纳入 Git；脱敏的故障片段和原日志 SHA256 保存在 `build/login-variable-error-20261018/observed-client-errors.json`。调试窗口可见和正常启动已经得到用户确认，进入世界尚未验收。

## 字段与修复依据

继续使用项目现有 4.6 描述符和只读分析工具核对已安装客户端，不改生成协议、不操作游戏画面。核对对象为 `GameAssembly.dll` SHA256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3` 与元数据 SHA256 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。

| 回复 | 客户端实际读取 | 服务端处理 |
| --- | --- | --- |
| 差分宇宙 6017 | `JNAKFEHIMFM` 的静态资料 `KIKEAJJLAMH`，其中字段 13 是分区资料 `JGMDJIENFFH`；`e002bda` 至 `e002c26` 无条件读取分区容器 | 显式提供空的静态分区容器，保留零进度和无活动对局 |
| 列车派对 8025 | `MKDPFLPICJB.MergeFrom` 将字段 6 写到实例偏移 `0x30`；`Sync` 将其传给 `_SetCurTurnID`，后者查 `TrainPartyProgress` | 将字段 6 设为真实初始回合 1，提供既有的建筑容器，不发放资金、记录或奖励 |
| 棋盘玩法 8580 | `AMEEHIIPMFP.MergeFrom` 将字段 8 写到 `0x34`；初始化回调把它传给 `GridFightDivisionInfo.Sync` | 使用真实零级分区 1、赛季 1 和子赛季 4，保留零经验、零天赋和未开始对局 |
| 好友登录资料 | 新版命名字段 `_friend_uid_list` 为字段 3 | 为旧 `friend_uid_list` 增加语义映射，保留真实 UID 列表与旧版原始编码 |

列车派对描述符仍把字段 6 命名为 `unlock_area_num`；这里按已核对的解析与消费路径提供初始回合，没有用字段名字猜测语义，也没有修改描述符。`record_id` 保持零。

初始配置引用来自本地 4.6 原表。回归使用 `src/test/resources/gameplay/login-initial-defaults-4.6.json` 中直接筛出的四条完整记录，来源摘要和与原表相等的检查另存于构建报告；因此普通测试不依赖本机忽略的资源目录：

| 资源 | SHA256 |
| --- | --- |
| `GridFightDivisionInfo.json` | `8782977fe8c6553ee2367fc0b60485f1708f8e5495b9ca9e1fce7e4331b64ada` |
| `GridFightConstCommon.json` | `b6b97b20e7dd31936c4a0e8fc304aa81e4e03c6a8ed6095e6e662c9f3a1d1841` |
| `TrainPartyProgress.json` | `1f18c71e49f3e7607accfe67943f387fe10d6adb75de0725db6e0dc65012a5cd` |

## 验证与边界

`LoginClientErrors46Test` 的五项检查在修改服务代码前全部失败，修复后与原有初始化、嵌套资料、协议转换检查共 82 项通过。检查覆盖实际必需容器、真实初始配置、非空好友列表、旧版编码、未认证请求隔离，以及玩家玩法记录不变。拒绝未知或截断参数的原检查保留。

`NativeLoginInitialProbe` 同步增加这些必读字段的检查，避免只收到回复编号就误判成功。全量构建、独立 H2 重启、现有存档副本初始化和部署结果记录于本节后续验证记录。

本次只修复进入世界前的资料契约；差分宇宙、列车派对和棋盘玩法没有因此成为完整实现。默认自动建号与不校验密码继续保留。3011014 临时 NPC、完整演出及中途重入仍需用户进入地图后验收。SDK 的水印警告和其他未实现消息没有作为这三项异常的已证实原因。

## 最终验证与部署记录

`scripts/build.ps1` 在 20 分 54 秒完成，534 类 / 14,427 项，失败、错误、跳过均为零，原 XML 保存在 `build/login-variable-error-20261018/full-xml`。全量编译后发现新回归依赖本机原表，将四条原始记录改为仓库内夹具，随后单独复验该类 5 项全部通过；这是测试输入的便携调整，不是第二轮全量。生产输入和 JAR 在这次调整前后一致，另三份完整原表摘要与四条记录相等核对通过。

最终冻结产物为 `build/login-variable-error-20261018/runtime.jar`，SHA256 `5505e6352068106be66373277dcdc19a657d29b979399779299b4d6feb95c06e`。同一产物通过：

- 独立账号两轮 58 请求 / 58 响应，新增必需容器及配置引用检查、登录完成、场景和心跳全部到达；正常 stop 两次，同用户名改用不同密码、H2 重启后仍为同一账号和玩家。数据报最大 1200 字节，超界为零。
- 已存在玩家的停服存档副本两轮 58 / 58，正常 stop 两次、同玩家重启恢复通过；没有自动新建玩家，也没有拿测试副本覆盖运营库。
- 必需的 `scripts/smoke-server.py` 两轮 Dispatch 与账号恢复通过。该脚本的进程终止不计作正常 stop。
- 源码包检查及冻结 4.2 / 4.6 协议检查通过，没有修改生成协议。

旧产物在强化后的隔离登录探针中按预期失败，原报告保留为 `initial-old-red.json`；旧探针只报告 `ExecutionException`，未输出具体失败响应编号，不能把该条记录作为某一个回包的独立归因。具体字段问题的先失败证据来自五项 Java 回归和上面的实际客户端调用栈。

02:17 将旧 Java 76288 经原控制进程正常 stop，退出码 1000。停服后的 700,416 字节 H2 与配置备份到本目录 `before-upgrade`；数据库 SHA256 为 `4ac70d17f18933b4f9410521614c893e6a9fd4da5608516cc5b3535ab588d9bc`。新 Java 75704 由隐藏控制进程 25496 启动，继续使用原运营配置和当前数据库。HTTP 18080、UDP 23311、内部 Mongo 27079 的监听归属、就绪状态、国服/外服 SDK 环境 0/2、网关和无账号合成心跳检查通过。启动资源与动态观察器警告为零，实验协议提示保留；报告没有掩盖后续运行期的未实现消息警告。

部署前发现根目录 `version.dll` 已变为 `version.dll.1527416366`，文件内容仍是先前修正版 SHA256 `323299a6320f30fd9ad5f5595878d8efd49fc5444d4f51521c679cb59f63b9d2`。改名原因未确认；未运行游戏的前提下，把同一已验证 DLL 恢复到加载位置，保留带后缀的文件。五份既有客户端文件、十份运营 JSON 与原桌面快捷方式摘要不变，没有启动或操作客户端画面。

已重新附加 V6 元数据观察器，真实重试记录到本目录 `acceptance-transport.jsonl`，不记录密码、token、密钥或报文正文。新正常停服信号是本目录的 `stop.request`。本次服务端部署与合成验证已经完成，真实客户端能否进入地图和 3011014 演出仍待用户反馈。
