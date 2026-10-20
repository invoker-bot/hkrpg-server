# 地图机关和挑战宝箱的解锁、领奖与恢复

当前覆盖与剩余项以[第二轮记录](remaining-world-mechanisms-4.6.md)为准：435 条目录规则、429 条运行时规则，原待补 80 项中新增 39 项、剩余 41 项。以下保留此前部署的验证记录。

## 前一轮动态机关覆盖

在前一批基础上增加 66 个宝箱的来源绑定流程，并修正 5 处矿车绑定。当前目录共 396 项规则，其中 6 项由已有火炬及短矛实现保留原收据，运行时 `worldMechanisms` 管理 390 项。全部 328 条旧目录规则的内容保持不变，新增控制器别名不会使既有解锁记录失效。

| 本次补齐 | 范围 | 保存与恢复 |
|---|---|---|
| 拼图 | 27 个宝箱；用 `TargetProp`、`GameplayProp` 及原始碎片条件关联 | 激活指定碎片、逐片收集、全部收齐后开放拼图、完成后解锁；同一拼图的多个明确目标一起保存，失败全部回退 |
| 机巧鸟挑战 | 15 个宝箱 | 原始完成回调 `1010` 与组内“控制器 Open、目标宝箱 Hidden”条件关联，完成与领奖分开 |
| 金色短矛 | 24 个新增宝箱，加上既有 2 个布局 | 按 4、5、6、7 步四种原始序列保存；到达终态后，实际攻击对应盾牌才解锁；重登后已击破盾牌不重生 |
| 矿车 | 5 个旧规则 | 组中保留了非初始加载的旧控制器，实际使用的是后面的替代物件。根据相同脚本与 `UnlockChest` 关联启用对应实例，继续遵守原任务加载条件 |

拼图中间状态使用 `scenePropStates`，解锁使用 `worldMechanisms`，领奖使用 `worldChests`。关联状态在同一次玩家保存中提交，成功后才发布通知。未完成收集、错误步骤、伪造内存终态、缺少绑定物件、来源文件变化和保存失败均不能提前解锁。领取后再次完成或重复领奖不会再次发奖。旧存档中未绑定来源的 `storyValues` 不作为新拼图或短矛的完成证据。

公开资源继续使用原固定 4.6 提交。本次额外查验了以下公开来源，检索结果和原文样本留在忽略目录 `build/dynamic-mechanisms-20261021/source-search/` 与 `references/chest-source-audit/`：

- [mikugirls/DanHengServerResources](https://github.com/mikugirls/DanHengServerResources/tree/f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3)：固定提交 `f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3`。抽查的拼图组脚本仍只有 `ChangePropState` / `WaitEntityServerEvent` 类型和 `TaskEnabled`，没有所需目标参数。
- [Luotianyi-0712/DanhengServer-Resources](https://github.com/Luotianyi-0712/DanhengServer-Resources/tree/c86edc60839614a197e196658429ae0e2056bcd9)：固定提交 `c86edc60839614a197e196658429ae0e2056bcd9`，声明 3.1.0；组配置可用于核对旧布局，不能覆盖本项目的 4.6 原文。
- [gffice/LunarCore-Configs](https://github.com/gffice/LunarCore-Configs/tree/6310647210b5bd8868fd1c64664ef4a6dbfc14c4)：固定提交 `6310647210b5bd8868fd1c64664ef4a6dbfc14c4`。旧拼图布局包含组状态表，仍不能补全 4.6 的动态事件节点。
- [HonkaiSlopRail/pearl-sr](https://git.xeondev.com/HonkaiSlopRail/pearl-sr/src/commit/005d2c12db4781071f4b5b68e8f364587886a710/resources)：检索时固定提交 `005d2c12db4781071f4b5b68e8f364587886a710`，README 声明 4.7 beta，资源更新标记为 4.6.53。公开目录未提供独立的关卡组脚本，未用于替换本项目的正式 4.6 资源。

没有导入这些项目的实现代码或执行其程序。本次依据现有 4.6 的客户端回调、明确物件引用、原始状态及组结构实现受限流程；短矛限定为原始 `DestructableGoldenSpear` 组、明确 `AttachTargetRef`、唯一宝箱及匹配轨迹脚本。不会据此运行任意被剥离参数的服务端节点。

还有 **80 个目录项** 未被当前生成器解析为完整绑定，保留在 `world-mechanisms-4.6.json` 的 `pending`。主要是楼层变量驱动的引力球、时锁、跨组奖励、带任务条件的罗盘和布阵，以及 4 个特殊拼图布局。部分现有客户端回执规则的前置机关仍未接通，因此 80 不是全部未实现流程的准确数量。真实客户端的本地移动、轨迹、桥梁动画、NPC 发起挑战及所有前置任务尚未逐项验收；功能继续登记为部分实现。

原生回归命令：

```powershell
python scripts/generate-world-mechanisms.py --check
.\gradlew.bat test --tests '*DynamicWorldMechanism46Test'
python scripts/smoke-world-chests.py --dynamic --output build/dynamic-mechanisms-20261021/native-dynamic.json
```

动态探针使用完整原始地图和独立账号/H2 数据库。只预置测试角色、位置和矿车所属任务的测试前提；拼图中间状态、短矛步骤、盾牌击破、解锁和领奖全部由真实 KCP 请求产生。每个阶段正常停服后读取实际数据库，再启动同一 UID，分别检查物件状态、解锁记录、宝箱收据与实际货币材料；不预写目标完成。该测试不等同于真实游戏客户端操作验收。

## 本轮动态流程验证

在此前部署的 JAR 上复现 17 项拼图、机巧鸟及矿车回归失败，再复现 5 项短矛回归失败，均为行为断言失败，没有编译或环境错误。基线记录位于 `build/dynamic-mechanisms-20261021/baseline-results/`、`baseline-spear-results/`。

新增的 30 项回归通过，覆盖分步重登、同一棋盘多个明确奖励、任务门槛、错序请求、伪造终态、缺失物件、来源变化及保存失败后的整体回退。`scripts/build.ps1 -TestForks 8` 完整通过 **568 类 / 14,721 项** Java 测试，失败、错误及跳过均为零；`scripts/generate-world-mechanisms.py --check` 通过。Dispatch 与账号存储的进程重启检查也通过。

动态机关原生探针通过 **306 次 UDP/KCP 请求、39 次正常停服、38 次同 UID H2 进程重启**。实际解锁并领取 `10301634、20311667、20242671、20121671、20121676、20413666、20423666、20412666、20413667` 九个代表宝箱，收到九次正确编号的开箱通知；11 个改变过的物件状态、九份完成记录和实际货币材料在各阶段数据库中核对一致。新流程没有重设奖励核对基线，重复完成和重复领奖被拒绝。

同一产物的卡芙卡开场、罐子及普通宝箱回归另通过 92 次原生请求和两次正常停服。合计 **398 次原生请求、41 次正常停服**，JAR SHA256 为 `b168f4d13feb494204a5b6b0235c53dec2ef34c40c0b9de59ec04e97deef962d`；完整构建期间 2,170 个生产、测试及构建输入文件摘要不变。汇总见 `build/dynamic-mechanisms-20261021/build-proof.json`，过程见同目录 `native-dynamic.json`、`native-opening.json` 和 `smoke-server.log`。这些结果验证服务端状态、实际持久化及奖励投递，未替代真实客户端操作验收。

### 本轮部署记录

代码已提交为 `30543b5`，本机常驻服务随后更新到上述已验证 JAR，镜像为 `sha256:f199729b22d0d818169324044688d424b0c9b89df7e6329d0a8995a03fc0b50f`。`scripts/verify-compose.py` 的只读检查通过健康状态、运行产物摘要、挂载、Dispatch 以及 4.6 UDP/KCP 心跳；线上 `/api/features` 也已返回本轮规则覆盖及剩余 80 项的说明，状态保持 `partial`。

旧服务正常退出码为 143，停服后的 11 个数据文件逐一校验备份至 `.local/backups/dynamic-mechanisms-20261021-022520/`，同时保留旧 JAR、配置、清单及回滚镜像。运营配置摘要不变，86,345 个资源文件的清单摘要仍为 `0e3c0e3b9326a0960c76691c8fec415762480bb1cb2efc046fb3aaf4da870875`。

部署前后的只读数据库投影确认 UID 10001 的六项已完成剧情目标、卡芙卡试用角色 `1001005`、机关完成记录、物件状态及宝箱领奖阶段保留，没有重置账号。证据位于 `build/dynamic-mechanisms-20261021/deployment.json`、`compose-verification.json`、`live-preservation-proof.json` 和 `live-feature.json`。

## 前一批资源接入与守卫流程记录

公开资源检索确认：缺少的文件并非全部没有公开。本项目已经固定使用的 [DimbreathBot/TurnBasedGameData](https://github.com/DimbreathBot/TurnBasedGameData/tree/724b139d8c9c32d12552eb95745a4fee72bfe48b) 含有 `Config/Level/Maze`、`Props`、`GroupGraph`、`GroupTemplateGraph` 和 `Config/Props`。此前稀疏下载及安装清单没有包含这些目录。本次在同一提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 上补装 15,422 个文件，没有更换资源版本、覆盖运营文件或改写原始清单。

补装后逐项检查：326 个锁定或隐藏宝箱引用的 149 份不同组脚本全部存在，缺失文件数为零；证据在 `build/chest-unlocks-20261021/group-script-availability.json`。文件存在与服务端节点参数完整是两回事，后者仍有缺口。

同时核对了 [gucooing/hkrpg-go-Resources](https://github.com/gucooing/hkrpg-go-Resources/tree/2abd7d6104c1b48d4c034bb9c016e025c858d7b9)；该快照声明 2.6.0，抽查的同类脚本也缺少部分服务端节点参数，不能代替 4.6 数据。LunarCore 及 March7thHoney 源码仅用于比较，没有导入外部实现或引入运行依赖。资源数据的权利归属与本项目继承的 LunarCore AGPL 许可证分开，不将公开可读取误记为重新授权。

### 前一批接通的流程

从原始组、物件、配置和脚本提取 328 个宝箱的规则：69 个守卫挑战、58 个具有精确宝箱绑定的控制器、201 个具有精确目标的客户端完成回执。其中既有独立玩法继续使用原来的处理器及历史收据；此数量不是新增可玩机关数，也不是逐个客户端验收结果。

- 守卫挑战必须击败原始组中的全部指定守卫。每次击败先保存进度，再移除怪物；只打败一个、单纯卸载实体或退出战斗不能解锁。重登后已击败守卫不重新生成，未击败的仍保留。
- 对已加载的推箱子、魔方、积木投影和矿车控制器，按照原始完成回调与明确目标关联处理。控制器状态和解锁记录写入同一次玩家保存；保存失败一起回退。目录中 5 处矿车控制器标记为非初始加载，仍需对应任务的动态加载流程，不能据此算作已可玩。
- 钟表小子梦境、时流火炬、神谕等有明确 `PropReqInteract` 目标的配置，接受匹配的 `111120` 或 `120120` 完成回执。只有当前地图、维度、组、物件和原始状态匹配才会解锁；解锁不会发奖。
- 解锁记录与领奖收据分开。已解锁未领奖时重登仍可领取，已领奖时恢复已领取状态并拒绝重复奖励。领奖继续使用现有 `worldChests` 的投递、防重及中断处理。

`worldMechanisms` 是新增的玩家保存域，旧存档缺省为空。记录绑定宝箱、来源摘要、地图维度、完成时间和守卫集合。既有成功领奖收据继续有效；没有为运营账号预写机关完成、删除收据或重置 UID 10001。当前原始文件与加载的配置会重新核验，同大小和修改时间的文件替换也不能复用旧缓存。

`scripts/generate-world-mechanisms.py` 生成规则目录及完整原文测试夹具，`--check` 可复核。运行时使用固定规则和数据，不在收到请求时猜测缺失脚本。`scripts/fetch-resources.ps1`、`scripts/install-resources.py` 已纳入新增目录，增量安装仍先检查冲突，保留已有文件和原始清单。

### 前一批验证和边界

9 项原始行为测试在此前实际部署 JAR 上全部失败，基线保存在 `build/chest-unlocks-20261021/baseline-results/`。扩展后的 17 项回归覆盖部分击败重登、解锁未领奖重登、领取后重登、重复完成和领奖、错误完成类型、跨地图实体、存档损坏、来源变更以及保存失败回滚。资源安装另有 2 项回归，包含五类目录的冲突检查。

```powershell
.\gradlew.bat test --tests '*WorldMechanism46Test' --tests '*MapChest46Test'
python scripts/generate-world-mechanisms.py --check
python scripts/smoke-world-chests.py --mechanisms --output build/chest-unlocks-20261021/native-mechanisms.json
```

原生探针在独立账号与 H2 库中验证七个代表宝箱。测试只预置测试角色和地图位置；战斗胜利、机关完成及领奖由真实 UDP/KCP 请求产生。守卫战斗可能发放战斗奖励，因此在全部守卫击败后单独记录一次实际库存基线，再逐箱核对奖励增量；这不验证战斗掉落配方。测试使用实际服务进程正常停服后的 Morphia 恢复，完成状态不得由检查器补写。

仍有 148 个原始 Hidden/ChestLocked 目录项没有被本生成器识别为完整流程，逐项列在 `world-mechanisms-4.6.json` 的 `pending` 中；其中包含此前已由独立玩法覆盖的少量项目，因此不能直接作为全部未实现数量。部分脚本只导出 `WaitPredicateSucc` 或 `ChangePropState` 的类型，缺少条件、参数或目标；另外还有拼图、金色短矛的其他布局、动态怪物、跨组机关及任务条件尚未实现。

客户端完成回执不等于服务端重演解谜步骤。客户端本地棋盘、移动、轨迹与动画、所有前置交互和真实游戏界面仍需逐类验收；本次没有实现通用关卡脚本解释器，也没有自动解锁未知机关。实例宝箱、完整官方掉落和全地图收集统计继续保持部分实现。

### 前一批 2026-10-21 验证记录

17 项机关回归及宝箱、火炬、金矛、普通交互相关回归合计 9 类 / 147 项通过；2 项增量资源安装测试通过。完整 `scripts/build.ps1 -TestForks 8` 通过 567 类 / 14,691 项 Java 测试，失败、错误和跳过均为零。完整构建期间全部生产与测试输入摘要不变。运行时登记 324 个新域规则，另外 4 个已有火炬继续使用原有解锁收据。

同一 JAR 的机关探针通过 162 次 UDP/KCP 请求，实际解锁并领取 `20001632、20111656、20211656、20241651、10301656、10401651、10403660`，收到 7 次正确宝箱编号的开箱通知。23 次正常停服、22 次同 UID H2 进程重启后，守卫集合、完成记录、已领取状态和实际货币材料均符合预期；没有预写目标完成。七个案例分别覆盖守卫、推箱子、魔方、积木投影、钟表小子梦境、火炬和神谕。

卡芙卡开场、罐子和普通宝箱另通过 92 次原生请求及 2 次正常停服，Dispatch 与账号存储重启检查通过。累计 254 次原生请求、25 次正常停服使用同一产物，SHA256 为 `fc92ff46a09bf8504e045b6b80174be6767c5ae5cb04aec3954b15c1d576a248`；汇总见 `build/chest-unlocks-20261021/build-proof.json`，具体过程见同目录 `native-mechanisms.json` 和 `native-opening.json`。

本机常驻服务已更新到该产物和补齐后的资源镜像，健康、实际 JAR 摘要、挂载、Dispatch 和 UDP/KCP 心跳核验通过。旧服务正常停服退出码 143，11 个数据文件逐一校验备份至 `.local/backups/world-mechanisms-20261021-011820/`。运营配置摘要不变，UID 10001 的 6 项已完成剧情目标、卡芙卡试用队伍、当前剧情及宝箱记录保留，没有重置账号。上述结果是服务端协议、数据库和部署验证，真实游戏客户端操作与演出仍未验收。
