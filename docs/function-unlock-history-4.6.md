# 已开启功能在对白后重复提示

## 原因与处理

用户报告已经开启的教学目录等功能在对白后仍显示新功能提示。本轮找到并修正登录历史查询的另一条事件重放路径：`GetMissionDataScRsp.mission_list` 原先包含全部已完成子任务，`main_mission_list` 也包含已完成主任务。之前的连接增量通知修复只约束 `PlayerSyncScNotify`，没有约束完整查询的这条客户端回调路径。

本机固定 4.6 客户端将查询字段 15 装入 `_FinishedSubMissionIDs`，将字段 9 装入 `_FinishedMainMissionIDs`；随后把字段 8 交给 `_UpdateSubMissionList`。后者遇到新建的已完成子任务会排入 `0x928` 完成事件，`SystemOpenModule._OnFinishSubMission` 会以允许提示的方式刷新功能解锁。教学目录 2500 的原始条件为主任务 1000112 完成，不能通过清空教程记录、提前完成任务或关闭全部新功能提示修复。

现在完整查询只把进行中的任务放入任务实例列表，已完成主任务留在字段 9，已完成子任务放入字段 15（固定协议仍保留原名 `OLKALABEDOA`）。`FINISHED`、奖励投递中 `CLAIMING` 和已领奖 `CLOSED` 都保留为完成历史；实际进度、完成时间、奖励和任务资格不改写。增量通知继续发送真正新完成的子任务和主任务。查询成功后的通知基准使用完整的已保存状态，避免历史移入单独字段后又在下一次对白被补发。

这是服务端历史投影修复，无需修改客户端补丁或增加“全部提示已读”标记。此前 [任务变化通知](mission-notifications-4.6.md) 的失败重试、并发锁和周期任务再次完成行为继续保留。

## 来源与静态验证

- 本机 `GameAssembly.dll` SHA-256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`；元数据 SHA-256：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。仅静态读取，未运行参考仓库程序或修改客户端。
- `KHKJONMCECE`（GetMissionDataScRsp）`WriteTo` / 静态初始化 RVA `1e819c10` / `1e81a5f0`：字段 9 的编码器标签 `0x4a` 对应实例偏移 `0x18`；字段 15 标签 `0x7a` 对应偏移 `0x38`。
- `MissionModule._OnCmdGetMissionDataScRsp` RVA `d6f5060`：上述两列表分别进入偏移 `0x1d8` 的完成主任务集合和 `0x188` 的完成子任务集合。`_UpdateSubMission` RVA `d6fd580` 在新建完成实例时排入 `0x928`；`SystemOpenModule._OnFinishSubMission` RVA `e17e8e0` 调用允许提示的解锁刷新；`FuncUnlockData.UpdateUnlockStatus` RVA `d0844f0` 处理未开启到开启的变化。
- 已固定参考提交 March7thHoney `fe00bdb` 的 `PacketGetMissionDataScRsp.cs` 也只向任务实例列表放入进行中的记录；本轮未导入参考代码。字段 15 的语义依据上述本机消费者与序列化器确认，不依赖名称猜测。
- 原始 4.6 `FuncUnlockData.json` 的 2500 条目及 `MissionInfo_1000112.partial.json` 确定教学目录与首战目标 100010191 的关系。

## 验证与边界

新增 7 项回归在修复前全部失败；旧发布 JAR 的隔离原生 KCP 实验也在开场历史查询发现完成子任务进入活动列表而失败。原始证据位于 `build/function-unlock-hints-20261025/red-results/`、`native-red.log` 及其指向的探针日志。

定向检查覆盖完成/领奖三个阶段、进行中主任务的已完成子目标、重复查询后的通知基准、真正新解锁通知、发送失败、重登及 Player 状态恢复。既有数据库往返、损坏历史拒绝和并发快照检查同步改为验证独立历史字段，保留真实进度检查。首战、每日战斗和跨楼层目标的旧检查也改为分别验证进行中实例与完成历史，继续核对存储进度、后续目标、未提前完成的主任务和奖励数量。

隔离原生 KCP 剧情验证通过 927 次请求、23 个会话及 11 次正常停服（10 次同 UID H2 重启恢复）。每次完整查询均检查完成历史与进行中列表互斥，每次增量检查均拒绝重复完成记录；保留 69 次真实任务变化和 13 次新主任务完成通知。沿途覆盖开场战斗、剧情门、角色选择、读取资料、切图、队伍、桥梁、姬子试用与末日兽后的醒来流程。`GetMissionStatusCsReq` 仍返回已完成首战的真实进度，历史投影未改写存档。

`./gradlew test shadowJar` 完整构建通过：615 个测试类、15,490 项测试，失败、错误和跳过均为 0。修订后的首战、每日战斗、切图和新历史回归另行通过 4 个测试类、126 项定向检查；`scripts/smoke-server.py` 的两轮登录服务及重启账号恢复也通过。

冻结的 3,576 个源文件与构建输入核对通过。完整构建、原生剧情验证和容器内的 JAR SHA-256 一致：`b2cd24eb6d0dd4ed6138bbc93e2e072841f16759761037c81e208f63efe47814`。容器镜像为 `sha256:4796bddbf1eb524e4378022b4043deada06454ee8a3fa60ab922029a7e1f3a6a`，汇总证据为 `build/function-unlock-hints-20261025/final-proof.json`。

静态消费者和网络探针不能替代客户端实际提示验收；用户报告的对白后弹窗仍需更新后实机复测。
