# 派对车厢初始房间显示

## 实机问题与来源

2026-11-01 用户重登后提供截图：界面已显示“派对车厢”，角色和交互图标存在，建筑与地面没有显示。新会话 PID 26436 的客户端日志已不再出现 `Test_Prop_Teleport`、`AsyncLoad MapObject FAILED` 或 `NullReferenceException`，只读等待记录显示 `uiReady=true / loading=false`。用户另确认观景车厢显示正常。此前调试组加载修复保持有效，派对车厢空场景仍须独立处理。

固定客户端静态调用链表明，`TrainPartyBuildManager.Sync` 先调用 `_ResetAreaInfos`，将区域 `IsUnlock` 置为 false，再按收到的区域 ID 设置可见区域。`APCCFELCPKH.DKCIKFPJMKG` 按 `TrainPartyModule.IsAreaUnlock` 的结果激活或隐藏原始 `ShowBlockList`。旧 8034/8025 初始化只提供空 `TrainPartyInfo`，区域清单为空，初始房间因此不满足显示条件。相关 RVA、字段布局和反汇编证据保存在本工作区 `build/train-client-acceptance-20261101/`；这些是只读静态证据。

物理字段另经当前客户端解析器核对：建筑资料的区域列表 codec 使用标签 `0x42`（字段 8）；区域消息 `NDLMAMEGFDM.MergeFrom` 在 RVA `1eabd550` 将标签 `0x48`（字段 9）写入 `+0x44` 的区域 ID，把 `0x50`（字段 10）写入 `+0x40` 的进度。区域同步沿这些实际偏移读取数值。验证没有依赖协议文件里历史偏移注释或混淆字段名来猜测语义。

`TrainPartyAreaConfig.json` 的六个原始区域中，仅 12 没有 `RequireAreaID`；它对应 `Bar_2F_Bedroom`，Sort 为 1。回归夹具保留全部六条原始记录和源文件 SHA-256，来源提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。

## 本次范围

初始化增加原始初始房间 12 的显示引用，建设进度保留 0。已完成步骤、家具、资金、星数、奖励、活动局和玩家保存记录维持原有状态；依赖房间不在该初始化中开放。该行为用于未开始建设的初始房间显示，完整建设、旅客、掷骰和结算继续未实现，状态仍为部分实现。

`TrainPartyInitialRoom46Test` 在修改实现前的三项回归全部失败，保存于 `initial-room-red.log/xml`。检查原始无前置房间、初始建筑可见性所需引用、物理字段 7→2→8→9，以及零建设进度和玩家状态不变。原初始化契约及网络探针明确允许这一项原始引用，仍严格拒绝其他已完成记录。

客户端实际建筑恢复和交互由用户手动复验；用户明确要求停止 Computer Use，本次后续不自动操作游戏窗口。最终构建、隔离网络和实机反馈另列，不将回包检查等同于模型已经显示。

## 2026-11-01 构建与网络验证

新增三项先失败回归及相邻初始化共四类、80 项定向检查通过。完整 `test shadowJar -PtestForks=8` 用时 11 分 57 秒，647 类、16,040 项，失败、错误、跳过均为 0；2,881 份构建输入摘要保持一致。测试 XML、输入清单与日志位于本工作区 `build/train-client-acceptance-20261101/`。

冻结 JAR SHA-256：`140632df548f6bbb58084d908f219e3a227732fce6b8f884aa5861314ddc4e14`。同一 JAR 通过 29 次列车原生请求、14 份场景快照、三次注销和两次正常停服；同进程重连及 H2 重启后均保留初始房间 12 的零进度引用，五个调试组保持不下发。旧包在强化后的同一探针中明确报 `Missing original initial room reference`。

完整登录初始化另通过两个会话、两次正常停服和同 UID 的 H2 重启恢复。每轮 144 条初始请求，90 类已实现响应契约与未实现查询的原有边界保持；初始化分别为 2,443 / 2,458 毫秒，原 4,000 毫秒门限不变。Dispatch 与账号重启恢复冒烟同时通过，其 terminate 停服不计入上述正常停服次数。相关报告为 `native-green.json`、`login-native-green.json` 和 `smoke-server-summary.json`。这些报告均保持实际客户端验收未完成的标志。

本轮代码在 `codex/party-carriage-scene-init` 工作区单独构建，原目录中正在进行的 GM 商店改动未纳入这个产物，也未被覆盖。
