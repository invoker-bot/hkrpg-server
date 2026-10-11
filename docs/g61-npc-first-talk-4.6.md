# G61 原 NPC 初遇与菜单资格（4.6）

本批补齐 P20002 / F20002001 / G61 原 NPC 10206、配置实例 400001 的初遇性能 90000103。真实初遇记录独立保存，原条件菜单可以读取它，再接到已有的 300060201 对话与 1211401 电话日常。初遇本身没有原始子任务目标，不创建目标、不发委托奖励。完整客户端脚本执行、演出和游戏界面尚未验收。

## 固定来源与资源安装

原件固定为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`，来源标题为 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。固定提交没有 LICENSE 文件；本批没有将参考仓库运行时代码引入 Java，项目继续保留 LunarCore 来源与 AGPL。

`src/test/resources/fixtures/g61-npc-first-talk-4.6.json` 保留 50 份完整原件来源、240 个完整对象、原图树和十份本机 bytes 的身份。此夹具创建时六图尚未安装，`installation=missing` 和 `decodedEntireBinaryGraphs=false` 是当时的真实状态，不能回写为后来的安装或解码结果。

后续 `scripts/recover-client-g61-npc-graphs.py` 已完整读取本机十份 bytes，逐份检查尾部消费和整图与固定原件相等。任务字段、工厂、继承和选项类型依据本机 GameAssembly 与 metadata 静态读取，未执行客户端 DLL。完整解码证据另存于 `build/g61-client-graphs-root-proof.json`；12 项来源 Python 检查通过。

root 随后仅新增下列六图，`build/g61-original-graphs-install-audit.json` 已通过：安装前 70,615 份原资源没有改动或缺失，没有预期外添加，运营配置未改。新增 JSON 的完整对象与固定原件及本机解码结果相等，格式和键顺序导致原件与安装文件的字节 SHA 不同；这里不声称原始字节拷贝。

- `Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json`
- `Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json`
- `Config/Level/NPC/W0/NPC_DefaultStory_500001300.json`
- `Config/Level/NPC/W0/NPC_DefaultStory_500001301.json`
- `Config/Level/NPC/W0/NPC_DefaultStory_500001302.json`
- `Config/Level/NPC/W0/NPC_DefaultStory_500001303.json`

旧 Phone 报告保留创建时两份 NPCDialogue 缺失的事实。当前 `scripts/smoke-daily-message.py` 的来源预检接受两份都缺失或两份完整匹配两种状态，部分安装、嵌套字段改变、额外字段和固定原件 SHA 改变均拒绝；其他旧原件仍要求既有字节 SHA。新增九项来源 guard 曾有两项真实失败，修复后九项全绿，不改写历史报告。

旧来源 Python 首项曾把夹具中的历史 `missing` 当作当前缺失，六图加装后已实际复现一项失败。该测试现分别检查原历史身份与当前完整匹配，39 份完整原件 SHA 和37份旧文件字节 SHA 均保留；原四项来源检查全绿，证据为 `build/daily-phone-historical-source-red.log` / `build/daily-phone-historical-source-green.log`。这属于来源检查兼容，不能累计为 Java 行为 RED。

## 初遇身份与原菜单

原 NPC 的加载条件为主任务 1000401 已完成，卸载条件为主任务 3000604 已接取且延迟到楼层重载。运行时只接受当前 Maze 平面 20002、楼层 20002001、维度和剧情线均为零、原入口 200020102 或 200020103 中实际加载的唯一 G61 NPC。NPC 编号、组、配置实例、Owner、加载侧、加载条件和原图全文均须匹配。

原对话收集 90000103 的初遇状态；未见过时进入 `NPC_DefaultStory_500001301.json`。该图报告真实初遇后，后续原菜单按各原子任务的 `Started` 条件选择 300060101、300060201 或 300060301。菜单保留原 MissionID、DialoguePath 和原 Talk → Act 触发链。本批没有把任意 Performance 解释为目标完成，也没有放宽通用脚本路径。

`NpcFirstTalkState` 将 UID、性能编号、实际场景与入口、维度、剧情线、组、配置实例、NPC 编号、实际实体编号、场景运行身份、事件身份、记录时间、原前置完成时间及完整来源签名保存在同一个 Player 文档中。重复报告读取原记录；旧通用 FirstTalkReport 不能替代此记录。保存失败恢复原对象，查询、重复报告和恢复不会补发奖励或伪造任务进度。

原 4.6 查询命令 2142 的性能列表为字段 4；完成命令 2161 的性能编号为字段 13。协议定义与原生调用证据在 `scripts/fixtures/g61-first-talk-client-4.6.json`，探针启动前再次核对实际 schema。场景中的实际 NPC 身份由服务端校验，完成请求没有可信的 NPC 实体字段供客户端指定。

## 已发生的 Java 验证

`G61NpcFirstTalk46Test` 使用实际 MissionManager、Scene NPC 加载、4.6 请求分派、出站响应及真实 Morphia Player 文档读取。夹具明确提供原前置完成、受控时钟和场景基础条件，不运行原客户端演出。

第一轮 40 项因夹具缺少 required 日常目录在 setup 失败，不计行为 RED。补齐原始 required 目录后，40 项中 15 项为纯 AssertionFailedError，25 项兼容与拒绝对照通过，零 error / skip。有限实现及共享挂钩落地后，这 40 项全绿；归档为 `build/g61-fixture-corrected-red-tests/verification.json` 和 `build/cast-g61-ammo-corrected-verification.json`。本批最终全量及冻结产物另由 root 记录，此处不提前填写未发生结果。

## 同日解锁活跃目标

本批原生首次登录时原 1010403 尚未完成，DailyManager 已保存当天空选池；同日补齐原前置后，3000602 可以真实自动接取，但旧同日分支不会补入原活跃目标 2100003。原 Quest 的解锁字段为 `FinishMission / [1010403]`，该缺口已通过实际停止后的 H2 文档确认，不用将首轮委托推到下日规避。

`DailyActiveLateUnlock46Test` 先实际得到 19 项中 12 项纯行为 Assertion 失败、7 项对照绿，零 error / skip，归档为 `build/daily-wolf-chess-native-gaps-red-verification.json`。最小修复仅为同日尚未占用的原池 Type 补选；已有 DOING、FINISHED、CLAIMING、CLOSED 对象、观察身份、日期、积分、Phone 与奖励记录不重抽、不清空。沿用原排序及日轮选策略；追加保存抛异常时撤回新增对象，倒退时钟不追加。

另增两项保存拒绝保护后共 21 项。首次正向运行的两项后置重复结束电话预期误写为 SUCC；实际既有拒绝码为 MESSAGE_SECTION_CAN_NOT_FINISH，已仅纠正两行预期，保留全部信用、活跃、防重及身份断言。这两项不计新的生产 RED。修正后的 21 项全部通过，包含两次真实 Morphia 读及空池、已领奖池保存失败后的同日重试；最终小集合共 187 项通过，见 `build/daily-wolf-chess-native-gaps-corrected-green.log`。本批全量另由 root 记录，取消的旧 full build 不计通过。

## 原生验收与边界

`scripts/smoke-g61-first-talk.py` 与三份 G61 Java helper 已实际执行。七项 Python guard 覆盖独占报告、lease、首异常保留、正常 stop 资格、不同 HTTP / Mongo 端口及显式无代理的 loopback HTTP。

前两次输出 `build/g61-first-talk-current-native.json.failed.json` 与 `build/g61-first-talk-current-native-second.json.failed.json` 保留。首跑暴露工具时钟把新前置完成时间放在受控时钟之后的问题：5 项真实保护回归中 4 项先失败，改为每次投影当前墙钟且仍保持同一初始日界后，5 项全绿，与原七项 guard 合计 12 项通过。第二跑暴露上述生产同日选池缺口，两次均不计完整原生验收。

第三次使用修复后冻结产物，实际执行命令为：

```powershell
python -X utf8 scripts/smoke-g61-first-talk.py --jar build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar --output build/g61-first-talk-current-native-third.json
```

产物 SHA256 为 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09`。独立目录为 `build/smoke/daily-message-g61-native-z2uk57_8`，汇总日志为 `build/g61-first-talk-current-native-third.log`。实际 140 请求、160 检查、7 快照通过；五个进程各有一条正常 Stopping server 日志，四次同 UID H2 恢复。五份 G61 与四份 Phone 只读检查完成 18 次独立实际 Morphia Player 读取，数据库字节和 Player 文档均未被检查器改写，最初 NPC 观察对象逐份相等。

初遇与首轮委托同在受控日 20737：先保存未解锁空池，再同日解锁，原 2100003 首次出现为 DOING / 0。原 NPC 菜单条件、300060201 Talk、1211401 半段回复、恢复后余下回复及真实提交电话都通过。下一受控日 20738 使用独立的新接受身份和新 Phone receipt，旧轮归档历史完整保留。两轮各实际取得 5,000 主委托信用点及 200 活跃度，最终信用 10,000；重复请求不重发。50 份完整固定原件预检匹配，163 个本次使用的运营源文件 SHA 前后不变。独立复核保留于 `build/story-script-draft/g61-third-native-independent-audit.json`；这是一份通过报告，不重复计前两失败或旧 Phone 验收。

前置 1000401、1010201、1010403 的完成历史、单一原 3000602 目录与受控时钟是明确隔离夹具；初遇、委托目标、电话历史和奖励未预写。原菜单资格检查读取原 JSON 和真实任务状态，仍不能代替客户端执行菜单图、演出和 UI。完整官方剧情、自然墙钟跨日、崩溃原子性及真实客户端验收仍待完成。
