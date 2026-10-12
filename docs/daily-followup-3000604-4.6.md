# 原始 3000604 非选池后续任务

本批有限接入原始 3000603 电话结算后的 3000604 接取和服务端推进。原 3000604 是 `Type=Daily`，却不在原始 88 条 `DailyMissionData` 中；它的接取条件为 `MultiSequence=3000603`、开始条件为 `Auto`。修复前，通用每日资格检查把它当成需要选池记录的委托，因记录不存在而拒绝接取。

现在仅对完整原始 3000604 定义作例外。真实 3000603 电话三次选择和最后结束请求完成后，已有刷新接取 3000604，已有任务查询返回该任务。任务不消耗第二个每日选池位置，不生成该任务的每日接取日、归档或过期记录。未发最后电话结束请求、旧全局电话完成标记、外来任务身份、无进度/无完成时间/未来完成时间或取消记录不能代替原始前置。

原始目标只有 300060400 和 300060401：前者等待进入 `20002/20002001`，后者依赖前者完成并接受 `Mission_300060401` 的 Talk 报告。测试通过实际 `Player.loadScene` 从原列车 `10000/10000000/100000102` 返回原目标楼层推进 300060400；同楼层重载和不匹配的进入事件不伪造转移。300060401 报告还核对原始 G85 两名 NPC（10209/400001、10215/400002）、Prop100/300001、真实场景所有者、原楼层、维度、组和完整加载投影。缺失、外来或移动后的实例不能完成报告。

原始 MainReward231 为 5000 信用点。测试中前置 3000603 已支付 5000，随后 3000604 另付一次原始 5000；钱包总计 10000。已领取的原 2100003 活跃度200保持原数，不把后续任务算成第二个选池委托。重复报告拒绝并保持余额。跨日保留普通任务的已完成状态，不凭 `Daily` 类型发明这条非选池任务的周期重开规则；官方完整组轮换与后续重置语义继续待核对。

## 固定来源

固定来源为本地 `references/TurnBasedGameData` 的提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。原22份证明保留；增加原列车整楼层和同一完整表中的列车/入口行后，正式夹具包含23份完整原件及41个完整选中对象。11份 Config 保留原始 bytes，12份整 Excel 以无损压缩 bytes 保留并核对原 SHA；源码仅声明本项目有限规则，没有导入参考仓库代码。参考数据树未发现 LICENSE/COPYING，不将数据来源冒称 AGPL；服务器原 LunarCore/AGPL 归属不变。

23份原件覆盖 MainMission、原88行 DailyMissionData、DailyMissionCount、PerformanceE、DialogueNPC/DialogueCondition、NPCData、MazeProp、MazePlane、MapEntrance、RewardData、TalkSentenceConfig，以及完整 Main4/partial/layout、Mission401、Talk401、Act401、G85、G61、目标楼层、列车楼层和 G85 DialogueMain。运行资格同时核对8份已有整 Config 及同一 Java loader 的 Main/合并任务图/G85投影与完整 Mission401 任务结构；浮点规范指纹由实际 Java21/Gson 生成。

G85 NPC 的 `Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json` 是固定 Git 来源原件，本机仍缺失。夹具明确 `installed=false`，不会将它安装到运营或隔离资源，报告不称已安装或已执行。其原图触发 PerformanceE300060401；原 Act 有两层各两个选项，最后共同发出 `300060401_Finish` 并 EndPerformance。完整 Mission401 等待此 CustomString，未包含显式 `ClientFinishMission` 或 `FinishPerformanceMission`。本批服务端 Talk 报告不能证明客户端 CustomString、选项轨迹或演出执行，游戏 UI 尚未验收。原 G85/G61 的 DelayToLevelReload 字段保持原件；本批没有补整个客户端组调度解释器。

## 实际验证

不可变产物为 `build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。所有草稿以独立 javac/JUnit 执行，排除 mutable main 输出，没有运行 Gradle。

- 首次草稿4项：2个真实接取/查询断言失败、2个拒绝对照通过。
- 扩展34项：13个纯 AssertionFailedError、21个对照通过，0 errors/skips/aborts；此前4项不重复累计。额外拒绝失败表明将 Main4 改为 Branch 可绕过旧每日检查。
- 有限实现后新34项全部通过；随后8类247项通过，包括34新项、Sibling34、Phone35、G6140、Origin40、Late21、InitialNpc29、CompletedObservation14。
- 生产 CodeSource 核对11类：仅两份共享副本和两个新规则类来自 build overlay，其余7类均来自同一 f586。使用的本地输入 SHA 未变；其他代理并行编辑时不宣称全源码稳定。
- 新回归包含两次实际 Morphia `Player` 写入后读取，恢复原目标状态和已支付奖励，重复拒绝保持余额。这是同进程 Mongo 内存后端，不等同 H2 进程重启或真实客户端验收。
- 正式类名与 classpath 布局迁移后的独立复核仍为247项全部通过；23份完整原件 bytes 的 SHA 在 JVM 中核对，12份 Excel 无损解压。迁移首次生成器多写一个括号导致编译失败，没有执行测试，保留工具日志并修正后重跑，不计行为失败。

首草稿的 ExcelMap.remove 编译错误保留为工具准备失败，未执行测试，不计行为 RED；扩展 wrapper 原预估12个失败，JUnit实际13个且全为行为断言，汇总计数误估不改变真实结果。固定源上下文初次误猜入口 ID 的准备错误也未执行测试。正式 Gradle、完整新包、实际 KCP/H2 与游戏客户端尚未在本批执行，后续证据产生前不得记为通过。

## 原生网络与正常 H2 恢复

在根代理冻结的候选包 `build/out-followup-core-material-candidate-runtime.jar` 上执行单条实际网络路径，SHA256 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`。随后根代理完整构建实际通过516类/14,106项，0 failure/error/skip，2573输入稳定；最终 `build/out-followup-core-material-runtime.jar` 实际SHA与Native候选包相同，因此本次Native属于同一最终产物。

单 lane 仅 D0 的原始父电话 branch0：121140302 → 121140306 → 121140311，最后原电话结束请求结算3000603。实际随后自动接取非选池3000604；原列车入口100000102往返目标200020102使原300060400完成，只让原300060401进入Doing；查询实际G85两名NPC和Prop，错误旧Talk拒绝，原1205 Talk报告另支付原5000信用点，通知在响应前，活跃度仍为原200。原Main4收据没有消耗第二个pool位置，也没有创建日轮、过期或归档。重启后原目标和奖励保存，重复报告/领取与再次接取拒绝。

原始日志实际统计为141次KCP请求、171项检查、8快照、7正常停服、6同UID正常H2重启、28次独立实际Player读取。读取为G61的14次与Phone/Followup共用的14次，Followup复用检查不额外计读取。源23完整原件/22份本机全对象及35项实际4.6线描述符在启动前核对；source-only G85 DialogueMain始终缺失且未复制、未安装、未执行。正常stop原日志及14份只读H2检查由独立审计再核，数据库/config bytes在各次只读检查中未变。

报告为 `build/daily-followup-3000604-candidate-native.json`，SHA256 `97d900db8a231ce19e0e525653e82b87e045b7ae32e1c2c1f39fe22c867a1ab2`；完整日志为同名 `.log`，SHA256 `d89406b9f95291eab0130f3d84db6706155f132bc78512841c5bb0c4661a1e37`；raw与H2审计为 `build/daily-followup-3000604-candidate-native-audit.json`。唯一隔离目录 `build/smoke/daily-message-g61-native-3000604-followup-settled-302-306-311-oneReply-eec_fa8a`。

前置任务历史、单条原始每日catalog和受控时钟属于明确夹具；没有写入Main4/目标完成状态或NPC演出轨迹。实际只跑父电话一个原分支、一个受控日，另七电话分支保持既有单元/旧证据；不是跨实际墙钟日、断电恢复、完整Act/CustomString或游戏客户端UI验收。晋级工具的两份Java与本次执行bytes相同；wrapper改变布局/helper路径和source fallback，并校正13条完整消息history与其中3条玩家reply的输出标签。旧报告`executedOriginalPaths.actualParentReplies`实存13条history，不把它称13次玩家选择，也没有改写归档；真实三次选择由snapshot与H2原history证明。该标签问题先用8项Python中的1项失败锁定，修正后8项全绿。晋级布局再次编译5helpers、核35描述符和src缺失fallback的23原件/22本机完整对象通过，未另起Native复跑。
