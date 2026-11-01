# 列车地图误加载调试传送配置

## 原因与范围

2026-11-01 的客户端日志显示，列车 `P10000_F10000003` 进入场景时，两处 `MapProp:100` 实例加载 `BakedConfig/Config/Level/DesignerBackup/Test_Prop_Teleport.bytes` 失败，继而在 `LevelGraphConfig.FromBinary` 抛出空引用异常。该日志对应实例 3133、3134；它们是客户端运行时编号，不是资源中的物件编号。

原始 `G2 / AreaTeleport` 包含 `300001 / 1F_To_2F` 和 `300002 / 2F_To_1F`，两者的 `InitLevelGraph` 都指向这份测试脚本。该组要求 `SavedValueCondition: Utility_IsDebug == 1`，楼层声明的默认值为 0。相同调试条件还出现在 G25、G28、G44、G79。`GroupInfo` 保留了条件原文，但旧的普通组加载、直接加载和任务刷新没有判断这些调试条件，因而将测试组当作普通场景内容下发。

固定客户端设计数据索引只读检查确认，该测试脚本的键 `89e179bdd79afa97`（十进制 `9935356109229062807`）不存在；与日志中的索引查找失败一致。来源清单及客户端索引证据保存在 `build/debug-scene-load-20261101/`。这里证明的是误加载调试组与本次失败的关系，不能仅凭最近出现日志就将原因归到最近某个提交。

## 修复

`DebugGroupConditions` 仅接管普通 Town、Maze、Train 场景中引用 `Utility_IsDebug` 的组条件，从当前维度的原始楼层声明及已保存楼层值读取实际值。条件保留 And/Or、缺省 And、缺省 Equal 和缺省数值 0；无法解释的调试条件及非法调试值拒绝加载。其他 SavedValueCondition 继续由原有实现处理，本次不声称已完成所有地图的楼层条件系统。

首次进图和显式 `Scene.loadGroup` 共用同一检查；`MissionManager.shouldLoadGroup` 保持一致。任务刷新重新检查调试组，条件不再满足时移除实体并发送整组卸载通知。正常 G26 的同类型空物件与 G121 屏障保持现有行为；没有按 PropID 100 或脚本文件名屏蔽所有物件，也没有删除、补造测试资源或改写正式存档。

## 回归与验证边界

`TrainDebugSceneGroups46Test` 的夹具来自 `DimbreathBot/TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，固定原始楼层、七个组以及选定 MazePlane/MazeProp 记录，并记录十份来源文件的 SHA-256。玩家历史、异常条件和混合 And/Or 条件是明确标注的测试数据。

修改实现前，14 项回归中 9 项因调试组被加载而失败，错误与 XML 已保留；最初遇到的 Gradle 结果目录占用属于环境失败，不作为行为失败证据。随后使用独立构建目录，修复后的 24 项专项回归、19 项原开场组回归及 5 项列车世界序列化回归通过。覆盖首次加载、六个原始维度、显式加载、任务查询、刷新卸载、重建、原始楼层标志下发、正常物件保留及非法状态隔离。

`scripts/smoke-train-debug-groups.py` 使用独立端口、合成账号和 H2，通过真实 UDP/KCP 检查原生进图通知和场景查询，再检查同进程重连及正常进程重启。它不操作正式玩家；验证报告显式保留 `clientGameplayVerified: false`。旧运行包也已通过该探针复现调试组泄漏，见 `native-red.json` 和对应探针日志。最终构建、网络验证与正式客户端进图结果另列于本页后续记录，不把单元或探针通过当作游戏画面验收。

## 2026-11-01 最终产物

以已提交的 `3b61cf2` 为基线，本次补丁通过完整 `test shadowJar -PtestForks=8`：646 个测试类、16,037 项测试，失败、错误、跳过均为 0，用时 10 分 15 秒；2,879 份构建输入摘要保持一致。完整 XML、构建日志和输入清单保存在 `build/debug-scene-load-20261101/`。最初另建的验证工作区未用于此产物；最终构建继续使用原仓库与独立 Gradle 输出目录。

冻结 JAR 的 SHA-256 为 `431642f2ba11037001aa457a7fbfcfb2b9114a9f5231c0db0cd7d67c7516f61d`。同一 JAR 的隔离探针通过 26 次原生请求、14 份场景快照、三次正常注销和两次正常停服；覆盖两个列车入口、同进程重连及 H2 重启。五个调试组均未下发，G26/G121 和实际队伍首领保留，`Utility_IsDebug=0`；十份来源资源、探针源码、JAR 和隔离配置在验证中未变。报告为 `native-green.json`。

同一产物已更新到 `hkrpg-local-server-1`。停服退出码为 143，正常停服后完整备份并逐文件核对 53 份运行文件，备份位于 `.local/backups/train-debug-20261101-213114`；运营配置、凭据、资源清单与 Compose 配置摘要保持一致，其他容器身份未变。更新后容器中的 JAR 摘要与冻结产物一致，默认只读 Compose 检查通过，见 `deployment.json` 和 `compose-readonly.json`。

客户端实际画面验收尚未完成：更新后准备读取游戏窗口时，用户按物理 Esc 停止了 Computer Use。本轮随即停止客户端操作，没有将服务端或网络结果写成实际重登进图成功。后续需从原桌面入口重登列车地图，核对加载结束、正常交互及新日志不再出现 `Test_Prop_Teleport` 加载失败。
