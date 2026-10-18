# 3011014 演出入口与临时 NPC

已补入原始 E 演出的开始回执、开场临时 NPC 的服务端生命周期、14 份缺失入口和三名相关 NPC 的初次对话记录。**尚不能宣称完整客户端演出通过**：真实游戏客户端没有连接本地测试服执行相机、动作、捕获、台词分支和中间 NPC 替换。原始演出图保持完整，不把开始确认或任务回报标作已播放证据。

## 固定原图与本机协议

来源仍为 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，版本 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。既有 68 份完整原件及 195 个选中完整对象保留；另将三份完整 DialogueItem 的原始字节、SHA256 和对象指纹保存在 `src/test/resources/fixtures/daily-recover-performance-entrances-4.6.json`。原提交没有 LICENSE/COPYING；这些是来源明确的资源数据，未引入参考代码、可执行程序或运行时依赖，不把它们声明为 AGPL 数据。

`StarPerformanceRelayCsReq` 命令 1224：performance_id 字段 1，performance_type 字段 12；响应 1296 的 retcode 字段 10。固定本机国服 4.6 的 `ELevelPerformanceType` 常量表读取到 E=4。静态读取使用 `scripts/analyze-client-46.py`，证据为 `build/recover-performance-native-20261014.json`，客户端文件 SHA 为 `GameAssembly.dll=8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`、metadata=`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。字段由冻结描述符检查；将该请求作为本项目开始见证属于服务端实现选择，未取得官方客户端实际发送顺序的证明。

委托本身只接通 301101401、402、406—411；三名 NPC 的初遇及后续对话开始见下节。未知演出、类型、场景、未接取目标、重复 wire 身份、变化资源和损坏记录明确拒绝。开始不完成目标、不选分支、不发奖，也不扣物。

## NPC 初次对话与结束回报

原 DialogueMain 先查询初遇记录，播放对应初遇或后续图，再显示菜单。这三项原本没有匹配的 FinishFirstTalkPerformance 任务，不能沿用通用任务回报来保存 NPC 初遇。

| 原 NPC / 组 / 实例 | 初遇 | 后续对话 |
|---|---|---|
| 11302 / 99 / 400002 | 501028201 | 501028202 |
| 11104 / 93 / 400001 | 501028001 | 501028002 |
| 11205 / 76 / 400001 | 501030700 | 501030701 |

`DailyRecoverIntroductions` 对六份完整原图、六条 PerformanceE 原行、既有完整委托来源与 14 份入口进行核对。初次回报还要求实际普通场景中有对应原 NPC：包括楼层、组、实例、NPC、位置、旋转及真实加载组。六项 E 开始只开放对应位置；后续对话要求已记录原初遇。初遇成功后不完成日常目标、不发物、不扣物、不发奖励。

`DailyRecoverIntroductionState` 在 Player 内单独保存 UID、原初遇、实际场景/实体/运行编号、观察时间、随机事件身份与固定来源。它是全局历史，不随日常委托换日或取消清除；旧通用 FirstTalkReport 不可替代。保存失败撤回新记录，重复回报不再次保存；损坏 UID、来源、时间或显式 null 拒绝。原生 2161 的重复身份字段现在按 wire 核对，不再接受 protobuf 合并后的最后一个值。

六份完整图和六条原行保存于 `src/test/resources/fixtures/daily-recover-introductions-4.6.json`，同样来自上述固定数据提交。它们在运营资源中原已存在，本次只读核对。原图中的音频、动作和本地结束字符串由客户端执行，初遇回报处理器不解释这些字符串。

本机静态调用证实，`FinishPerformanceMission` 配置的 Key 由运行任务 `DGMIJNGMGOP.OnTaskBegin` 读取，经过 `NetworkManager` 方法 634886 发送命令 **1205**，原 Key 进入 string 字段 2，子目标身份进入字段 6。未知的 1216 请求属于 `SyncWait` 任务，不是本图的结束回调，因此没有新增该协议或改写冻结描述符。复核工具 `scripts/verify-recover-performance-graphs.py` 对完整 PE 方法边界、原字节 SHA、配置/运行类型槽及实际发送指令留证。

只读整图报告 `build/recover-introductions-graphs-20261014.json` 将 77 份 fixture 原字节与固定 Git blob 核对，再与实际运营资源逐完整 JSON 对象核对，包含 14 份补入入口、8 份完整 Act 和 6 份 NPC 初遇/后续图。14 份补入入口还逐原字节相等；其余 63 份保留运营文件已有格式，不声称原字节相等，见 `build/recover-introductions-20261014/whole-source-byte-proof.json`。报告列出原创建、捕获、动作、替换和退场任务，明确 `clientActExecuted=false`；静态图存在不等于实际播放。

## 开场 NPC 与恢复

| 原演出 | 楼层 / 组 / 实例 | 服务端开场 NPC |
|---|---|---|
| 301101401 | 10102001 / 99 / 400002 | 原初遇 NPC 已按原加载门存在，不新增副本 |
| 301101402 | 10102001 / 122 / 400001 | 原 11302 临时 NPC |
| 301101406、409 | 20121001 / 94 / 400001 | 原 11302 陪同 NPC |
| 301101407、410 | 20123001 / 75 / 400001 | 原 11302 陪同 NPC |
| 301101408、411 | 10102003 / 23 / 400002 | 原 11302 陪同 NPC |

位置、旋转、实例、NPC 编号和原组从完整匹配资源读取。开始见证先与真实日期、UID、主目标及当前子目标的接取 UUID 同存 Player，再通过现有场景刷新通知发布；重复请求不新增 actor 或再次保存。保存失败恢复原 UUID 和状态，未发布 NPC。完成对应菜单、交物或反馈目标后清理开场 NPC；恢复只接受原始加载组、当前普通剧情场景和仍在进行的同一接取。变更日期、替换接取、离开原楼层或原件损坏不能恢复。

旧版仅报告目标的存档保留兼容；一旦有开始见证，后续回报必须匹配该见证及完整入口。给物/付款跨文档的原人工对账边界仍见 [回收物说明](daily-recover-delivery-4.6.md)。开始历史仅是开始证据；不伪造 EndPerformance、本地图内 CustomString 或客户端演出完成记录。

## 缺失资源与完整画面边界

`scripts/install-recover-delivery-dialogues.py` 按固定完整原件补入 11 份 NPC DialogueMain/Pack 和三份 DialogueItem，共 14 份。全部冲突先核对，创建使用独占文件写入；已有匹配文件保持原字节，冲突拒绝，路径不得逃逸服务端资源目录。不修改 config.json、数据库、已有运营资源或客户端安装。安装报告为 `build/recover-performance-entrances-install-20261014.json`。

G76 的原对话项路径名沿用 `DialogueItem301101101`，其中实际 PerformanceID 和结束字符串指向 301101407，保留原名与原内容。Mission_301101412 中沿用 301101301 的原字符串也保持原件，不为完成界面擅自修改原图。

原 Act 已包含 409 的 400001→400003、410 的说唱动作与 400001→400003、411 的 400002→400004 以及最终退场。**这些中间步骤仍由客户端原图承担，服务端没有逐帧执行它们**。本批没有通用客户端演出解释器，也没有接收任意 CustomString 的新协议。只有实际客户端完整播放、两日三支路及中断重进通过后，才能将“完整演出”改为已验收。

## 前批验证记录

新增回归先在原实现复现未处理开始请求；修复后验证正常流程、拒绝路径、重复请求、保存失败、原 Player/Morphia 读取、资源缺失及接取替换。完整构建与实际 KCP/H2 结果如下。

- 原实现的 10 项新入口回归均失败，错误/跳过均为 0，保留 `build/recover-performance-20261014/red-xml` 与 `build/recover-performance-red-20261014.log`。修复后的首轮聚合暴露一个测试对异常包装的断言错误，已修正，日志保留；修正后的 10 项专项通过，又追加四项开始后回报与 wire 身份回归，由最终全量构建执行。
- 安装两项冲突/重复运行检查及既有六项探针清理检查，共 8 项 Python 通过。另将 14 份完整文件与固定 Git 原始 blob、fixture 和本机新增文件逐字节独立核对，见 `build/recover-performance-20261014/entrance-git-proof.json`。
- `scripts/build.ps1 -RerunTests` 全量 522 类 / 14,301 项 Java 通过，其中新增演出类 14 项，失败、错误、跳过均为 0，耗时 21 分 12 秒。源码、协议、fixture、构建及验证脚本共 3,449 份输入在执行前后 SHA 相同。XML 副本及聚合证明在 `build/recover-performance-20261014/full-xml`、`full-build-proof.json`，日志为 `build/recover-performance-full-20261014.log`。
- 冻结产物 `build/recover-performance-20261014/runtime.jar`，SHA256 `5bc6c1cf9d77df93bce50370cf33516c9d05262510fa24e62efce7e259da8794`。与原 a760 产物相比新增四个演出状态/生命周期 class，改变五个相关外层 class 与功能目录；47 个嵌套 class 字节发生变化，独立 `javap -c -p -s` 核对执行代码和签名一致。生成协议和其他 class 保持原字节，见 `build/recover-performance-20261014/payload-proof.json`。
- 同 SHA 产物以实际 UDP/KCP 执行三组两日支路：D0 为 403/404/405，D1 为 404/405/403。合计 345 次请求、396 项检查，12 次正常 stop、9 次同 UID H2 进程重启、24 次独立实际 Player/Morphia 读取。开始/重复开始、原开场 NPC 出现、对应目标后释放、真实给物/扣物/5000 信用点与 200 活跃、防重及每日接取身份均通过。并未预写选项、物品、付款或目标完成；只隔离既有前置历史、单任务池及任务/日常时钟。
- 原生全程报告 `build/recover-performance-kcp-20261014.json`，SHA256 `78a139a696daa31cde9c0dd364c1794fbd14ba4f9d976b734c5df0fb50cf4e77`；只读原始日志/存档/工具复核为 `build/recover-performance-kcp-audit-20261014.json`。每组 224 份整文件来源 SHA 稳定，14 份入口在隔离资源中明确复制；报告及工具/原件未在通过后改写。活跃菜单 NPC 的恢复另由真实 Player/Morphia 读取后的场景构建回归验证；原生进程重启矩阵恢复的是已结算阶段与演出开始见证，不宣称演出中途实际客户端重连通过。
- `scripts/smoke-server.py` 同产物两次 HTTP/Dispatch 及同账号恢复通过，见 `build/recover-performance-dispatch-20261014.json`。最初 CLI 调用使用了不支持的参数、原生输出名与静态报告重名，均在启动前拒绝；对应日志保留，不计为验证成功。

自然时间跨日、完整客户端画面、客户端演出过程中断重进、其他 NPC/任务的 E 演出、异常断电原子性和原给物/付款未决收据仍待后续验收。本次提交范围是已验证的服务端入口、开场 NPC 与固定资源安装。

## 本轮初遇与原图验证

- `scripts/build.ps1 -RerunTests` 全量 523 类 / 14,311 项通过，失败、错误、跳过均为 0，包含新增初遇类 10 项；耗时 24 分 29 秒。3,457 份源码、协议、fixture、构建及验证脚本输入前后原字节相同，最终 jar 与所有本轮网络/存档检查使用的冻结产物 SHA 相同。XML 与完整证明见 `build/recover-introductions-20261014/full-xml`、`full-build-proof.json`。
- 首批 6 项新初遇回归在前实现有 4 项纯 AssertionFailedError，错误/跳过均为 0；保留 `build/recover-introductions-20261014/red-xml` 与对应 red 日志。修复后的初遇 9 项、原演出 14 项、G61 初遇 40 项及原协议 3 项，共 66 项专项通过；之后新增取消日常不删除初遇的回归，由全量执行。
- 既有安装/原生工具保护 8 项 Python 通过；77 份完整原对象及本机 1205 发送链只读核对通过。冻结产物为 `build/recover-introductions-20261014/runtime.jar`，SHA256 `588dd4b73373eef7ae90895a20de91900b08cacab97e31c956af5b8c044cd0aa`。
- 相比前批 5bc6 产物，新增五个 class，改变五个相关外层 class 和功能目录；47 个嵌套 class 字节变化，独立 `javap -c -p -s` 核对代码与签名相同，不宣称所有属性相同。生成协议保持原字节，见 `payload-diff.json` 与 `nested-class-code-proof.json`。
- 同一 SHA 实际 UDP/KCP 三组两日流程全部通过：528 请求 / 564 检查、12 次正常停服、9 次同 UID H2 重启、24 次独立实际 Player 读取。三名初遇的查询、原在场实体、回报无奖励、重复回报，以及后续原三选一给物/交物/反馈、每日奖励和防重同时核对。每组 230 份完整来源与 9 份工具输入 SHA 稳定，隔离资源明确复制 14 份入口和 6 份既有原初遇/后续图。只隔离前置历史、单任务池与时钟，未预写初遇、选择、物品、付款或完成状态。
- 原生全程报告 `build/recover-introductions-kcp-20261014.json`；`build/recover-introductions-kcp-audit-20261014.json` 另将每份原始协议日志、停服日志、实际 H2 读取和工具/来源 SHA 独立复核。三项初遇的整个历史对象在两日与重启之间保持不变，日常委托接取身份仍各日独立。
- 另用新产物只读加载前批真实 H2：确认旧 Player 文档缺少新字段，实际 Player 读取后得到空且有效的新初遇状态，不伪造见面记录，旧数据库前后 SHA 相同。证据为 `build/recover-introductions-20261014/legacy-proof.json`。
- 同 SHA 的 `scripts/smoke-server.py` 两次 HTTP/Dispatch 与账号正常进程重启恢复通过，见 `build/recover-introductions-dispatch-20261014.json`。这些服务端检查不包含官方客户端画面或演出中途实际客户端重连。

## 本轮客户端连接边界

本机游戏只到达标题画面，尚未连接本地测试服，随后由用户退出。只读检查发现系统代理为本机 10809 的 v2rayN/sing-box，采用 Rule 分流；已检查的配置没有把游戏入口改写到本地 HKRPG HTTP 8080 / KCP 23301，未捕获实际游戏连接的终点。诊断报告为 `build/recover-introductions-client-connection-20261014.json`。本轮没有改系统代理或证书。

启动时游戏自动下载了资源更新，屏幕资源标签从 D16566615/A16566615/L16566587 变为 D16728363/A16704710/L16700845；不能声称整个客户端目录原字节不变。DLL 与 metadata 仍匹配已固定 SHA，因此前述 1205 静态调用核对成立。进一步尝试直接读取本机八份 Act 时，当前 DesignV 索引不再匹配旧固定索引 SHA `2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`，工具在读取图之前拒绝。失败日志保留于 `build/recover-introductions-20261014/client-original-acts.log`；没有放宽固定索引保护，也没有把这次尝试记为八份当前客户端图通过。77 份服务端完整原图核对、真实协议/H2 与客户端画面验收分别记录。

## 2026-10-15 后续验收

新增 `scripts/verify-client-recover-acts.py`，显式选择经过独立完整核对的新索引 `5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed`。固定 DLL 与 metadata 保持前述 SHA；基础 G61/休息区/任务图证据仍按原 profile 校验。八份 Act 共 4,176 字节，全部消费，并逐完整 JSON 对象同时等于 724b 固定原件和当前运营文件；当前客户端的 409、410、411 原始中间替换段也包含在核对中。报告为 `build/recover-acceptance-20261015/client-acts.json`，各记录的索引键、长度和 SHA 另固定于 `scripts/data/client46-recover-acts.json`。

此次补齐的是只读解析证据，不增加服务端逐帧执行器。三类任务逐原生实例字段、实际工厂表赋值、分配类型槽、读取方法及完整 PE 函数边界/字节 SHA 核对：

| 类型 | 实际任务标签 | 工厂 RVA | 读取函数 RVA |
|---|---|---|---|
| CaptureNPCToCharacter | 936 | 1cedb720 | 1d529d10 |
| CharacterTriggerFreeStyle | 993 | 1cedc860 | 1d54c360 |
| SwitchCharacterAnchor | 3428 | 1d61e260 | 1db13520 |

原生读取函数中的字段掩码与赋值顺序逐项核对，不由类型字段顺序推断。两条非整数字面量仅接受本批完整原件中的固定对应：4294967300→0.5、6012954220→0.7；未实现通用 FP 转换，也不将邻近整数四舍五入成这些值。原 G61 profile 仍拒绝它们。五项新图回归覆盖八图完整对象、所有图截断/尾随字节、未知任务/字段掩码、精确字面量和报告保护。

77 份原件、14 份入口、8 份 Act、6 份初遇/后续图及 1205 原生结束发送链重新核对通过，报告为 `build/recover-acceptance-20261015/whole-source-and-1205.json`。这些是静态数据和原生代码证据，报告继续明确 `clientActExecuted=false`、`clientUIVerified=false`。

连接验收使用 `build/recover-acceptance-20261015/client` 中独立复制的已安装客户端，11,594 文件、约 109 GiB；原安装未改写。仅将副本 `ClientConfig.bytes` 内两个官方 Dispatch 地址替换为 `http://127.0.0.1:18080/query_dispatch`，原配置备份和双份摘要在同目录上层保存。测试服使用新配置和新 H2，HTTP 18080、UDP/KCP 23311，复用已验证的 588dd4 产物和现有只读资源；没有启动参考仓库的 launcher/DLL，没有改系统代理或证书。

独立副本实际发出 `query_dispatch`，服务端日志在 15:16:31 记录；测试服 HTTP readiness 为 200。当前画面停在首次用户协议提示，自动点击和回车没有触发下一步，已请求用户手动推进。SDK 登录、本地 Gateway/KCP 登录、完整播放和播放中断重进尚未通过，不能将本次 Dispatch 请求计为游戏世界连接或完整演出验收。

路径/索引/回退、新 Act、G61、休息区及基础图读取器的 74 项专项通过。扩大运行时另外两项旧索引的实机七图检查因客户端已热更新而被原 SHA 保护拒绝，日志单独保留于 `python-focused-rerun.log`，没有把它们列入通过数。`scripts/build.ps1` 成功；Java 和 JAR 任务均为 UP-TO-DATE，本次未重跑前批 14,311 项 Java，运行代码和冻结产物未改变。日志、连接配置与边界报告均位于 `build/recover-acceptance-20261015/`。

## 2026-10-16 SDK 登录补正

前批只处理Dispatch，原生账号SDK仍指向官方Passport，服务端也缺少原生ma-cn密码/会话路径。此次对独立副本补入固定SDK账号地址与登录公钥数据补丁，并新增本地RSA账号识别、会话校验及token交换；既有运营安装、配置和数据库保留。服务端回归、HTTP/H2与实际登录画面的不同边界见 [国服SDK登录记录](cn-sdk-local-login-4.6.md)。3011014的完整播放和中断重进继续待真实客户端登录后验收。

## 2026-10-18 当前安装与专项复验

从实际安装目录 `C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current` 只读复验，八份完整 Act 共 4,176 字节通过既有固定索引、DLL/metadata、原生字段与完整对象核对；77 份原件、14 份入口及 `FinishPerformanceMission` 的 1205 发送链全部通过。14 份入口逐原字节相等，77 份运营文件 SHA 均匹配 `.local/compose/artifact/resources.sha256`，此次未发现这些入口仍有缺失。本轮复验开始时，Compose 宿主产物与 `build/login-first-talk-final-20261018/runtime.jar` 同为 `bce6e60ccbab07a028eb242464ad2c7e6f00bcf2b4c2853aaf58b90ad3253093`。证据在 `build/recover-followup-20261018/client-acts.json`、`whole-source-and-1205.json`、`current-resource-audit.json`。

本轮实际重跑 G61 原 NPC 初遇 40 项、回收物演出入口 14 项、回收物初遇 10 项，共 64 项 Java；使用本地 Java 21、TCP fallback 和三个测试进程，全部零失败、错误、跳过。另有 Act 解码/拒绝边界、入口安装保护和原生工具清理保护共 13 项 Python 通过；该专项阶段没有重跑 Java 全量。日志、三类 XML 及准确计数见同目录 `java-focused.log`、`focused-xml/`、`python-focused.log`、`focused-validation.json`。此前冻结全量内的回收物交物类 57 项仍是历史结果，不混入本轮 64 项。

此次未启动或控制游戏客户端、未访问正式 `.local/compose/work` 存档，也未重跑三组两日 KCP/H2 矩阵。当前静态图完整不等于实际播放；客户端真实开始请求顺序、相机/动作/中间 NPC 替换、三支路完整播放、演出中断重进及自然跨日继续待验收。原池解锁任务为 2010501，G99/G93 另要求完成 1010501，G76 要求完成 1010902；新账号不能被默认视为已满足日常与地图加载条件。既有隔离探针的前置历史和受控时钟不能写入正式存档，也不能据此声称自然流程已通过。

随后完成 TextJoin 修复的统一全量验证：545 类、14,473 项实际执行通过，包含上述 64 项；新 JAR 已部署到 Compose，资源清单保持不变。该更新及两轮真实 Compose 网络检查见 [Avatar 类剧情文本默认值](textjoin-avatar-default-4.6.md)。这些结果仍不能代替 3011014 的客户端演出验收。
