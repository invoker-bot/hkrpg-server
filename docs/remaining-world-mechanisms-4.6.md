# 剩余机关与挑战宝箱：2026-10-21 第二轮

此前待解析的 80 个目录项中，本轮补齐 **39 项**，仍有 **41 项** 没有完整、可核验的解锁绑定。规则目录由 396 增至 435，运行时登记 429 项；6 项仍归原有独立玩法处理。旧 396 条规则的完整 JSON 和摘要保持不变，已有完成收据不失效。这里的数量是规则覆盖，不代表所有前置任务、客户端解谜操作和演出已逐一验收。

## 本轮接通的绑定

| 类别 | 宝箱 ID | 推进依据 |
| --- | --- | --- |
| 电缆和跨组踏板 | 20123655、20134651、20003657～20003659 | 控制器或组中的明确宝箱引用、原始完成回调 `1010` |
| 机巧鸟 | 20223651～20223653、20241665 | `SinglePropByOwnerGroupAndID` 中的真实目标编号及 `120120` 回执 |
| 任务完成回执 | 20211672、20211675、20211676、20211678 | 固定任务脚本的目标物件、111120/120120 与正在执行的所属目标 |
| 根茎谜题 | 20222671～20222679 | 棋盘 `1010` 持久化、对应任务目标完成、后续对话结束后才显示宝箱 |
| 布阵谜题 | 20221671、20221673、20221674、20221676、20221677、20221679 | 原始棋盘目标、完成回调、任务脚本与棋盘相同的 PerformanceFinish 事件名 |
| 剧情奖励 | 20121686 | 201050301 对话完成、201050302 唯一宝箱目标与自动触发脚本 |
| 就绪拼图 | 20331662 | 原始 `JigsawSir_02` 的不同完成回执 `11090`，不混用普通棋盘的 `11000` |
| 时锁 | 20433691～20433699 | 精确 `FSV_PuzzleState` / `FSV` / `Treasure` 引用、客户端组属性完成请求、任务等待值 |

跨组操作同时核验控制器组和宝箱组的地图、维度、加载状态与原始定义。根茎、布阵任务还要核验原始子任务，以及控制器的 `scenePropStates` 保存记录；单纯改一个内存物件或预填任务终态不能替代棋盘完成。根茎奖励等待后续对话，布阵奖励等待对应棋盘目标完成。非目标物件不会被整组解锁。

4.6 的 `UpdateGroupPropertyCsReq` 使用已核对的有符号字段 10 `OLBEPIBLDBD`。只接受绑定控制组的 `Srv_PuzzleState=1`；第 7、8 关另接受在第一阶段之后的 `Srv_KindlingFall=1`。黑屏结束回执写入独立的 `late` 标记及 `FSV_TimeLock_n_Late`，重登可从两阶段之间继续。不能通过发送第二阶段跳过第一阶段，也不能把初始为 3、尚未开始的时锁直接完成。楼层声明同时核验原文件和内存加载版本。

组属性、楼层值和解锁记录在一次玩家保存中写入；失败时一起恢复。先持久化，再发送状态通知和任务事件。解锁没有奖励，开箱继续走原有 `worldChests` 投递收据；重复领取拒绝。合法重复组属性回执返回当前值，不追加完成或奖励。未知字段、重复字段、畸形字符串、溢出及非法属性值不写进度。

机巧鸟 `20241665` 的实例脚本在同一固定资源提交中存在，之前未纳入稀疏下载和安装目录。本轮将 `Config/Level/LevelInstanceGraph` 加入两份清单，增量补装 1,126 份原始文件；完整冲突审计先于复制，既有资源和原始清单不覆盖，新增独立补充清单。该脚本的 `LogisticsRobotReachEnd` 明确发送 `120120` 给 `Bird_Chest`，由原始物件值绑定具体宝箱，不再误判为互联网资源缺失。

## 公开来源核查

运行数据继续固定为 [DimbreathBot/TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b](https://github.com/DimbreathBot/TurnBasedGameData/tree/724b139d8c9c32d12552eb95745a4fee72bfe48b)，没有替换为其他版本。本轮只读核对的外部源码、数据及固定提交如下，没有导入第三方实现代码、引入其运行依赖或执行参考仓库的二进制。

- [Mar7thLover/March7thHoney-OpenSource@fe00bdbda3c28ca33d01f171566aa35b5ab95083](https://github.com/Mar7thLover/March7thHoney-OpenSource/blob/fe00bdbda3c28ca33d01f171566aa35b5ab95083/GameServer/Server/Packet/Recv/Scene/HandlerUpdateGroupPropertyCsReq.cs)：`HandlerUpdateGroupPropertyCsReq.cs` 将 `OLBEPIBLDBD` 传入组属性更新，匹配本项目 4.6 协议字段 10。其通用属性回显没有提供剩余机关的条件和目标。
- [mikugirls/DanHengServerResources@f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3](https://github.com/mikugirls/DanHengServerResources/tree/f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3)：引力球组 `F20422001/G262` 的九个 `LoopWaitServerFSV` 节点仍只有类型/启用标记；没有变量名、比较值和宝箱目标。
- [Luotianyi-0712/DanhengServer-Resources@c86edc60839614a197e196658429ae0e2056bcd9](https://github.com/Luotianyi-0712/DanhengServer-Resources/tree/c86edc60839614a197e196658429ae0e2056bcd9)、[gucooing/hkrpg-go-Resources@2abd7d6104c1b48d4c034bb9c016e025c858d7b9](https://github.com/gucooing/hkrpg-go-Resources/tree/2abd7d6104c1b48d4c034bb9c016e025c858d7b9)：较旧版本，没有取得可匹配当前地图的完整参数。
- [awc21/DanhengServer-Public@64c79d170f6aac42a3adfefad4bc119d58fe1cd7](https://github.com/awc21/DanhengServer-Public/tree/64c79d170f6aac42a3adfefad4bc119d58fe1cd7)：引用的 EggLinks 资源仓库请求返回 404，不能作为已取得资源。
- [yuvlian/echium-server@3ca5bd5c63ec00114a2ca522466477e4e245b376](https://github.com/yuvlian/echium-server/tree/3ca5bd5c63ec00114a2ca522466477e4e245b376)：场景处理和资源加载源码未提供这些组事件的完整定义。
- [benzhu5055/pearl-sr-work@b118505316bd6dab25fe16c4e210a4df68e4d9b1](https://github.com/benzhu5055/pearl-sr-work/tree/b118505316bd6dab25fe16c4e210a4df68e4d9b1)：`resources/res.json` 为场景实体、位置与状态表，没有补出被剥离的服务端事件条件。文件 SHA256 为 `c100f2f20d2f90e31db66dd9c978f4f7f07794e048c767631b9560a67a710552`。对 xeondev 原仓库的资源请求返回 403，未声称检查该响应背后的内容。

原始脚本和表行仍逐项固定摘要。公开可读取不等于获得重授权，未将参考仓库中未知许可证代码复制进项目。检索与抽样证据保存在忽略目录 `build/remaining-mechanisms-20261021/sources/`。

## 尚未接通的 41 项

| 数量 | 宝箱 ID | 具体缺口 |
| --- | --- | --- |
| 21 | 20422686～20422688、20422691～20422699、20451691～20451699 | 引力球的关卡完成值与各宝箱目标之间没有完整映射；组脚本的循环监听节点缺少字段，不能按数组顺序或 ID 尾号猜测 |
| 9 | 20423683～20423691 | 三个区域挑战组有多个宝箱，区域完成值及 `ChangePropState` 的目标参数缺失，尚无完整的发起、完成、奖励对应关系 |
| 2 | 20211674、20211681 | 任务罗盘、怪物与奖励的组合条件未取得；不能仅凭罗盘完成或卸载怪物立即发奖 |
| 1 | 20211682 | 任务回执实际指向 300008，宝箱为 300004，缺少两者之间的完整联动 |
| 3 | 20311684、20322662、20332662 | 动态碎片数量、墙面/桥梁状态、隐藏主线控制器的加载与终态条件未完整接通 |
| 2 | 20331626、20331665 | 特殊钟表奖励的楼层完成值与宝箱绑定参数不完整 |
| 1 | 10401680 | 收集虫任务、楼层值和奖励的服务端联动节点参数缺失 |
| 1 | 20433651 | QTE 挑战的发起、成功结果及奖励门槛未形成完整绑定 |
| 1 | 20481657 | 转移挑战的循环楼层值监听节点缺少完成条件和目标参数 |

这些 ID 继续保留在生成器的 `pending`，功能登记为 `partial`。上述核查仅说明已检查的来源没有补全参数，不代表整个互联网不存在其他完整资源。本轮没有用直接开箱、默认成功或无条件解锁替代这些玩法。

## 复核方式与边界

```powershell
python -m unittest discover -s scripts/tests -p test_world_mechanism_catalog.py
python -m unittest discover -s scripts/tests -p test_world_mechanism_resource_install.py
python scripts/generate-world-mechanisms.py --check
.\gradlew.bat test --tests '*RemainingWorldMechanism46Test'
.\scripts\build.ps1 -TestForks 8
python scripts/smoke-world-chests.py --remaining --output build/remaining-mechanisms-20261021/native-remaining.json
python scripts/smoke-world-chests.py --dynamic --output build/remaining-mechanisms-20261021/native-dynamic.json
python scripts/smoke-opening-stage-win.py
python scripts/smoke-server.py
```

新增原生探针覆盖 12 个代表宝箱及不同回执类型，使用完整原始资源和独立测试账号/H2 库。只预置测试位置、所属任务及必要的前置历史，时锁只预置开始值 0；不预写控制器完成、时锁值 1、解锁、领奖或收据。每个阶段经实际 UDP/KCP 推进后正常停服，检查 H2 中的控制器、任务解锁、第一/第二阶段、领奖记录及真实货币材料，再启动同一 UID。前置任务的取得与客户端本地解谜动作仍是明确的验证边界。

`20121686` 领取后完成主任务 `2010503`，原始 G54 组只在该任务 Accept 阶段加载，因此整个宝箱组会卸载。探针分别核对领取响应、重复领取拒绝、领取通知、H2 收据与实际奖励，随后要求重登不再出现该组，不能误要求它继续显示为 ChestUsed。根茎谜题也按原文核对完成后控制组卸载、保存记录仍保留的行为。

就绪拼图 `20331662` 的 `11090` 回执将棋盘从 TriggerEnable(10) 切换为 TriggerDisable(9)。探针按这条原始 InteractConfig 核对场景和 H2 终态，不能套用普通 `11000` 拼图的 Closed(0) 预期。

新增 Java 回归共 47 项，覆盖全部 39 个新增绑定、两关时锁第二阶段，以及保存失败回滚、错误回执、楼层声明被修改、畸形协议和绕过任务前置等拒绝路径。修复前用原运行包 `b168f4d13feb494204a5b6b0235c53dec2ef34c40c0b9de59ec04e97deef962d` 锁定了 39 个绑定及两关第二阶段的失败；生成器和资源安装另有 4 项 Python 回归。RED 日志及最终验证报告位于 `build/remaining-mechanisms-20261021/`，该目录不纳入 Git。

实际协议验证还发现，正式任务加载器附加的 `task` 脚本缓存会使任务元数据比较失败，导致合法回执返回 2400。测试夹具现按正式加载过程附加原始脚本，再锁定此问题；校验只排除这个运行时缓存字段，修改任务目标仍会拒绝。此前没有附加缓存的单元结果不能替代最终完整资源的协议验证。

补装前后的全资源摘要比较确认：原有 86,345 个文件逐项保持一致，新增 1,126 个实例脚本及一份补充清单，总计 87,472 个文件。新资源清单 SHA256 为 `53d2a1dc1e2942e79afbde6f0262efd7a86f8fabc0129b0bd804f814384c16f7`。

最终服务端代码的完整 `scripts/build.ps1 -TestForks 8` 构建通过：569 类、14,768 项测试，失败/错误/跳过均为 0，2,173 个编译与资源输入在构建窗口内保持不变。47 项新增回归全绿。候选 JAR SHA256 为 `081a980855bfd4f5c646a471da764e11bf71248bfb672f81c4f3d9c35a1fc364`；`compile-proof.json` 记录完整测试统计，`runtime-cache-red.xml` / `runtime-cache-green.xml` 保留任务缓存缺陷的 21 项失败及修复后的 47 项通过。

同一最终 JAR 的协议与数据库检查全部通过：

| 检查 | 实际请求 | 正常停服检查 | 同 UID H2 进程重启 |
| --- | ---: | ---: | ---: |
| 新增流程，12 个代表宝箱 | 271 | 39 | 38 |
| 原有动态机关，9 个代表宝箱 | 306 | 39 | 38 |
| 新号开场、首战与破坏物 | 92 | 2 | 1 |
| 合计 | 669 | 80 | 77 |

两组地图探针均核对 1,715 个原始宝箱目录项、真实货币与材料、各阶段保存记录和重复领奖拒绝，没有预写目标完成或重置奖励基线；原有 396 条规则及收据保持兼容。Dispatch 独立账号在进程重启后恢复。开场检查曾发生一次 30 秒场景查询超时，失败日志保留；全量测试结束后，同产物重试完整通过，没有修改超时时限。`build-proof.json` 汇总上述结果，真实游戏客户端操作及尚未接通的 41 项仍不在通过范围内。

## 常驻服务更新

功能提交为 `f49ee1e`。2026-10-21 04:40（Asia/Singapore）正常停止 `hkrpg-local-server-1` 后备份 11 个数据文件，并逐项比较 SHA256；备份位于 `.local/backups/remaining-mechanisms-20261021-044036/`，旧镜像保留为 `hkrpg-local/server:rollback-remaining-mechanisms-20261021-044036`。

服务现运行镜像 `sha256:d8aff56353aee4b4b2b5837606ee2c0caf945bbe54e1d29f003ea6ba7b39908c`，容器内 JAR 与上述已验证产物一致，健康检查、CN/OS Dispatch 和实际 UDP/KCP 心跳全部通过。操作者配置 SHA256 仍为 `6ab12e00346b47718fa765e2b2a88531e8c855efaa25f8eac925155a7f7d194e`。

更新前后只读比较确认 UID `10001` 保留：7 个已完成开场目标、卡芙卡临时队伍 `[1001005]`、已完成开场门 `100010105`，以及已有宝箱阶段、机关记录和物件状态。没有重置账号或恢复旧数据库。证据为 `deployment.json`、`compose-verification.json` 和 `live-preservation-proof.json`；部署完成不代表 41 项未实现流程或真实客户端解谜演出已经验收。
