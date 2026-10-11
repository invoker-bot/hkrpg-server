# 300060001 原始结束报告的 NPC 收尾

本批把原 `300060001` 结束报告接到原 Act 的共同 NPC 收尾：只有原日常的初段正在进行、原 G84 两名 NPC 与三个 Prop 已真实加载、完整原图和 PerformanceE 绑定相等时，原 `FinishTalkMissionCsReq` 才能保存初段完成与收尾收据。保存成功后移除 `84:400001/10209`、`84:400002/10215`，保留三个 Prop，再由已有流程接取 `300060101`。初段不发放主委托奖励，也不提前完成电话目标。

这是经过原结束报告确认后的服务端共同收尾。服务器没有执行客户端两个 CustomString 分支、选项、动画、气泡或完整 Act；通用 Prop100 状态变化不能表示原按钮、CustomString 或任务完成。此前 3000601 三段委托的单条 KCP/H2 验证不计为本批 NPC 收尾的网络验收。下面的新产物已经独立验证本批 KCP 与正常停服后的同 UID 恢复，真实游戏客户端验收仍待完成。

## 原件与入口

来源固定为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`，资源标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。新增夹具保留五份 Config 完整原始 bytes/Base64，以及 PerformanceE 的完整选中行；来源审计另外读取该提交的完整 PerformanceE 原表，逐整文件比较本机已安装 JSON。原有 51 文件来源证明不变，没有安装或覆盖运营资源，没有引入参考项目运行代码。该参考数据树未发现 LICENSE/COPYING，不能据此宣称其有 AGPL 许可；Java 实现继续保持 LunarCore 来源和项目 AGPL。

| 完整来源文件 | 原始 bytes SHA256 |
|---|---|
| `Config/Level/Mission/3000601/MissionInfo_3000601.json` | `9a67c6e38f43dbae81715d0fe3c2b6c3291495f834a8a9eb7db0bb6600e0b4f3` |
| `Config/Level/Mission/3000601/MissionInfo_3000601.partial.json` | `c7409784b09ec6cbd43aaf8d4abe2c4d384c161f01cd8bd4f576905656e97faf` |
| `Config/Level/Mission/3000600/Mission_300060001.json` | `0a54640f3fcf27608c8a74baf4784dd776d33eae990fdac7d77ff345f096ac93` |
| `Config/Level/Mission/3000600/Act/Act300060001.json` | `94f38500e363fc0c2780b6b719e1aeacff8020a469ecc93acedc0cc530c799d5` |
| `Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G84.json` | `29aaaeaaeb11f41f3c0f1a0a7e31eea814549818f1af8637d9a0f9290450d4f1` |
| `ExcelOutput/PerformanceE.json` | `1ab86805edbc838b1510662863f7dd69428675c86527ac51f0e180a928935b53` |

Mission 的 `300060001_Finish` 路径先触发 `E300060001`，再以 `Mission_300060001` 报告结束；独立 `300060001_Bubble` 路径不构成两分支同时完成的条件。原 Act 的两个选项分别进入 `TalkSentence_300060007` 和 `TalkSentence_300060009`，最终都汇入 `TalkSentence_300060010`。共同序列的 `PerformanceTransition.DestroyNpcList` 只列上述两名 NPC，随后 `EndPerformance`；最后一个序列等待 Performance 结束并 `FinishLevelGraph`。

原 G84 位于 P20002/F20002001 的维度 0、楼层索引 66，OwnerMain 为 3000601，Category 为 Mission、SaveType 为 Reset。LoadCondition 为初段 Accept，ForceUnloadCondition 为初段 Cancel。三个 Prop100 的 `ServerInteractVerificationIDList` 均为空，不能编造 serverInteractID 来替代原客户端演出。原结束报告命令 1205 使用 sub_mission_id 字段 6、talk_str 字段 2；字段 11 的 custom_value_list 不能冒充 CustomString 轨迹。

## 保存与场景生命周期

初段完成采用候选 QuestState 和候选收据状态，在同一次 Player 保存中写入。收据固定 UID、原 plane/floor/维度/剧情线、实际入口、接取日、主任务与初段接取 UUID、报告键、完整来源签名、独立事件 UUID，以及实际 NPC 实例、NPC ID 和已加载 entity ID。旧合法接取 UUID 保留；不存在的初段键在候选对象中创建，主任务键在原对象预约并按已有 CAS 接口回滚。保存抛错时恢复原 QuestState 对象、原收据状态和主任务原键，原 NPC、奖励、积分及通知保持原状，原请求可重试。

同日重新加载只读取已保存收据，不补造或保存执行历史。原来源、当前日、原接取身份任一不相等，都不能抑制 NPC。原主任务正常完成后，OwnerMain 门会卸载整个 G84；正常场景重进不会自动载入它。另有回归覆盖公开 `Scene.loadGroup`：当服务器显式载入原 G84 时，同日已完成或已付款的原接取仍保持已报告的 NPC 移除。这是既有服务器 API 的边界，不表示真实客户端重进会绕过 OwnerMain 门。新日归档或未付款阶段到期重接时，旧收据不得抑制新接取的两名 NPC。

## 已有验证证据

不可变基线为 `build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256 `258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1`。独立 javac/JUnit runner 排除可变 main 输出，并逐类检查生产 CodeSource。最初 8 项有 4 个真实行为失败；正式扩展 14 项有 10 个纯 AssertionFailedError、4 个拒绝或兼容对照通过。两份报告分别保留，不能重复相加。最初正式夹具的 RecordingActor 初始化 NPE 不计行为 RED。

实现后新增 9 项收据、重复报告和来源损坏对照，仅计实际 GREEN 覆盖。旧 Origin40 只补完整原 E300060001 行，并把到期测试的旧 NPC 捕获移到初段执行前；旧 Phone35 只有 combined global/两日委托用例补完整来源到隔离 temp resourceDir，并在 finally 恢复。两处原行为断言保留。旧夹具捕获时机和缺失来源产生的失败不计生产行为 RED。

另外独立新增 6 项整轮完成边界，实际运行 213 项时出现 4 个纯 AssertionFailedError、209 项通过、0 errors/skips：真实三段 Talk/Phone 完成后，已付款 CLOSED 和明确 pending 奖励收据保留的 FINISHED 两种状态，在公开组重载以及两次真实 Morphia Player 读取后重载时均复生两名 NPC；正常 OwnerMain 重进的两个对照通过。该报告为 `build/story-script-draft/daily-origin-effects-completed-period-red-first.json`，与原 14/10 行为 RED 分开保存。修正仅把同日有效收据的主任务条件从 Doing 扩到已有 Accepted 定义，原日周期、原图与接取 UUID 条件保持。

四个同日失败修正后，213 项中的 212 项通过；同一新持久化用例继续到新日时又真实暴露旧 G84 的 loadedGroups 保留：公开组加载在旧 CLOSED 期留下三个 Prop，次日重开在换主任务前没有刷新旧 OwnerMain 门，新初段加载不能生成原两名 NPC。该失败另存 `build/story-script-draft/daily-origin-effects-owned-green-third.json`，不是最初四个同日失败的重复计数，也不表示普通客户端重进路径失败。最小修正需要在原 3000601 重开清理旧子任务之后、新主任务分配之前刷新原场景组。

上述两个修正后，`build/story-script-draft/daily-origin-effects-owned-green-fourth.json` 实际 213 项全部通过，0 failures/errors/skips/aborts，编译和执行的固定输入无变化。分组为新 NPC 生命周期 29、旧 Origin40、Sibling34、Phone35、G61 首遇40、同日晚解锁21、完成观察键14。17 份生产类 CodeSource 校验中，六份本批 owned/shared 类与两个明确的 Aether 编译依赖来自逐文件 SHA 捕获的隔离快照，其余九份来自上述不可变 jar；没有使用可变 main 输出，没有运行 Gradle。

根正式聚合专项 51 类 / 1,308 项全部通过；完整构建 512 类 / 13,954 项通过，0 failures/errors/skips，2,037 编译与资源输入无变化。二者有测试重叠，不能相加。专项、完整构建与下述新产物使用同一个实际 jar。上述 Java Morphia 测试使用独立 Mongo MemoryBackend；下述是另外执行的实际 H2 进程恢复证据。

## 新产物单 lane KCP/H2

实际产物 `build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。保留报告 `build/daily-origin-effects-current-native.json`、完整日志同名 `.log`，独立原始日志/停止/H2 审计为 `build/daily-origin-effects-current-native-audit.json`。隔离运行目录是 `build/smoke/daily-message-g61-native-3000601-settled-102-noReplies-z9d0lptj`，没有修改运营配置、数据库或资源。

只运行 settled 的 case 0：第一日原回复 `121140102`、第二日原回复 `121140104`。实际 212 次 KCP 请求、255 项检查、11 个快照、8 次正常停服、7 次同 UID H2 恢复。32 次独立 Morphia Player 读取分为原 G61 检查 16 次和 Phone 检查 16 次；NPC Effects 在 Phone 的这 16 次读取内核对，额外独立 Effects 读取为 0，不能重复计成 48。

两日各自初段都先查询实际原 G84 两名 NPC/三个 Prop，然后发送原结束报告。命令 `SceneGroupRefreshScNotify=1433` 的 `group_refresh_list/group_id` 标签为 7/10，删除实体路径标签为 7/6/14；实际收到的两个删除 ID 与报告前两名原 NPC 的 wire entity ID 完全相同，并且均先于原 1205 结束响应。两日合计四个原 NPC 删除；结束响应后的查询保留三个 Prop，重复报告拒绝并不再次完成；原初段 H2 恢复后重进保持零 NPC，原电话及主任务完成后由 OwnerMain 门卸载整个 G84。

停止后读取核对完整收据的 UID、原场景/入口、报告键、完整来源签名、实际 actor entity ID 与 loader-normalized NPC 来源指纹，以及与真实 QuestState 相同的主任务/初段接取 UUID 和完成时间。第一日收据跨所有后续读取保持不变；第二日重新生成两名原 NPC，完成报告后产生不同的主任务/初段接取 UUID 与事件 UUID。两轮主奖励各 5,000 信用点与独立领取的 200 活跃度、防重复领取及旧电话归档均通过。

该 lane 使用明确的教程前置历史、单条真实原日常目录和受控时钟；首次登录未解锁到同一实际 D0 解锁的路径保持，没有把日常目标或电话完成状态种入夹具。两日表示受控时钟跨日，不是实际墙钟等待；这是正常 stop/restart，不是崩溃原子性测试。另两个原回复未扩为新 Native lane，公开 `Scene.loadGroup` 的 FINISHED/CLOSED 及跨日边界由 Java 回归验证。原两条 CustomString 演出选项、完整客户端 Act、菜单 UI 和真实游戏客户端仍未执行。
