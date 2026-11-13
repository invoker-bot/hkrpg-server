# 廉价房间误加载测试木桶

## 原因与来源

2026-11-13 用户截图显示「廉价房间」床边出现发光木桶，NPC 与木桶重叠。原始楼层 `P10102_F10102002` 的 G16 名为 `DreamTest`，包含三个 `101017` 可破坏物、一个空交互物以及两份 NPC 摆放。其中一名 NPC 配置为不初始生成；另一名 NPC 的水平位置与其中一只桶相差约 0.02 个场景单位，符合截图中的重叠现象。截图未包含实体编号，不能将某只屏幕上的桶断言为指定实例。

G16 原始组及楼层 `GroupInstanceCommonMap[16]` 都没有启用 `LoadOnInitial`，也没有声明任务加载条件或服务端归属。继承的 `GroupInfo` 将初始加载和归属分别默认成 true 与 Server，普通组加载又直接放行，因此把这份未启用测试内容生成到了实际房间。

测试夹具 `src/test/resources/fixtures/cheap-room-dream-test-4.6.json` 固定来源 `DimbreathBot/TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，记录九份来源文件的 SHA-256，保留完整楼层、G2/G9/G16 及选定原始表行。G2 是「磐岩镇」房门出口，G9 是已接取 `101060104` 目标时的床边剧情交互。

## 修复边界

`AtmosphereGroupSourceAdapter` 在读取原生 `RtLevelGroupInfo` 时交给 `DebugGroupConditions` 核对完整 G16 原文指纹 `89c20fe38a040d7904e46fe78f1bb8f067812830ec8f6353c0bad3e11e6de588`。场景中的楼层、位面、组编号及资源对象身份也必须匹配，才按未启用调试组拒绝生成。

初始进图、显式组加载和任务准入复用现有检查。任务刷新将已登记的该组连同实体卸载，并发送现有整组卸载通知。普通木桶模型、同名自定义组、旧格式记录和其他原始组继续使用既有规则。完整原文变化后需要重新核对来源，首次修复仅覆盖这份已验证 G16；后续扩展的五组及共用加载漏洞见[后续复查](scene-loading-followup-4.6.md)。

拒绝日志为 `Scene placement skipped: reason=dormant-debug-group`，包含位面、楼层、维度和组编号。复用 `GameDiagnostics` 的限频及重复计数；不修改正式玩家进度、配置、数据库或原始资源。

## 验证

实现前七项回归中五项真实失败，两项对照通过。修复后七项通过，覆盖进图、显式加载、重进、4.6 场景快照、已加载组清理、正常木桶/床边交互/出口保留，以及重复拒绝的日志合并。玩家历史及旧加载器的残留登记由测试明确构造。

专项联合回归、完整构建和产物记录见本页后续验证记录。构建与场景消息检查不代表实际客户端画面验收；本轮没有使用 Computer Use。

## 2026-11-13 首次修复验证记录

- 七项新增回归先失败五项；修复后与列车调试组、开场组和场景诊断联合执行，共 51 项通过。
- 首轮完整构建受系统临时目录限制影响：20,049 项中 1,277 项失败。独立 Java 探针确认系统临时文件能读写，但 `Path.toRealPath()` 报 `AccessDeniedException`；工作区内的同一操作成功。保留原失败 XML，未修改生产路径检查。
- 将本次进程的 `TEMP`、`TMP` 及 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<工作区>/build/cheap-room-validation/tmp` 指向工作区，再复查原先失败的宝箱、活动对白和技能三个测试类，111 项全部通过。
- 同一环境执行 `scripts/build.ps1 -TestForks 4`，725 个套件、20,049 项全部通过，失败、错误、跳过均为零，耗时 9 分 25 秒。此次执行重跑了完整测试任务，编译和 JAR 打包复用同一份未改变产物。
- `scripts/smoke-server.py --jar build/libs/hkrpg-server.jar` 在隔离端口与测试数据库中通过两轮登录、令牌拒绝及进程重启后的账号恢复。正式账号未参与验证。
- 本次运行源码、功能清单、测试、夹具及 JAR 的八项冻结摘要保持一致；九份所选原始来源文件摘要也保持一致。

运行包为 `build/libs/hkrpg-server.jar`，SHA-256：`5639ecb3b9cc1831e109e88c94e36ea5142485e8ca2b4708853c4219f5c51ef3`。完整结果及失败环境证据位于 `build/cheap-room-validation/`，包括 `full-summary.json`、`focused-summary.json`、`full-green/`、`full-original-temp/` 和 `dispatch-summary.json`。

当时的会话对 Docker named pipe 的访问及 `.git/index.lock` 的写入均被环境拒绝，因此该轮没有更新正式运行包或创建 Git 提交。实际客户端重登、桶消失、床边剧情及出口的画面验收仍待新包部署后完成。

2026-11-14 已在合并拳馆入口后的完整工作区重跑测试并验证同包登录、重启与宾馆切图；具体结果见[后续复查](scene-loading-followup-4.6.md#2026-11-14-合并后复核)。本次没有部署场景改动。
