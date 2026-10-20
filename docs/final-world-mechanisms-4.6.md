# 剩余机关补齐：任务罗盘和贼灵 QTE

2026-10-21，从上一轮剩余 41 项中接通 4 项。通用目录从 435 条增加到 438 条，运行时从 429 条增加到 432 条；贼灵宝箱由独立的 `Mascots` 流程管理。剩余 37 项仍为 `pending`，不能将本次交付理解为 41 项全部完成。此前实现与验证见 [上一轮记录](remaining-world-mechanisms-4.6.md)。

## 接通的流程

| 宝箱 | 原始条件与服务端处理 |
| --- | --- |
| 20211674 | F20211001/G130，罗盘 300001 完成 1010 回执后载入货船守卫 200002；真实战斗胜利后解锁 300004；所属任务 401510104 未接受时拒绝开始。 |
| 20211681 | F20211001/G171，同样按照罗盘、货船守卫、战斗解锁顺序；所属任务为 401510203。 |
| 20211682 | F20211001/G173，罗盘请求指向控制器 300008；任务 401510207 完成后依次对话 208、210、211，其中 210 必须在 F10201001 领取物品 140189，211 返回交回物品；目标 209 接受后解锁 300004，并允许原文的 1120 收尾回执。 |
| 20433651 | F20433001/G151，捕捉组按 0→1→2 推进，完成任务 401572701/702 的对话后进入 3；归还回执 4 保存楼层值 3，才加载 G152 并解锁原本为 ChestLocked 的宝箱。 |

规则均来自 [DimbreathBot/TurnBasedGameData 固定提交](https://github.com/DimbreathBot/TurnBasedGameData/tree/724b139d8c9c32d12552eb95745a4fee72bfe48b)。目标来自罗盘、货船和奖励提示器的明确引用，结合任务目标和组状态进行交叉检查。生成器固定源文件与数值表摘要，不按编号顺序推断目标。没有引入第三方运行时代码或执行参考仓库的二进制文件。

完成回执和领奖分别保存；重复领奖不再发奖。保存失败不发布后续场景状态。旧 435 条规则与旧贼灵文件摘要保持兼容。20433651 的原文类型为普通挑战宝箱，不能把它计入 `CHEST_PUZZLE_MASCOT` 活动目标。

## 仍缺少的 37 项

| 数量 | 宝箱 | 缺失证据 |
| --- | --- | --- |
| 21 | 20422686–688、20422691–699、20451691–699 | 重力球任务有完成楼层值，但监听节点的条件值和对应奖励目标被剥离，无法确定每次挑战解锁哪个宝箱。 |
| 9 | 20423683–691 | 区域挑战每组有三个隐藏宝箱，服务器 ChangePropState 节点没有目标参数；房间/区域值不能唯一对应奖励。 |
| 3 | 20311684、20322662、20332662 | 跨组碎片使用动态楼层计数，墙面/桥梁状态写入参数缺失；主线拼图的隐藏控制器还缺少可验证的激活状态绑定。 |
| 2 | 20331626、20331665 | 特殊钟表的怪物波次、阶段完成与奖励绑定没有完整的服务端参数链。 |
| 1 | 10401680 | 收集虫的答案值、收集过程和奖励状态切换缺少完整参数。 |
| 1 | 20481657 | 转移挑战的楼层值监听只有节点类型，缺少完成条件与奖励目标。 |

本轮还检查了多个公开仓库的固定提交。对八条代表性组脚本逐路径检查，结果如下；“缺失”只表示这些路径，不表示整个仓库或互联网没有其他资源。

| 公开来源 | 固定提交 | 八条脚本检查 |
| --- | --- | --- |
| [DimbreathBot/TurnBasedGameData](https://github.com/DimbreathBot/TurnBasedGameData/tree/74a7b96fba32f00cf495a5c00b0311bd02207aaf) | 74a7b96f | 8 条存在，关键服务器节点仍只有类型，没有待补参数。 |
| [R4nggaa/Idk-Data-Game](https://github.com/R4nggaa/Idk-Data-Game/tree/880c3d698e808b62a1efd519559b3e3bcc2daaac) | 880c3d69 | 3 条存在、5 条路径缺失；存在的关键节点仍缺参数。 |
| [xibei1204/DanHeng-RES](https://github.com/xibei1204/DanHeng-RES/tree/411e0caa3ae227834e7cc4478e4d4b4dcc734a80) | 411e0caa | 8 条路径缺失。 |
| [taunu16/FireflySR-Alpha](https://github.com/taunu16/FireflySR-Alpha/tree/297da2b44140d890c3d1a8ded131e5ce744cfa2f) | 297da2b4 | assets 前缀下的 8 条路径缺失。 |
| [tymarishadethefluent/DanhengServer-Resources](https://github.com/tymarishadethefluent/DanhengServer-Resources/tree/83e0185267178a7c5bc2c5263deb69140e454a28) | 83e01852 | 8 条路径缺失。 |
| [LoveFurina/BalanceCore-Beta](https://github.com/LoveFurina/BalanceCore-Beta/tree/8d21475fc6e9769b11ab1c73c340a9e49f1a9811) | 8d21475f | 8 条路径缺失。 |

八条样本分别对应 F20422001/G262、F20423001/G55、F20311001/G455、F20331001/G330、F10401001/G510、F20433001/G152、F20481001/G278 和任务罗盘公共脚本。逐文件结果保存在忽略目录 `build/final-mechanisms-20261021/sources/parameter-matrix.json`。另检查了 cloudysr、cyrene-sr 和 aluminium 的仓库树/资源引用，没有据此获得待补目标参数。未复制这些不明许可证的数据到运行资源。

## 验证方法与边界

```powershell
python scripts/generate-mascot-extension.py --check
python scripts/generate-world-mechanisms.py --check
python -m unittest discover -s scripts/tests -p test_world_mechanism_catalog.py
.\scripts\build.ps1 -TestForks 8
python scripts/smoke-world-chests.py --final-bindings --output build/final-mechanisms-20261021/native-final.json
python scripts/smoke-opening-stage-win.py --doors --output build/final-mechanisms-20261021/native-opening.json
python scripts/smoke-server.py
```

原生探针使用独立账号和 H2 数据库，将战斗队伍、任务接受与旅行位置作为测试前置；罗盘完成、守卫战斗、对话物品、捕捉组状态、解锁及领奖均经 UDP/KCP 请求推进，每阶段正常停服后核对数据库，再以同 UID 重登。探针库与线上同号角色相互隔离。此验证不代替真实客户端的旋转罗盘动作、QTE 手感、剧情演出以及全部前置任务的取得。

宝箱掉落沿用项目现有的自定义奖励策略；任务奖励采用固定资源中的原始奖励表。

修复前从原运行包提取机关与物件转换类，前置到含新增原文绑定的测试类路径；6 项罗盘回归有 5 项失败，分别锁定提前出现守卫、不支持罗盘交互和拒绝合法 NPC 收尾回执。贼灵的两项初始回归也均失败。首次原生重登又暴露了守卫恢复读取尚未赋值的玩家场景的问题，另用首次构建登录场景的回归锁定。

战斗本身会掉落信用点和材料。探针将战斗响应的物品明细独立累计，再与固定宝箱奖励一起核对真实数据库；不会把战后余额重新设为基线，也不重置背包。跨地图对话同时核对任务物品 140189 的领取、交回及重登状态。贼灵对话另有任务奖励 14015727（40 星琼、60 个 120004），探针按固定原文独立累计，并检查主任务奖励已经持久化结算，重复登录不再发放。

原生 QTE 对话后的场景查询还复现了实际的线程死锁：定时更新持有玩家锁后等待场景锁，`Scene.toProto()` 则持有场景锁，并在核验已完成的贼灵任务时等待玩家锁。线程快照明确报告这两个线程死锁。新增回归在持有玩家锁时观察真实场景序列化线程，确认旧代码提前占有场景锁；测试自身不互相等待，不会挂死测试进程。场景序列化已统一为玩家锁 → 场景锁；修复后的 16 项针对性回归通过。

5 项目录生成器回归及 2 项资源安装检查通过，两份生成目录校验通过。修复死锁后的独立 QTE 原生复测通过：46 次协议请求、7 次正常停服、6 次同 UID 的 H2 进程重启，覆盖捕捉、对话任务奖励、归还解锁、开箱、防重领和最终重登。

四项完整原生复测通过：157 次协议请求、22 次正常停服、21 次同 UID 的 H2 进程重启；4 个宝箱均解锁、领奖并保存为已领取，余额和材料数量按每次奖励准确核对。开场首战、剧情门和可破坏物件又通过 149 次协议请求、3 次正常停服和 2 次同 UID 重启。独立登录/派发烟测的两轮检查及账号重启恢复通过。

最终全量构建通过：572 个套件、14,802 项测试，失败、错误和跳过均为 0，包含本轮新增的 14 项回归。源码及测试输入从构建到验收保持一致，原生探针与候选镜像均校验到相同的 JAR SHA-256：`d570fa2adeee868efee3fbc4abfaaea2d255fd7586a4066fe176bbd0588599a8`。

完整证据位于忽略目录 `build/final-mechanisms-20261021/`，包括 `build-proof.json`、`native-final.json`、`native-qte-diagnostic.json`、`native-opening.json`、修复前的失败回归 XML 及 `scene-lock-deadlock.txt`。通过这些服务端检查不代表剩余 37 项或真实客户端演出已完成。
