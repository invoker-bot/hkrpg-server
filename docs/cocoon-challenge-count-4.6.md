# 拟造花萼挑战次数解析修复（4.6）

## 实际错误

用户在雅利洛的「回忆之蕾·拟造花萼（金）」难度 I 点击挑战时报「消息错误」。本轮在正式容器的网络命名空间中临时、只读观察花萼消息，取得普通请求 1432 的消息体 `38e90740215001`，响应 1484 为 `4805`，即错误码 5。观察器只记录花萼请求与响应，不保存登录或其他流量，结束后已退出。

该请求是花萼 1001、世界等级 0、场景物件 33、挑战次数 1。次数在字段 10；旧入口只允许 `wave`（字段 4），要求它为正数，还把字段 10 当成未知选项。因此实际默认点击在调用战斗工厂前就被拒绝，并非玩家体力不足。

## 改动范围

`Cocoon46` 将已核实的普通字段 10、快速字段 6（冻结描述符名均为 `HECCOBFBJFI`）解释为挑战次数，调用原来的 `BattleService.startCocoon`。保留此前支持的仅 `wave` 请求；同时携带两种非零次数时拒绝，避免把尚未核实的组合请求解释为免费或缩短的挑战。

普通成功响应回显真实 `challenge_cnt` 和请求物件编号，同时保留已有花萼编号、波数及实际 `SceneBattleInfo`。次数必须为正且不超过资源上限，战斗工厂仍核对全部关卡和体力；入战本身不扣费，沿用原来的结算扣费与防重复逻辑。未知字段、宽整数别名、重复字段和未实现的嵌套上下文继续拒绝。

本次不修改生成协议、命令编号、4.2 路径、资源、玩家存档或版本常量。场景物件编号仍沿用原有边界，没有新增地图物件身份校验；助战、周本上下文等玩法不因此宣称完成。

## 来源核对

冻结协议为 `protocols/4.6/CmdSceneType.proto`、`CmdAdventureType.proto` 和 `Common.proto`。本机客户端通过项目已有的只读元数据/指令解析器核对，没有加载或执行 DLL：

- `GameAssembly.dll` SHA-256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`；元数据：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- `FarmModule.SendStartCocoonStageReq`（方法 589969，RVA `0xce6de80`）及普通发送器 634442（`0xd9859e0`）把次数写到请求实例偏移 `0x24`；请求 `ANHIGPKMCNG.WriteTo`（233101，`0x1e6319f0`）以标签 `0x50` 写出该值，即字段 10。实际一波点击也只发送字段 10。
- 快速发送器 634443（`0xd985aa0`）将次数写到 `0x2c`；`JLGGPEBAIMB.WriteTo`（150116，`0x1e74a780`）以 `0x30` 写出，即字段 6。
- `StartCocoonStageScRsp` 的原生常量确认 `challenge_cnt=15`、`prop_entity_id=1`。`AdventureModule._OnStartCocoonStageRsp` 仍在成功时读取实际战斗资料。

静态报告在 `build/cocoon-challenge-20261102/client-*.json`，实机失败包在 `observed-cocoon.jsonl`。`src/test/resources/fixtures/cocoon-challenge-count-4.6.json` 保存这份请求、原始花萼 1001 的完整记录及三个关卡记录，并登记来源文件摘要；测试使用生产资源反序列化器及真实战斗工厂。

## 验证边界

`CocoonChallengeCount46Test` 共 9 项：实际失败包、普通/快速单次与资源上限 24 次、原波数请求、次数缺失/越界/混用、重复/宽整数/未知/嵌套/截断请求、体力不足、认证与已在战斗边界。旧代码下四项因错误码 5 明确失败，其余五项通过；最初测试夹具误用普通 Gson 解析资源哈希的失败单独保留，不计为行为复现。

`scripts/smoke-farming-mission.py --native-count` 在独立配置、账号和 H2 中发送这两类原生次数，核对真实三波与一波战斗、40 点体力结算、任务推进、防重复、同 UID 重连与 H2 进程重启。合成任务及进程终止方式保留原脚本边界；不会在正式账号中制造胜利或修改奖励，也不代表游戏画面验收。

定向 6 类 / 51 项测试全部通过。新版次数和原波数分别完成 27 次原生 KCP 请求，共 54 次；各有三次登录会话，余额从 300 经三波与一波结算变为 260，重复结果拒绝，同 UID 重连和 H2 进程重启保持完成记录及余额。结果分别为 `build/cocoon-challenge-20261102/native-green.json`、`legacy-native-green.json`；同包的两轮 Dispatch/SDK 冒烟也通过，见 `dispatch-summary.json`。

网络探针最初已成功入战和结算，但随后按过时的 `mission_list` 读取已完成目标而失败；现改为核对已有的原生字段 15 历史列表，仍要求活动列表与历史不重复。该失败保留在 `native-green.log`。隔离目录首次使用系统 Java 17 的启动失败另留于 `isolated-native-green.log`，最终固定使用项目 Java 21；没有放宽战斗或任务校验。

全量基线包 SHA-256 为 `26ea900392de2b5090c286a63153a51be67c7f7c3d43faa5df8f44b41e377cec`，659 类 / 16,160 项测试在固定输入下全部通过，失败、错误和跳过均为 0。完整原 XML 在 `full-xml/`，汇总为 `full-base-summary.json`。最终包只将并行编辑混入的 GM 功能目录说明还原为实际运行能力；与全量包相比仅 `features.json` 不同，全部运行类逐字节相同。修正后 7 类 / 52 项花萼及健康接口专项、两种格式共 54 次 KCP 请求和两轮 Dispatch/SDK 冒烟再次通过，最终验证矩阵见 `full-summary.json`，没有把这些专项另算作一次完整回归。

最终包 SHA-256 为 `7f825303a91b58a1c0a39f35ab35050e11a3300b39638cea304f8bffb656c254`。与更新前实际运行包逐 ZIP 条目核对，只有 `Cocoon46.class`、探针及其监听器和 `features.json` 四项变化，无增删条目；原功能目录条目保持相同，只增加本次花萼条目。其他运行类及数据保持原样，见 `jar-diff.json`、`release-catalog-correction.json`。仓库中并行提交的其他功能保留在其源码与文档中，本次发布仍以原来已运行的实现为基线。

本轮源码固定在 `build/cocoon-challenge-20261102/verification-source/`，基于更新前运行源码 `885eeef`，保留本轮花萼文件，不混入并行聊天的其他新增运行实现。3,061 个源码、协议、依赖及构建输入登记在 `isolated-input-hashes.json`。共享输出目录的初次运行遇到并行构建，已中断并保留原日志，不计为通过；隔离验证中调整并发的两次未完成运行也保留记录，不计为完成。最终全量使用 `test --rerun shadowJar -PtestForks=8 --max-workers=8 --no-daemon`，每个 JVM 固定 `ActiveProcessorCount=4`，每进程内仍顺序测试，耗时 12 分 12 秒。

## 本地服务更新

最终包已更新到 `hkrpg-local-server-1`，实际容器 JAR 摘要与上述最终包一致，容器健康。镜像先在独立上下文中构建，通过现有资源逐文件校验后再正常停服；退出码 143、无 OOM。停服后逐文件核对备份 53 份运行文件，原配置、数据库、运行资料及凭据继续使用，备份为 `.local/backups/cocoon-challenge-20261102-052753/`。

配置、排期、凭据及资源清单等 15 份保护项摘要保持一致，其他运行容器身份前后相同。更新后默认只读 Compose 检查通过，实际 JAR、Dispatch 和原生传输健康；见 `deployment.json`、`after-compose-readonly.json`。部署保护还在切换前核对旧容器及产物身份，失败回退只处理本轮安装的版本，不覆盖并行发布。

本轮实际客户端点击只用于复现错误请求；更新后的画面、入战及实际战斗仍需用户重新登录后验收。服务端自动验证不代替游戏客户端验收。
