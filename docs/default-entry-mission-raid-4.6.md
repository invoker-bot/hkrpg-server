# 4.6 原始43306普通入口与虚拟队伍

本批接入原始43306的有限Default入口：保留省略的EnterType、Player队伍、Unknown恢复、SkipRewardOnFinish、两主任务1032201/1031901、前后1031801与完成返回2031101。固定资源提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的36份完整Git文件、104条完整记录及17个原始子任务与安装资源逐项核对，证据为 `build/default-entry-mission-raid-source-proof.json` 和源码夹具。共享安装器要求的7号队伍规则为未使用的原始记录，不作为43306限制。

第一阶段保留Talk/StageWin依赖，103220105执行原始RaidSceneTransfer至43306002后才进入第二主任务。第二阶段103190102完成后，原始103190112的完整ReplaceVirtualTeam创建1021304（实际角色1304、等级40、装备21002），GetTrialAvatarList依照原始ListContain检验实际角色。普通队伍的身份、HP/SP和秘技点保留，虚拟队伍使用独立状态；暂停回到入场前位置，重入返回所存阶段，满足两个主任务完整结束证明后才返回2031101并退休剧情队伍。

`RaidEntranceSources` 从配置资源目录只读完整RaidConfig行，为Default入口核对现有类型化行并冻结原始行全部字段。省略EnterType与显式Default可识别，不改成SkipUI；未知、null、重复键、非整数或缺失来源拒绝。缓存随路径、文件大小和修改时间变化重载，完整行变化使已有阶段证明失效。旧SkipUI阶段签名保持原样。此缓存不是对保持同尺寸/同时间戳恶意篡改的实时监控。

原始DifficultyAdjustmentType=1保留。安装客户端只读核对Default=0与DifficultyAdjustModule：World0不能降难度；GetEasyWorldLevel的有效值减一并向零截断。另已核对更新请求4103、数据字段2，以及内部数据ID字段8/source字段11/difficulty字段12；Raid来源为1、Stage来源为2，难度Default=0/Easy=1。证据为 `build/client46-difficulty-adjustment-native-bounded.json`、`build/client46-difficulty-adjustment-wire-bounded.json` 和请求类型槽记录。当前入口仅允许实际玩家World0；更高世界等级的难度设置、关卡有效等级与正式UI仍待完整实现和验收，不能因为存在这些字段就允许旁路。

新增23项Java检查覆盖原始省略入口、普通数值保持、两阶段/原始虚拟脚本/完整结束门、World限制、显式Default、八类无效来源、丢失/损坏资源、未映射字段变化关闭、暂停/重入，以及普通、虚拟、暂停、完成四种真实Player/Morphia状态各两次恢复。最初四项中的三个实际入口失败保留在 `build/default-entry-mission-raid-resource-red.xml`。扩展测试曾把暂停返回位置误写为完成返回，已纠正，不算生产缺陷。

原生脚本 `scripts/smoke-default-entry-mission-raid.py` 使用原始任务与完整任务目录，前置主任务接取、正式初始秘技点2、对话/StageWin结果是标记隔离Player夹具。实际服务器负责进入、转场、虚拟队伍创建、目标检验、暂停再入、恢复及结算；只读来源哈希和同一jar固定校验覆盖整个运行。原型通过114个KCP请求、同UID11次快照、八次正常停机/七次H2进程重启、12次离线操作（八次只读检查/四次明确Player夹具写入），2947份源资源哈希不变。初次检查器把无限制Player入口误当作需要基础队伍快照，已改为核对本项目实际存储结构，不算生产缺陷。最终构建、导出源码包及原生协议验证以 `build/default-entry-mission-raid-verification.json` 为准。

完整原始演出、实际自然战斗输入、全部原始任务图、其他Default/Performance入口、高世界难度、真实游戏UI和断电原子性仍待完成，功能目录保持partial。生成协议、命令号、版本常量以及运营配置、数据库和资源不改。
