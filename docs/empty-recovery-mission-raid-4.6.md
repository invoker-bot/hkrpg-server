# 4.6 原始43315空恢复与完整复合试用动作

本批保留43315的原始RecoverType=[]、SkipUI、Player、单主任务1034208及前后1034207，不把空列表改为Unknown。原始完成入口省略为0，暂停与完成均返回入场前保存位置。只读资源提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的27份完整Git文件、91条完整记录、28个原始子任务与安装资源逐项匹配，证据为 `build/empty-recovery-mission-raid-source-proof.json` 与源码夹具。无奖励副本保留SkipRewardOnFinish；共享安装器需要的7号队伍规则不作为本副本限制。

`RaidEntryRecovery` 识别有限空恢复入口，`RaidEntranceSources` 核对并冻结完整原始行，`MissionRaidSequence` 为单主任务保留完整根、子任务与原始脚本来源。入场不恢复正式HP/SP/秘技点；当前有限入口仅接入实际World0。改动完整原始行中的未映射字段会关闭已有运行的来源证明，不能凭类型化子集继续。

103420827的完整原始序列为ReplaceVirtualTeam及ClientFinishMission(103420807)。`MissionTrialCompanions` 逐项匹配整图和目标807的原始Talk、所属副本、接取状态及MoveToAnchor(43315001,28,1)，不能裁剪为仅创建角色。实际创建原始1021317（角色1317、等级40、装备23033、W1_Standard_30-40自定义技能预设），保留正式入场队伍与独立虚拟数值。目标807仍DOING时，通过生产finishTalk/StoryActions执行原始锚点动作并保存游标；两条指令都实际结束后，827才完成。目标缺失、取消、改写、挂起或未知后续动作均不能授权新角色或伪造完成。保存演员预约失败时回退持久状态与执行收据，缓存和场景发布在保存后执行。失败锚点保留挂起游标，不自动重试未知效果。

原生末尾对话复验发现，场景组卸载会错误调用恢复道具的销毁奖励，导致正式队伍秘技点从2被补到5。EntityProp恢复现在仅由Scene.destroyProp的实际所属道具销毁路径触发；普通removeEntity与任务组卸载不再发放HP/MP，宇宙原有收据路径继续处理其独立恢复。新增十项检查中六项卸载/移除检查先失败；两个旧场景检查的初始夹具没有真正切换Scene，已修正，此夹具错误不计为生产缺陷。

新增23项副本检查覆盖原始空恢复、完整复合脚本、未知或截断动作、实际目标锚点游标、保存失败回退、完整来源变更及普通、虚拟、暂停、挂起、完成五种实际Player/Morphia状态各两次恢复。原始入口、完整复合图和保存回退的先失败记录分别保留在 `build/empty-recovery-mission-raid-resource-red.xml`、`build/empty-recovery-mission-raid-composite-red.xml` 和 `build/empty-recovery-mission-raid-reservation-red.xml`；重叠失败不相加为缺陷数量。浮点Position构造、漏写锚点夹具和对新来源可用性的错误预期也已修正，不计为生产缺陷。

`scripts/smoke-empty-recovery-mission-raid.py` 在原始完整任务与资源上运行实际SDK/UDP/KCP。前置主任务接取、正式初始MP=2及早期Talk/StageWin结果为明确隔离Player夹具；目标807保持DOING，实际服务器执行复合脚本和锚点移动；原始815末尾对话使用实际FinishTalkMissionCsReq，服务器据完整原始结束列表完成单主任务并退休角色，实际LeaveRaidCsReq返回2000101。Rappa（1317）秘技实际消耗虚拟MP2到1，暂停重入及正常H2恢复保留1，正式MP仍2。预计验证108条KCP、同UID十次快照、七次正常停机/六次进程恢复、九次离线操作（七次只读检查、两次明确Player写入）；最终通过计数和源码导出对照以 `build/empty-recovery-mission-raid-verification.json` 为准，未完成验证不算通过。

完整自然演出、全部可选任务图、实际自然战斗输入、其他空恢复形状、高世界难度、正式游戏客户端UI和断电原子性仍待完成，功能目录保持partial。生成协议、命令号、版本常量及运营配置、数据库和资源不改。
