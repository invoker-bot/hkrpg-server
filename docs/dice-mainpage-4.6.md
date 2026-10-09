# 4.6 经典骰子首页投掷与永久成就

首页投掷报告已接入 Java 21。它只记录已接受的首页投掷请求，完整骰子战斗、骰面、技能、回合输入重演和游戏 UI 仍需补齐。本项不变更继承核心的 4.2 协议基线，也不依靠修改版本字符串宣称客户端可玩。

## 固定来源与只读核对

- `March7thHoney-OpenSource` 提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，AGPL-3.0：原始 `HandlerDiceCombatMainPageRollDiceCsReq.cs` 只有成功响应。本实现补充计数和校验，没有把该占位成功作为完整玩法。
- `TurnBasedGameData` 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`：原始常量 `Dice_Combat_Main_Page_Dice_Cool_Down_Time=1500`，原始 Quest `4055044`、FinishWay `DiceCombatRollDiceCnt/NoPara/20`、前置 Quest `4000001` 和 Reward `113000/Hcoin=5`。六条完整来源记录及各文件 SHA 保存在 `src/test/resources/fixtures/dice-mainpage-roll-quest-4.6.json`。常量整表 SHA 为 `cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95`。
- `scripts/verify-client-dice-mainpage.py` 只读本机 `GameAssembly.dll` 和元数据：首页发送函数实际分配空请求 `CNFMEHAEIJI`，发送命令 `9262`；响应 `BPNGPLHJHBE` 只有字段 8 的 UInt32 返回码。客户端冷却函数实际读取上述资源键，整段 PE 函数边界及 16 个指令点一并核对。响应命令 `9276` 来自冻结参考映射，未把它标为已独立验证的原生消费者命令。

## 状态与领取

计数、最后接受的毫秒时间和固定来源身份放在实际 Player 文档中。旧存档缺少字段时从零开始；损坏、来源不符、时间倒退、UInt32 溢出不能增加次数。保存计数失败会撤回内存候选状态；保存成功后，查询能根据真实计数回溯成就进度。

投掷要求活动页面与模块实际开放，且没有战斗、其他玩法或未完成的交付/购买/升级事务。1500 毫秒间隔是本项目依据原始客户端冷却实施的服务器约束。空请求不包含骰面、输入、物理轨迹或服务器开局凭证，因此不能由它证明客户端真的播放了投掷动画。

前置成就真实完成、原始 Quest/FinishWay/Reward 一致、计数达到 20 时，才允许领取原始 5 星琼。查询、领取及前置判断都校验计数来源；伪造的 FINISHED 记录不能代替投掷历史。活动关闭后保留已经取得的永久成就，禁止新增首页投掷。本项目前只为 `DiceCombatRollDiceCnt` 连接首页报告来源，尚未覆盖全部骰子对战中的投掷。

领取沿用现有预留、发放、关闭流程。CLAIMING 保留并阻止重复发放；正常保存和进程重启不证明库存跨文档断电原子性。

## 验证方法与边界

`DiceMainPageRoll46Test` 的 56 项检查覆盖 1 至 20 次真实领域调用、1500 毫秒边界、并发、异常线字段、非法存档、旧存档、窗口与玩法冲突、保存回退、原始配置改动、前置及重复领取。5 项行为断言先复现失败，夹具初始化异常不算行为回归。另有两次真实 Player Morphia 读取，核对计数、最后时间、5 星琼和 CLOSED 状态。

`scripts/smoke-dice-mainpage.py` 使用隔离目录与账号，分两段通过 UDP/KCP 发送共 20 个空请求，再核对原始奖励、重连、正常 H2 重启、关闭窗口和独立离线 Player 读取。前置教程 `4000001` 完成及页面门槛放开是明确夹具；原始计数、完成条件和 5 星琼奖励不修改。该脚本不启动游戏客户端，不修改运营配置、存档或来源资源。

运行 `scripts/build.ps1`、完整 Python 回归、源码导出独立构建、只读客户端核对和隔离网络验证，最终以本批 `build/dice-mainpage-roll-verification.json` 的实际计数与 SHA 为准。旧的 5131 项证书封存为历史快照；全剧情、宇宙、活动小游戏、骰子对战演算及实际 4.6 客户端验收仍不能计为完成。
