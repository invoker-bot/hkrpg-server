# 客户端与服务端日志

## 复查一次卡点

先按同一时段查看两边日志。客户端从原桌面入口打开后，独立读取器将本次会话写入 `%LOCALAPPDATA%/HKRPG/client-debug-*.log`，每行开头是读取时的 UTC 时间。服务端新增诊断以 `at=` 标记 UTC 时间，`session=` 是本次服务进程内的连接编号。`floor`、`group` 和 `instance` 对应场景放置，方便对照客户端的任务号或 `PROP_组-实例`。原始客户端日志保留在 Unity 的 `Player.log`。

本机 Compose 服务端日志在 `.local/compose/work/logs/latest.log`，较早记录按小时压缩。也可以运行 `docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml logs --tail 200 server` 查看近期输出。

| 日志关键词 | 可定位的信息 |
| --- | --- |
| `Unhandled` | 协议版本、命令编号、能识别的命令名及连接阶段。 |
| `Packet rejected` | 失败发生在分帧、请求头、协议转换、处理器或传输阶段。保留异常类型和有限调用栈，已知映射缺失附上原字段名。 |
| `Scene placement skipped` | 缺定义或加载失败的楼层、维度、组、实例和物件、NPC 或怪物资源号。缺失副本的旧入口另记 `reason=missing-raid`、实际楼层、组和副本号。 |
| `Scene placement skipped: reason=inactive-mission-group` | `inactive-mission-group` 的位面、楼层、维度、剧情线、组及归属任务，定位旧组或错步骤的显式加载。 |
| `Mission script skipped` | `missing-optional-performance` 的主任务、子任务、演出及连接场景，定位原始失效的非必需分支。 |
| `Gameplay save failed` | 固定保存阶段、任务或奖励编号、数值收据摘要、异常类型及场景，区分预约未开始投递和投递后的完成写入。 |
| `Reward delivery incomplete` | 奖励号、数值收据摘要与保留的 pending 状态，定位需要复核的部分或异常投递。 |
| `Story action interrupted` | 子任务、动作序号及预约保存、执行、完成保存或已有 pending 的固定阶段，定位剧情动作中断。 |
| `Quest reward incomplete` | 普通或每日目标、奖励号、部分投递或已有 CLAIMING 等固定原因，定位无法自动重发的领奖。 |
| `Gameplay result` | F 交互、任务对白和提交、演出请求、切图、副本和战斗拒绝的命令、对象、返回码及原因。`subject` 随命令表示任务、关卡、入口或交互配置，物件交互另记组和实例。 |
| `Player tick error` | 定时更新的场景、异常类型及有限调用栈。 |

例如，客户端出现演出异常而该时段服务端没有对应交互或任务回执时，可以先按客户端演出编号定位本地脚本。两边日志仍需按时间、场景和请求实际出现情况交叉确认。

## 重复计数

服务端按类分开计数，每类每分钟最多记录 96 个不同定位键的首次详情。未处理命令按协议版本和命令号合并，玩法与场景故障还区分位置和原因。`Diagnostic repeats` 保留总重复数、超额数和重复最多的六个定位键，正常停服时也会结出最后一个窗口。新增诊断只记录数值编号、固定原因和有限调用栈，排除请求正文、账户、令牌及任意异常正文。

客户端按警告或错误正文和调用栈合并同一窗口的重复项。`[event #N]` 是首条详情，`[Unity repeats]` 中的 `#N+K` 指它另外出现了 K 次。物件名称冲突只忽略临时实体编号，保留组和放置编号。不同演出编号或调用栈各有首条。首次记录最多保留 48 行或 32 KiB，原始 Unity 日志留有完整记录。摘要在窗口到期或读取器正常退出时输出。

## 增加诊断

服务端优先复用 `GameDiagnostics`。为异常、明确拒绝和意外跳过保留稳定的原因、发生位置与结果，数量或实时对象编号放在首条详情中，避免它们使相同原因被拆成不同记录。客户端只在已脱敏的外部读取器中折叠重复记录。验证时同时检查一个重复故障和一个位置或原因不同的故障。

## 验证范围

旧实现先复现了服务端缺少定位、客户端重复打印整段调用栈的问题。全量构建通过 19,914 项 Java 测试。独立 4.6 连接在大矿区验证了 47 次原生请求，包含未处理命令、两个不同任务的拒绝和一个不存在的交互物件。对应日志留下 5 条首发详情，34 次重复由两条摘要计数，之后仍能查询当前场景。两次独立登录和重启恢复也通过。

82 项客户端读取器及启动相关测试通过。正式游戏正在运行时，两项写桌面快捷方式的测试被既有保护拦截。新读取器通过临时进程和原始日志离线回放验证，正式客户端下一次从桌面入口启动后的日志待实机观察。

## 本机安装记录

2026-11-10 本机 Compose 已更新。安装包以原运行包为底，重编译日志改动，其中 `GameSession` 与 `Gameplay46` 按原运行包的历史活动功能范围编译。用反汇编核对修改前的行为，并用隔离包验证两次登录、场景查询和重复计数后才安装。正式服务短暂重启后健康检查和存档核对通过。

- 日志源码提交 `ed065c2`。
- 运行包 SHA-256 `b9c609b5e43ccd7de5a028546e817c7c92b34e25609dcf12b6b0f39611dedb45`。
- 停机备份 `.local/backups/game-diagnostics-20261110-162712/`。
- 本机验证报告 `build/future-diagnostics/` 中的 `full-summary.json`、`native-release.json`、`login-compatible.json`、`deployment.json` 和 `compose-after.json`。
