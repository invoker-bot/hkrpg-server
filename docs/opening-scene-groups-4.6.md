# 开场场景提前生成凝滞虚影

本页记录场景门槛修复时的验证与产物；后续宝箱领取修复、当前部署产物及存档核验见[基座舱段宝箱说明](base-zone-chests-4.6.md)。

2026-10-20，用户截图中出现“空海之形·凝滞虚影”。原始资源确认它属于基座舱段 `P20001_F20001001` 的 `G93`，包含 `Prop 702 / MappingInfo 1101` 和 `NPCMonster 8013012 / FarmElement 1101`。该组明确要求主任务 `4010127` 完成后加载。地图本身属于开场地点，但新号看到这个入口是场景阶段错误，不能据地图编号正确就判断整个开局正确。

问题出在普通组的条件被跳过：原资源没有 `Category: Mission`，`SceneEntityLoader` 和 `MissionManager.shouldLoadGroup` 只对 Mission、Atmosphere 和延迟卸载组检查任务条件，其他组直接加载。基座舱段后续宝箱 `G6` 同样有被忽略的前置；`G52` 的开场门特效和 `G88` 的调查对象则遗漏了任务推进后的卸载。

修复在 Town、Maze、Train 的当前资源维度中，对省略 Category 或明确 Normal 的组应用原始 LoadCondition / UnloadCondition。初次加载、显式 loadGroup 和任务刷新使用同一判断；已有 And / Or、Accept / Finish 和有效任务记录校验复用原实现，未知卸载条件不会放行。六个普通组同时把两个未使用条件写成默认空列表，保持其已有初始加载行为，避免波及无前置对象。延迟加载等到重新构造场景，延迟卸载保留已有组直至离开；普通组条件不接管 Raid、Rogue、Challenge 等实例的选组流程。没有更改地图、出生坐标、资源文件、任务完成记录或物品。

`OpeningSceneGroups46Test` 使用 11 个原始资源文件的选定记录，夹具记录来源提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 和原文件 SHA256。检查新号隐藏入口与后续宝箱、保留当前开场对象、解锁后的出现与重进、更新时移除、过期实体激活失败、不同玩家隔离、延迟加载/卸载、无效完成记录和实例边界。状态历史与错误条件是测试夹具，不是强制修改实际账号。修复前 12 项行为回归失败，证据保留在 `build/scene-group-diagnosis/red.log` 和 `red.xml`。

原网络检查 `scripts/smoke-opening-stage-win.py` 追加核对实际 GetCurSceneInfo 响应：卡芙卡 Actor 7005 存在，G6/G93 不存在，当前开场 G52/G88 仍在。首次登录、同账号重连和正常进程重启都执行相同检查，同时继续验证原首战推进和教程保存。旧 JAR 的真实 KCP 复现检测到提前生成 G6，见 `build/scene-group-diagnosis/native-red.log` 指向的原始 probe 日志。

补充保留已有地图组排除规则：Book、Bug、AngryBlock、ShelfDoor 及既有指定地图范围的屏蔽，在普通任务组的刷新/显式加载时也生效。原资源中存在九个带任务条件的普通 Book 组，不能因增加任务刷新而重新生成；四项先失败回归见 `exclusions-red.log`。

本次只修复已有资源任务条件的场景加载与刷新。所有系统的解锁逻辑、全部地图表现、客户端特效和完整新手演出仍须分别验收。网络检查显式发送剧情/战斗回执，不等于官方客户端自动玩完整段剧情。

新增 19 项回归已通过，先失败记录与修复后 XML 保存在 `build/scene-group-diagnosis/`；同时通过原任务组、延迟卸载、氛围组、开场角色和首战定向测试。独立新号完成 40 次真实 KCP 请求（首次 22 次、重连 9 次、正常重启后 9 次），两次正常停服，三个阶段的场景检查全部通过；证据见 `build/opening-battle-native.json`。最终测试 JAR 的 SHA256 为 `0b5538aa82b8e524dc1fd5fbd04f36735109cf44075f34fc3b8526dc95d7e256`；补充兼容保护之前的全量成功及对应旧产物证据另存为 `first-full-build.log`、`first-opening-native.json` 和 `first-image-proof.json`，不充作最终产物的全量验收。

最终 `scripts/build.ps1 -TestForks 8` 通过 563 类 / 14,626 项测试，失败、错误、跳过均为零，用时 10 分 58 秒。构建输入摘要未变化，11 个来源文件的摘要与夹具记录逐项一致，汇总为 `build/scene-group-diagnosis/build-proof.json`。

本机 `hkrpg-local-server-1` 已切换到同一 JAR 的镜像 `sha256:d6464efc6a6423922c2a870cdf2763b01a889085ea88c442e11808990691e898`。正常停服退出码 143，停服后 11 个数据文件逐项备份并校验，旧 JAR、配置、迁移清单和数据保存在 `.local/backups/opening-scene-20261020-211553/`；运营配置摘要保持不变。容器健康、HTTP、Dispatch 和 UDP/KCP 的部署只读检查通过，见 `deployment.json` 与 `compose-verification.json`。上线后的只读检查确认 UID 10001 的四个已完成开场目标保留，卡芙卡试用队伍仍在，开门目标 `100010105` 仍在进行，凝滞虚影前置 `4010127` 尚未完成；见 `live-preservation-proof.json`。这些是服务端与网络证据，官方客户端画面仍需重登后实际验收。
