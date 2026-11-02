# 好友页面持续加载修复

## 原因与范围

用户报告进入「我的好友」后持续转圈，右上角已显示 `1/100`。正式容器日志对应记录：

```text
Rejected 4.6.0 response for GetFriendListInfoCsReq: Unmapped outgoing field: legacy.FriendListInfo.simple_info
Client disconnected
```

好友登录快照包含服务器控制台好友，但进入页面所需的完整列表转换失败。客户端收不到列表并失去连接，因此已有数量与页面显示不一致。直接原因是旧内部协议与 4.6 的语义字段更名，不是数据库中缺少好友。

`ProtocolBridge` 增加以下明确字段转换，保留原 UID、昵称、头像、等级、状态、备注、标记、聊天气泡、最近活跃时间及展示角色。旧版 4.2 字节编码继续保持原样；未知字段仍拒绝，不通过发送伪造空列表绕过错误。

| 内部字段 | 4.6 字段 | 4.6 字段号 |
|---|---|---|
| `FriendListInfo.simple_info` | `FriendSimpleInfo.player_info` | 4 |
| `FriendApplyInfo.simple_info` | `FriendApplyInfo.player_info` | 6 |
| `GetFriendApplyListInfoScRsp.friend_apply_list` | `receive_apply_list` | 12 |
| `PlayerSimpleInfo.logout_time` | `last_active_time` | 8 |
| `PlayerSimpleInfo.chat_bubble` | `chat_bubble_id` | 12 |
| `PlayerSimpleInfo.assist_info_list` | `assist_simple_info_list` | 13 |

## 来源

- 冻结协议：`protocols/4.6/CmdFriendType.proto` 的 2934/2925 好友列表请求与响应、2960/2916 申请列表、`FriendSimpleInfo` 和 `FriendApplyInfo`；`protocols/4.6/Common.proto` 的 `PlayerSimpleInfo` 与 `AssistSimpleInfo`。
- 原内部消息和生产构造器：`PacketGetFriendListInfoScRsp`、`PacketGetFriendApplyListInfoScRsp`、`Player.toSimpleInfo()`。控制台好友即使没有普通好友也会生成非空列表，因此空消息转换测试无法覆盖实际问题。
- 只读语义核对：`March7thHoney-OpenSource@fe00bdbda3c28ca33d01f171566aa35b5ab95083` 的 `FriendManager.ToProto/ToApplyListProto` 与 `PlayerData.ToSimpleProto`。仅核对已有字段，未引入参考程序或运行依赖。

## 验证边界

`FriendPage46Test` 覆盖生产控制台好友包与缓存、普通好友完整资料及备注/标记/玩法枚举、三个共享字段的独立转换、非空申请列表、4.2 字节不变和未知嵌套字段拒绝。修复前六项明确因本次缺失转换失败，修复后八项通过。失败和成功 XML 分别为 `build/friend-page-20261101/isolated-red.xml` 与 `friend-green.xml`。

`scripts/smoke-friend-page.py` 使用独立配置、账号和 H2，发送真实 UDP/KCP 原生请求，验证重复查询、准确请求头、列表与登录 UID 数量一致、查询后心跳、同进程重连以及正常进程重启后的同 UID。探针不启动或控制游戏客户端，也不连接正式游戏数据库。

同一产物的隔离原生检查通过 33 次请求，覆盖三次登录会话、九次好友列表查询及九次查询后心跳；两次正常停服和一次同 UID H2 进程重启均通过。报告为 `build/friend-page-network.json`，JAR SHA-256 为 `c54f59a9f3ed1f9e438de5b4687653499fd5b64970a09ce51bcc580e8a7adeb6`。首轮网络动作已全部通过，但报告汇总遇到 JVM 启动日志的 Windows 编码；改为按字节核对固定错误标记后重新完整运行通过，原失败记录保留。

额外执行 `scripts/smoke-server.py`，两轮 Dispatch/SDK、错误凭据拒绝和账号进程重启恢复均通过，结果为 `build/friend-page-20261101/dispatch-summary.json`。该脚本的 terminate 停服不计入上述两次正常停服。

完整回归执行了 655 类 / 16,106 项，其中 16,099 项通过；另七项 `MissionHistory46Test` 因隔离目录缺少原始 `MissionInfo_1000112.partial.json` 失败。按 SHA-256 复制原目录的 508 字节原文件后，七项在同一源码和 JAR 上单独复验通过，并逐项匹配原失败名称。最终验证由完整运行与这七项复验组成；完整运行自身退出码仍为 1，复验及打包退出码为 0。原 XML、复验 XML 和汇总为 `build/friend-page-20261101/full-xml/`、`missing-resource-recheck.xml`、`full-summary.json`，没有未解决的失败项。

完整门禁采用 `scripts/build.ps1 -TestForks 2 -RerunTests`，设置 `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`；早期并发运行遇到系统内存提交失败，日志保留。另两份原始楼层资源也按原摘要放入隔离目录，三个资源原文件未改动，3,047 份编译/资源输入在最终运行及复验期间保持同一摘要。

本次补齐好友页面和已有申请列表的资料转换；推荐、加删好友、处理申请、聊天、黑名单、助战及完整好友流程不因此宣称完成。真实游戏页面能否结束加载、正常显示与交互，仍需用户从原桌面入口重登后验收。

候选包基于主线 `d366279` 与已部署车厢提交 `24ac8cf` 的合并状态 `1c00cc1` 单独构建，保留已部署的初始房间及地图修复。原目录中并行进行的等级奖励和活动回放改动未纳入候选包。
