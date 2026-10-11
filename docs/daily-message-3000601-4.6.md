# 原3000601三阶段日常与接取轮次

本批把原3000601接到已有日常接取、场景、Talk报告、电话和奖励流程，保留原目标300060001、300060101、300060102。服务器新增资格校验与每轮收据；这里不称原CustomString、Act剧情演出或真实客户端任务UI已完成。

来源为本地 `references/TurnBasedGameData` 固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。原件属于数据参考，没有导入参考实现代码或运行参考仓库二进制；该提交树没有LICENSE/COPYING路径，不能把数据来源的许可写成已确认AGPL。当前Java核心保持LunarCore来源与项目AGPL许可。

此前G61的50整原件/240完整对象保持原样，本批补入第51份原Act300060001。来源审计逐份核对固定Git完整字节、保留的完整原件、本机完整JSON与SHA；51份原件共57,315,855字节，正式新夹具保留103个完整选定对象和Config原图Base64。Act300060001原SHA为 `94f38500e363fc0c2780b6b719e1aeacff8020a469ecc93acedc0cc530c799d5`，本机已有5318字节文件SHA为 `6e4fc7fc55652b81fbb84a55554e9779ea218d81b3df5bb193f4eaf28954d492`，与5114字节原件完整JSON相等。本批没有安装或覆盖这份原本已有的资源，也没有改变旧G61六图安装历史。

## 原始流程

Main3000601是Daily/Manual接取/Auto开始，起始列表为[300060001]，完成列表为[300060102]。目录Type1/Group30003要求1010201完成，类型1每日限额为一次，与3000602/3000603共用配额。活跃任务2100003另要求1010403完成，使用FinishTodayDailyMissionCnt规则。主委托结算5000信用点，活跃任务实际领取200活跃度；二者各自验证。

| 原目标 | 接取条件 | 原完成报告 | 实际MissionJsonPath |
| --- | --- | --- | --- |
| 300060001 | Auto，无前驱 | Talk/Equal/Mission_300060001 | 3000600/Mission_300060001.json |
| 300060101 | AnySequence[300060001] | Talk/Equal/Mission_300060101 | 3000601/Mission_300060101.json |
| 300060102 | AnySequence[300060101] | MessageSectionFinish/Equal/1211400 | 3000601/Mission_300060103.json |

最后目标的编号是102，原文件名确实是103，不按编号改写。三个完整MissionTask canonical SHA分别为：

```text
300060001 a8ad32be0fe86430775eff1a8c1f42a0073bc6eadc9505bc2eb758c32ec23ea6
300060101 976d1f4da59fe8249135fbce998c2db3d7750b4354cfd46398c98f21f58661bd
300060103 7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775
```

初段位于Maze20002/Floor20002001、维度0的原G84。组属于Main3000601，原LoadCondition要求300060001接取；NPC10209/400001和NPC10215/400002，以及三个Prop100实例300001/300002/300003由现有Scene加载。服务器核对原楼层维度索引66、原组的实际加载对象、原NPC实例编号/类型/位置，以及Prop实体的原PropInfo身份；没有把任意同编号Performance允许为目标。

原初段脚本有四个并行序列。300002范围触发产生 `300060001_Bubble`，独立等待后播放两句NPC气泡300060145/300060146。300001的原UI触发产生 `300060001_Finish`，另一个序列等待它后触发Performance E300060001，随后执行 `FinishPerformanceMission(Key=Mission_300060001)`。原图没有声明Bubble与Finish是两个AND目标。原Act两条Option分支分别进入007或009，再合流010，随后PerformanceTransition、EndPerformance；其客户端NPC销毁与演出等待没有在服务器补造。

现有4.6 FinishTalk请求只提供sub_mission_id与talk_str（命令1205，字段6和2）。它不能携带或证明上述CustomString、Act选项和演出等待的轨迹。服务器只在真实初段Doing、当前日接取、原G84实体仍加载时接受匹配的原报告，再开放300060101。普通Prop100状态变化和G61初遇90000103都不能自行完成初段。现有参考中尚未确认可执行的4.6客户端ClientFinishMission方法体；原资源报告节点与实际协议字段相符，不等于已运行客户端调用链。

随后原G61菜单按300060101 Started提供原MenuItemID/MissionID与Talk_300060101路径。该Talk触发E300060101；原Act含取消结束及四个错误答案循环分支，正确分支产生300060101_Finish。服务器接受准确原Talk报告后开放电话1211400，但不根据ACK宣称这些Act选项已经执行。

电话Group11125/联系人48保留原三个Section[1211400,1211401,1211402]。1211400的11个Text节点从121140101、121140111自动开始，原单次回复为121140102、121140104或121140106，各支路最终合到121140108/109/110，完整历史长7。回复到达末节点仍需实际FinishSection请求，之后才结算主委托。

## 有限实现与持久化

`DailyMissionMessageRules`增加唯一原3000601定义，校验完整三阶段图、cross-folder路径、原脚本签名、目录/配额/G61/G84及11节点拓扑。`DailyMissionMessageOriginRules`对原G84采用相同Java资源加载器投影比较，保留默认值和float归一化，不拿原始JSON签名直接比较加载后的对象。源证明仍保留完整原图中未被当前GroupInfo表达的ForceUnloadCondition/SaveType等字段；没有宣称当前Java解释器执行这些全部字段。

Run保存在同一Player文档，绑定接取日、Main/Goal身份和已完成300060001/300060101身份。新增Completed观察API仅接受FINISHED/CLOSED、正progress和finishTime，保持原DOING观察API语义；合法旧UUID复用，坏UUID拒绝。首次电话保存失败会恢复Main/Goal和两项已完成前置的观察键，再恢复旧Run。普通回复/提交保存失败仍能重试。两项前置被同日的新Quest对象替换，也不能使用原收据继续完成。

旧3000602/3000603的Run字段与来源标识保持兼容；只有新三阶段定义使用新增的initialAcceptance/firstAcceptance。旧全局1211400对象保留，新接取不能借其完成状态支付。现有三目标归档、未付款到期、同楼层刷新与重接逻辑复用，未添加固定完成。结算日与接取日、活跃领取和单日配额仍有独立边界。

## 已执行验证

冻结旧产物 `build/manual-sibling-save-blast-runtime.jar` SHA256 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be` 上实际执行草稿及正式39项：20个纯AssertionFailedError、19个拒绝/兼容对照通过，0errors/skips/aborts。8个生产类CodeSource均来自这个jar，排除当前main输出。正式RED报告为 `build/story-script-draft/daily-message-3000601-6525-formal-red.json`。

现实现采用独立JDK21 javac覆盖五个精确owned/shared生产类，再与同一旧产物运行Origin40、CompletedAPI14、旧Sibling34、Phone35、G6140、同日晚解锁21，共184项全通过，0failures/errors/skips/aborts，来源输入前后相等。报告为 `build/story-script-draft/daily-message-3000601-owned-green-second.json` 与XML/日志/输入审计。两个实际Morphia Player文档读取验证初段完成身份和电话身份；本步骤使用MemoryBackend，不能写成H2进程恢复或客户端验收。

历史错误分别保留：前两草稿仅javac夹具错误，没有执行JUnit；第三草稿38项有20个行为断言与3个夹具错误，不能算干净RED。第四草稿及正式39项才是上述纯行为RED。首184相关运行的三个失败均是测试预期更正：两个未观察电话的Quest尚未分配UUID，改为锁定新Quest对象身份且保留已分配键不复用断言；旧Sibling完整原3000601不再硬编码unsupported，而以完整图通过和实际不同原图替换拒绝作对照。没有改生产逻辑绕过它们，也没有追认这三项为生产RED。

源码与夹具冻结清单见 `build/story-script-draft/daily-message-3000601-green-ready.json`。根任务正式联合专项414项全部通过、2544项输入保持一致，随后实际打包冻结 `build/initial-query-cyrene-daily-origin-clock-runtime.jar`，SHA256为 `258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1`。本批随后实际完成 `scripts/build.ps1 -RerunTests` 全量构建，507类/13,815项测试全部通过，0failures/errors/skips，2544项编译输入无变化；本数字来自本批实际XML审计，没有借用前批502类结果。

新原生工具最初在 `build/story-script-draft/origin-native/` 准备，实际运行前编译五个外部helper，预检完整51原件/103对象和实际35个协议消息形状，只在隔离副本内载入夹具命令，不覆盖包内生产类。实际Inspector的三个控制误标先形成纯工具3项失败回归，再作有限修复；正向守卫中的旧kind名称和Player原件缺省ContactsID两次夹具错误分别保留日志，没有计入生产RED。最终19项工具守卫通过。独立新工具已导入 `scripts/smoke-daily-message-origin.py`、`scripts/verify-daily-message-origin-native.py`、两个新的Java helper及 `scripts/tests/test_daily_message_origin_native_guard.py`；旧3000603工具和历史报告保持原样。

新冻结包上只运行settled caseIndex0这一条双日路径，原生报告为 `build/daily-message-3000601-current-native.json`，日志为同名 `.log`。实际196个KCP请求、239个检查、11个快照、8次正常stop、7次同UID H2进程恢复与32次独立Morphia Player读取全部通过，独立目录为 `build/smoke/daily-message-g61-native-3000601-settled-102-noReplies-mxp9qvyi`。这是本批原3000601实测，旧3000603原生结果没有重复计数。

| 实际阶段 | 原请求和保存证据 |
| --- | --- |
| D0初遇与同日解锁 | 先真实加载G61/NPC10206并提交原初遇90000103，此时没有3000601目标或Phone Run；随后在同一D0设置原1010201/1010403前置历史，现有流程实际接取Main与300060001，追加活跃目标2100003 |
| D0初段 | 实际Scene响应包含原G84的两NPC和三个Prop100实例；准确的300060001 Talk报告只开放300060101，正常stop后的H2证明初段FINISHED、G61段DOING，尚无Phone Run |
| D0 G61与电话 | H2恢复后，原菜单谓词按真实Started状态只选300060101；准确报告才开放300060102和1211400，原回复121140102产生完整7节点历史，先保存未提交末尾，再正常恢复实际FinishSection |
| D0结算与D1重接 | D0实际5000信用点和另领200活跃度；同UID恢复后拒绝重复结算。受控D1归档原三目标并重新接取初段，再报告G61、回复121140104、提交和领奖，每日仍是5000/200 |

两项前置历史通过仅console隔离命令设置，目标300060001/101/102、电话状态、四个观察身份和奖励均由真实流程产生，未由夹具直接设成完成。运行中的时钟按每次原请求阶段确认，D0初遇和晚解锁没有通过提前推进D1绕开问题。原300060001_Finish和Act演出本身仍未运行；该实测证明原报告的服务端场景/阶段门槛，不证明客户端已经走完演出。

`build/daily-message-3000601-current-native-audit.json` 对11份原probe日志、8个真实Stopping server日志、16份只读H2日志再次独立计数。每次H2 Inspector实际进行两次不同Player实例读取，读前后Player文档和MVStore文件SHA一致。原初遇整对象跨全部停服及两日保持一致；四个Main/Initial/First/Goal观察键同轮稳定、D1全部更新，三目标与Main的实际finishTime都属于各自接取日。原本机来源SHA复核无修改。最终旧轮历史走102、新轮走104；settled caseIndex1/2（分别以104/106为首日回复）及未付款到期Native均未运行。104只在已运行caseIndex0的D1出现，完整三条原回复图的单元/工具守卫覆盖与实际单lane结果分开记录。

自然墙钟跨日、强杀原子性、原CustomString/Act执行、客户端脚本解释器与真实客户端任务/菜单UI均未验。本批不据上述服务端结果宣称完整剧情演出完成。
