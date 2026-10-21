# 最后 37 项机关与挑战宝箱：兼容实现

本次范围固定为上一轮留下的 37 个宝箱。用户已明确接受：保留挑战门槛、逐项领奖和重登保存；原版服务端参数无法恢复的对应关系，采用本项目的兼容规则并标明。运行时仍以 4.2 协议为内部基线，4.6 为实验性适配。

## 来源与实现边界

原始客户端回执、物件、关卡条件、任务和奖励档位来自 [DimbreathBot/TurnBasedGameData 的固定提交](https://github.com/DimbreathBot/TurnBasedGameData/tree/724b139d8c9c32d12552eb95745a4fee72bfe48b)。新增目录 `compatible-mechanisms-4.6.json` 与原始规则目录分开；记录 10 条流程、37 个独立宝箱、218 个文件摘要和使用到的 Excel 行摘要。生成脚本保留原始完整组配置作为测试夹具。

扩查 15 个公开仓库，包括 [mikugirls/DanHengServerResources](https://github.com/mikugirls/DanHengServerResources/tree/f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3)、[iam-akuzihs/StarRailData](https://github.com/iam-akuzihs/StarRailData/tree/e948a9018d5e5c9a541cce50b3af1466b4644cec)、[gucooing/hkrpg-go-Resources](https://github.com/gucooing/hkrpg-go-Resources/tree/2abd7d6104c1b48d4c034bb9c016e025c858d7b9) 和 [spyfamily/LunarCore-Configs](https://github.com/spyfamily/LunarCore-Configs/tree/bebceb3015deac3791f842aa6b35707965d8273f)。找到的同名组脚本仍缺少目标宝箱、任务参数或完整服务端条件。客户端 `AdvClientChangePropState` 的字段不算服务端 `ChangePropState` 参数。没有把这些仓库的代码或二进制引入运行时。

下表中的“兼容对应”均由本项目定义，未经原版服务端核实；客户端计算拼图、重力球与时间回溯机关的结果，服务端检查原始完成回执、所在场景、来源摘要、顺序及任务接受状态，不声称重做客户端物理或拼图解算器。

| 范围 | 兼容对应与门槛 | 数量 |
|---|---|---:|
| F20422001 重力球 | G229 的 `MissionState=1..9` 顺序完成，分别对应 20422691–20422699；每关需当前原始等待任务。401600110/116/124 的真实对话完成分别对应 20422686/687/688，且必须已有第 4/6/9 关回执。困难模式回执保留，不重复发放剧情奖励。 | 12 |
| F20451001 重力球 | G53 的 `CaseState_1..9=1` 按兼容顺序对应 20451691–20451699，并保存下一关 `CurrentPuzzleID`。 | 9 |
| F20423001 分区挑战 | G164/G30/G167 的 `State=1` 对应三个房间。每房间三个箱子各有独立领奖记录；全领后才写入 `FSV_SubChestCheck=1/2/3`，由原始任务推进下一房间。G265 的显示状态回执不能解锁宝箱。 | 9 |
| 20311684 跨组拼图 | G455 的板与 G455/G379 各一个有效碎片；初始 1 片，必须再收集两片后完成拼图。 | 1 |
| 20322662 跨组拼图 | G340 的板与 G183 的有效碎片；初始 2 片。删除的 G174、G340 旧摆放不计数。 | 1 |
| 20332662 任务拼图 | 原始任务 103290250 正在进行时激活 G211 的板；初始三片齐全，完成 11000 回执后解锁。 | 1 |
| 20331665 钟表机关 | G199、G200 的板按顺序完成，保存 `B2ClockBoyPhase=1/2`，第二块完成后解锁 G328 的奖励。 | 1 |
| 20331626 怪物波次 | G39 电视的原始进入回执启动兼容挑战；G98/G147/G150 三组共 12 个实际怪物逐波击败。原始第四阶段目标缺失，兼容规则在三波全胜后写入 `B1MonsterWave=4`。G39 的替身怪物不计作敌人。 | 1 |
| 10401680 虫群收集 | 使用 `MazePuzzleOrigami` 殖群 12 的 20 个地图目标及其真实实例 ID，逐个交互到 EventOpen；展柜模型不计数。原版缺失的最后答题环节兼容为收齐 20 个后自动解锁，记录 `FSV_BugQuest_Answer=1`。 | 1 |
| 20481657 传送任务 | 原始任务 401590401 的真实对话完成后解锁；任意 floor value 或客户端宝箱状态不能替代任务凭证。 | 1 |

## 保存与领奖

- 每条流程保存来源指纹、首次时间、有限集合内的完成步骤；虫群保存每个有效目标的状态。只接受所在场景的原始有效目标与明确列出的回执。
- 完成步骤、关卡属性和楼层值在同一次玩家保存中提交，保存失败回滚；成功后再发送状态变化。真正击败怪物后才计数，普通卸载不计数。
- 采用已有的宝箱 PREPARED → OPEN 奖励流水。解锁不发奖；领取成功后才显示 ChestUsed。发奖后最后保存失败时保留未完成流水，阻止重试产生第二份奖励。
- 重登从同一份持久化记录恢复碎片、收集目标、怪物波次、关卡属性、解锁和领取状态。旧的 435 条来源指纹不变，已有奖励流水继续有效。
- 兼容目录所属组的原始 `SavedValueCondition` 参与首次加载、直接加载和进度刷新。每个房间领完三个宝箱后卸载旧房间，下一房间按任务条件出现；下一波敌人只在当前波全部击败后加载。该规则不会扩大到目录之外的普通地图组。
- 资源校验缓存仅用于同一场景的显示恢复；每次改变进度或领取仍重新读取校验，文件大小和时间戳相同也不能掩盖内容变动。

## 验证与交付记录

2026-10-21 验证通过：

- 新增 18 项专项回归，保留最初 5 项行为失败及后续 2 项场景加载失败的 RED 记录。覆盖全部 37 箱、顺序/任务门槛、分箱领奖、重登、保存失败回退、来源篡改拒绝和重复领奖防护。
- `scripts/build.ps1 -TestForks 8`：573 个测试类、14,820 项测试，失败、错误和跳过均为 0；目录/安装 7 项 Python 回归及两份目录生成器 `--check` 通过，旧 435 条规则指纹保持不变。
- 同一测试账号连续完成全部 37 箱：801 次原生 KCP 请求、45 次正常停服、44 次同 UID 重登。每阶段核验实际 H2 存档的完整步骤集合，以及原始宝箱奖励与战斗奖励对应的实际库存、货币增量；未预写完成结果，未重置余额。
- 开场剧情门回归：149 次请求、3 次正常停服、2 次同 UID 重登；既有 QTE 宝箱回归：46 次请求、7 次正常停服、6 次同 UID 重登。账号 Dispatch 的跨进程恢复验证通过。
- 传送任务另做独立验证：26 次请求、4 次正常停服；三房间 9 箱另做独立验证：108 次请求、8 次正常停服。原生检查确认旧房间卸载、下一房间按进度加载；最后三个箱子领完后再次登录仍为已领取。

验证证据位于本机 `build/complete-mechanisms-20261021/`，汇总为 `build-proof.json`。测试包与待发布镜像内 JAR 的 SHA-256 均为 `edf52f5a8b2483d2b4412f788b6a7c4e27c7a1119ff3718df8fc64de8635fbb8`。本次验证包含正常停服与重登、保存异常时的回退/防重；未证明玩家与背包分文档写入在断电时的原子性。官方客户端逐点演出与全部前置任务取得仍未验收，原版对应关系仍按上表标注为兼容规则。

部署已完成：实现提交 `3bc722b`，常驻服务 `hkrpg-local-server-1` 正常停服后切换到镜像 `sha256:ec674f22f49cbdd242d5758ed5124cda5f8d0f24b7e323769e3d692a204a79ab`。容器内 JAR 与上述验证包一致；更新后的 Compose 健康、Dispatch 和原生 KCP 心跳验证通过。运营配置摘要和 87,472 个资源文件的清单摘要保持不变。

停止后的 11 个数据文件逐个核对备份摘要，备份目录为 `.local/backups/complete-mechanisms-20261021-091455/`。更新后只读核对：仍为 1 个账号，UID 10001 保留 7 个已完成开场目标、卡芙卡试用队伍 1001005、门任务 100010105 已完成及 100010107 进行中，已有物件状态未变，没有重置账号。部署与保存证明分别为 `deployment.json`、`compose-postdeploy.json`、`live-preservation-proof.json`。

复跑入口：

```powershell
python scripts/generate-compatible-mechanisms.py --check
python scripts/generate-world-mechanisms.py --check
python -m unittest scripts.tests.test_world_mechanism_catalog scripts.tests.test_world_mechanism_resource_install
./scripts/build.ps1 -TestForks 8
python scripts/smoke-compatible-mechanisms.py --jar build/libs/hkrpg-server.jar --output build/compatible-native.json
python scripts/smoke-opening-stage-win.py --doors --output build/compatible-opening.json
python scripts/smoke-world-chests.py --final-bindings --final-case 20433651 --output build/compatible-qte.json
python scripts/smoke-server.py
```

原生验证创建独立账号和 H2 数据目录，初始任务接受、旅行及三个明确的场景前置值是夹具。不会预写挑战完成、领取凭证或奖励余额；对话、机关完成、战斗与领奖都走服务请求。每阶段正常停服，再由独立进程检查同 UID 的完整步骤集合和实际物品/货币增量；最后一组领奖后也再次登录检查。
