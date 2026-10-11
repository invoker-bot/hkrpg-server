# 4.6 原骑兽任务 6029860 的有限观察与领奖

本批接入原 `RideCavalryBeastCnt` 任务的服务器观察流程：满足原活动资格及明确的运营窗口后发布 DOING/0；冻结原生请求 915 携带当前实际加载的原骑兽 NPC 的 group 与实例 ID；保存一次原 Progress=1 的完成收据，再由既有领奖流程预约、支付原奖励并关闭。官方客户端上下骑乘、操控、移动和动画尚未验收。

原始来源为 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`。固定夹具包含 40 份与本地 reference 字节相同的源文件、43 条完整 Excel 原行、27 份完整组/Floor/任务文件；不是根据注释、NPC 名字或猜测字段拼接资源。reference 仍在 ignored `references/`，不会成为运行依赖。

| 原始配置 | 本批使用的原值 |
| --- | --- |
| QuestData | 6029860，QuestType=3，AutoUnlock，RewardID=3129020 |
| FinishWay | RideCavalryBeastCnt，Equal，ParamInt1=6029860，Progress=1 |
| CycleQuest | IsNonPeriodic=true，ActivityModuleID=3001301 |
| ActivityPanel/Config | 30013，原等级 21、主任务 1000510 条件 |
| QuestTimeLimit / FuncUnlock | WorldID=501，UnlockData106001，需要完成子任务 104010102 |
| RewardData | Hcoin 20，物品 213×4 |
| 实际 NPC 标签 | NPC.CavalryBeast，TagHash=2136885204 |

资格读取原配置，并保留现有任务状态及取消记录校验。运营窗口必须是配置中唯一、当前有效的 30013/3001301；服务器不会自动打开历史活动。前置子任务 104010102 属于主任务 1040101，G172 的 Atmosphere 子任务 104010320 属于主任务 1040103，两者均按原完整 `MissionInfo_*.json` 与 `.partial.json` 合并，保留各自真实 MainMissionID。

本批仅接受 P10401/F10401001、World501 的 Town 当前场景。源数据共 21 份骑兽摆放文件，其中该 Town 有 16 份：15 份被原 Floor 的实际维度引用，共 55 种组合；G172_D1001 未被任何原维度引用，拒绝计数。P40457/F40457001 的 5 份 Raid 摆放保留来源记录，当前拒绝计数。

| Town 原 group | 带标签的原实例 / NPC | Floor 实际维度组合数 |
| --- | --- | --- |
| 172（基础文件及 8 份被引用变体） | 400026 / 3158 | 17 |
| 176 | 400001 / 3185 | 1 |
| 421 | 400001 / 3186 | 1 |
| 601 | 400001 / 3185 | 1 |
| 602 | 400001 / 3158 | 17 |
| 825 | 400001 / 3127 | 17 |
| 85 | 400001 / 3184 | 1 |

报告须同时通过当前玩家拥有场景、plane/floor/entry/world/story 一致、没有战斗/instance/story 切换、原 MapEntrance、Floor/Group/NPC 完整来源、真实维度与加载 group 等检查。现有 `MissionManager.shouldLoadGroup` 会重新检查原 Atmosphere 和任务 owner，加载后门槛关闭也会拒绝。NPC 须唯一注册在当前场景，group/instance/原 NPCID 和摆放位置、旋转匹配。G176 同组 400002/N3183 未带骑兽标签，不能代替 400001/N3185。

本机 4.6 静态消费链已由 `scripts/inspect-cavalry-quest-client.py` 核对 35 项：实际 OnTaskBegin 调用骑兽入口；入口选择已接受的 6029860，再从 GameEntity 读取 group 和实例；请求 Writer 对应 quest 字段 7、实例字段 8、group 字段 11。冻结协议中的 `prop_id` 在此承载实例 ID，对象仍是 NPC。原 `+0xe0` guard 是 `_AliveState`，`_EntityType` 在 `+0x100`；本批没有猜测实体类型位掩码。该静态检查未启动或修改客户端，不能代表官方骑乘物理验收。

915 入口只接管原骑兽目标或该原 FinishWay 类型；重复 quest（包括末值改成其他任务）、重复实例、未知字段、缺字段和 uint32 溢出在计数前拒绝。报告本身不能创建缺失的接受记录。状态保存原 acceptance UUID、窗口、服务器时间、scene runtime/entity ID、场景身份及完整 group/NPC 指纹；收据与进度、完成时间一致才属于该任务。旧 legacy QuestState 的 CLOSED 不能冒充本域完成。

计数构造下一份状态后保存；保存失败恢复原域并允许重试。重复有效报告只确认原完成，不增加进度或自动付奖励。领奖沿用 `QuestManager.claim`：先持久化 CLAIMING，再调用库存，完整支付后 CLOSED；支付不完整保留 CLAIMING 并拒绝重复付奖。继承库存多文档持久化的限制，未声称跨文档崩溃事务或自动对账。原资格和来源仍有效时，已完成历史在活动窗口结束后可供前置校验；展示、新报告和领奖仍要求当前窗口。

## 验证记录

旧 103 项基线及第一次 GREEN 尝试包含 17 项 G172 初始化 NPE：初版夹具遗漏 1040103，且错误地用 partial 文件没有提供的 header MainMissionID 改写了子任务 owner。JUnit 把该 NPE 记作 failure，而非 XML errors；这 17 项不得计为初始行为 RED。日志及原 XML 保留在 `build/rest-floor-cavalry-red-tests/test`、`build/cavalry-quest-first-green-failed-tests/test` 和 `build/cavalry-draft/rules-diagnostic.log`。

修正夹具后，主代理用真正没有骑兽实现的冻结 `build/rest-merchant-floorload-runtime.jar` 单独运行 104 项测试，classpath 排除了当前 main output。SHA256 为 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b`。结果 96 项 AssertionFailedError、0 errors、0 skipped，无初始化 NPE；新增原 owner 合并一致性断言通过。真实 RED 报告为 `build/cavalry-corrected-fixture-baseline-red.json`，原 XML 在 `build/test-results/testCavalryBaseline/`，不被普通 test 输出覆盖。

`scripts/inspect-cavalry-beast-sources.py` 已运行 187 项源检查；调用实际 Java canonicalizer 的 70 个完整指纹均匹配，无 Python/Java hash 差异。报告为 `build/cavalry-source-verified/verification.json`。Rules 修正后的独立诊断确认来源加载、已加载数据、原资格和唯一运营窗口成立；诊断文件为 `build/cavalry-draft/rules-diagnostic-fixed.log`。该诊断不是 GREEN 或网络验收的替代。

104 项骑兽专项已通过。主代理冻结 `build/cavalry-beast-runtime.jar`，SHA256 为 `1bffb1356ea7a719675d06eee8ffe6ea5cb618d507a414bf6239bd61e412f58a`，记录在 `build/cavalry-beast-build.json`；冻结清单包含 2492 项输入。共享回归首次 31 类/1210 项有 21 处 Morphia failure，已归档 `build/cavalry-shared-morphia-red.json` 和原 XML；实际根因是另一条线的非 static 测试 Player 内部类被包扫描发现，骑兽实现未因该错误修改，共享复跑结果另行记录。

同一冻结产物的来源检查再次通过 187 项与实际 Java canonicalizer 的 70 个完整指纹，报告为 `build/cavalry-source-C-verified/verification.json`，报告明确记录上述产物 SHA256。`scripts/smoke-cavalry-beast.py` 使用全量原 Floor 与原 NPC，不缩减原维度引用、不预写目标完成或收据。

该产物的最终原生 KCP/H2 第五次运行全部通过：`build/cavalry-beast-C-fifth.json`、`build/cavalry-beast-C-fifth-native.log`，测试目录为 `build/smoke/cavalry-beast-m2g8ehss`。共 49 请求/74 检查、3 次正常停服、2 次同 UID 10001 的正常 H2 进程重启；产物 SHA256、测试配置和 2281 份受保护来源文件前后不变。分段为 bootstrap 6 请求/4 检查、report 34/55、resume 9/15。官方客户端没有启动。

原生场景查询确认 P10401/F10401001、entry1040101、World501 及实际 scene runtime，原 G176/400001/N3185 与同组未带骑兽标签的 400002/N3183 均唯一加载。七种非法 wire 返回 5；未带标签的原 NPC、错 group 和错实例返回 2407，各次实际任务查询仍是原 6029860、DOING/0、finishTime0。原请求成功后为 FINISHED/1、实际完成时间 1791731706，当前 NPC 实体保持；重复有效报告不改变完成时间。领奖响应及真实持久化库存均为 Hcoin+20、213+4，状态为 CLOSED/1，重复领奖明确拒绝。两次正常重启保留同 UID、实际接受 UUID/来源收据/完成时间和奖励余额；重启后的重复报告成功确认历史，领奖继续拒绝，没有再次支付。

四次停止后的 H2 检查分别执行已声明前置补齐、首次已支付状态检查、重启后检查、关闭窗口后历史检查。后三次均未改玩家数据，真实 Morphia 读取恢复了完成收据和库存；关闭窗口仅在离线检查进程中清空窗口，确认历史仍可查询且目标不再展示，未计作关闭窗口的网络验收。唯一补齐操作只设置原前置任务历史、等级21、原位置；新账号头像及 30013/3001301 运营窗口也是明确测试夹具。目标完成与收据始终由实际 915 保存，未由夹具预写。

此前四次网络尝试均保留各自 lease、日志及独立测试目录：

| 输出前缀 | 实际到达与失败边界 |
| --- | --- |
| `build/cavalry-beast-C-first` | 未启动 JVM；镜像工具有意清空 Mission 目录，脚本写回原任务文件时漏建 1040101/1040103 子目录 |
| `build/cavalry-beast-C-second` | 服务器 Mongo bind 地址占用，readiness 请求又遇到 BadStatusLine；原 http/mongo 为不同端口 55314/55315，未证明占用进程归属，未进入 KCP |
| `build/cavalry-beast-C-third` | bootstrap 实际 6 请求/3 检查通过并正常 stop；脚本 UID 正则变量覆盖 readiness 布尔值，统计时 TypeError，尚未执行 H2 前置补齐或目标报告 |
| `build/cavalry-beast-C-fourth` | bootstrap 与 H2 前置补齐通过，真实 Town 场景和两个原 NPC 已加载；探针用旧 Java `QUEST_DOING` 名称比较 4.6 枚举全名，首次任务查询断言失败，尚未发送 915 |

四个失败分别对应 `build/smoke/cavalry-beast-yyigwcfg`、`cavalry-beast-ikvz_o4l`、`cavalry-beast-5y2baqbl`、`cavalry-beast-pn7_2xxt`。这些记录不计作骑兽目标报告或领奖验收；前三次脚本修复由实际脚本回归先复现，第四次严格期望改为原 `Common.proto` 与冻结产物 descriptor 中的 `QuestStatus_QuestDoing/QuestFinish/QuestClose`（1/2/3），保留进度、完成时间与防重断言，并补逐次 `RIDE_QUEST` 状态/字段值/消息 hex。枚举 preflight 报告为 `build/cavalry-draft/native-enum-schemas.json`。Java 实现及 104 项行为断言保持冻结。

可复跑的来源检查：

```powershell
python -X utf8 scripts/inspect-cavalry-beast-sources.py --jar build/libs/hkrpg-server.jar
python -X utf8 scripts/inspect-cavalry-quest-client.py --client-dir C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current
```

可复跑的隔离网络检查：

```powershell
python -X utf8 scripts/smoke-cavalry-beast.py --jar build/libs/hkrpg-server.jar
```

脚本只在新建的 `build/smoke/cavalry-beast-*` 目录创建测试配置、运营窗口和 H2 数据；控制夹具只补原前置任务历史、等级、位置和测试账号头像。它核对实际原生任务进度、报告拒绝、领奖响应、真实保存收据和库存，以及同 UID 正常重启后的防重付。官方 UI、实际骑乘输入、Raid 摆放、非正常崩溃恢复及整个活动仍不在本批完成声明内。

默认报告名包含本次时间戳。指定已有报告路径会在读取运行产物和准备资源前拒绝；运行前以独占创建的 `.lease.json` 预留该报告，结束或失败后保留 lease 和日志；最终报告也以独占创建写入。已有证据不会被覆盖，重复使用已预留路径同样拒绝。本轮 loopback HTTP opener 禁用系统代理；http/mongo 在准备阶段须为不同 TCP 端口；尚未 ready 的进程不计正常停服。停服失败追加 `cleanup-failures.log` 和首异常 notes，保留首次启动或探针错误。

`scripts/tests/test_smoke_cavalry_guard.py` 的 8 项保护回归均通过。独占报告/lease、任务目录准备、真实自有 HTTP target/proxy 路由、端口复用、首异常保留与未 ready 的 stop 计数、实际 main bootstrap UID 标记到停服流程均有失败基线，依次保留在 `build/cavalry-draft/smoke-guard-red.log`、`smoke-preparation-red.log`、`smoke-loopback-red.log`、`smoke-ports-cleanup-red.log`、`smoke-bootstrap-red.log`；最后 GREEN 为 `smoke-bootstrap-green.log`。这些脚本回归不启动服务器 JVM，不计入 NativeKCP 或游戏玩法检查数。


本批最终集成完整 `scripts/build.ps1 -RerunTests` 实际通过492类、13,444项，失败/错误/跳过为零；2,494份编译输入前后无变化。完整XML为 `build/cavalry-phone-full-tests/test`，证据为 `build/cavalry-phone-full-verification.json`，对应冻结D的SHA256 `8d81c933127d7f62b8c022051d5b1d8dd4d8a1a30a4ec917134109c3fd8d7412`。骑兽原生网络为独立C，手机日常原生网络为D；二者的实际数量和SHA分别保留，未将C网络冒称D的网络复验。
