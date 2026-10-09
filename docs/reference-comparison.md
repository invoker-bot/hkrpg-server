# 参考项目对比与选型

证据来自 2026-09-30 下载的固定源码；提交记录详见 `../references.lock.json`。所有参考项目均保留在本地 `references/`，未执行其附带的启动器或 DLL。

| 项目 | 采用的价值 | 已确认的限制 | 主工程处理方式 |
|---|---|---|---|
| [March7thHoney-OpenSource](https://github.com/Mar7thLover/March7thHoney-OpenSource) | C# 玩法覆盖较广；任务/目标管理、异相仲裁、货币战争、Muip、4.6 分组协议 | 上游进度表仍列出任务、宇宙、活动和货币战争边界缺口；处理器数量不等于完成度 | 主要玩法补齐对照，协议适配另做 |
| [pearl-sr](https://git.xeondev.com/HonkaiSlopRail/pearl-sr) | Zig 的较新协议、战斗配置、挑战和列车模式 | README 标注 4.7 beta，实际最新协议提交为 4.6.51；任务等可返回预设完成；未找到许可证文件 | 阅读和行为对照，暂不复制源码 |
| [echium-server](https://github.com/yuvlian/echium-server) | Python 小型服务、Freesr 战斗配置、命途和 Lua 辅助工具 | 4.5.52；handler.py 有大量 dummy 响应，mission.py 直接标记完成 | 配装格式与工具参考；子模块 kcpshimmy 已下载 |
| [LunarCore](https://github.com/Melledy/LunarCore) | 直接可用的 Java 领域模型、网络、存储、场景、角色、物品、插件 | 默认 lite 为 4.2.0；宇宙为空响应，任务和日常为预设；README 的宇宙说明不适用于 lite 全部源码 | 主工程代码基础，保留 AGPL 和归属 |
| [HyacineDH-Core](https://github.com/Mar7thLover/HyacineDH-Core) | C# 4.0 的任务、挑战、成就和场景组织 | README 明确任务勉强可用，完整性需逐项核对 | 作为 March7thHoney 的前序实现对照 |
| [hkrpg-go](https://github.com/gucooing/hkrpg-go) | 网关、节点发现、消息队列、会话分片、资源与存储组织 | dev 快照是 3.0.0，不能将其协议直接用于 4.x；上游建议不要生产使用 | 将来拆分 Java 服务时的系统架构对照 |
| [himeko-nova-sr](https://git.xeondev.com/HonkaiSlopRail/himeko-nova-sr) | 4.4.53/4.5 beta 的战斗结算和挑战协议演进 | 测试模拟器定位；无可确认许可证 | 协议差异和回归案例阅读参考 |
| [evanescia-sr](https://git.xeondev.com/HonkaiSlopRail/evanescia-sr) | 4.2.54/4.3 beta 的同系列实现 | 同系列重复代码较多，不应重复计为新功能；无可确认许可证 | 较早协议版本差异对照 |
| LunarCore development | Java 2.5.0 的 17 个 rogue 领域源码文件 | 旧版协议和领域模型；不能整包搬进 4.2.0 | 模拟宇宙移植参考 |

## 当前决定

Java 21 单进程优先，支持 Dispatch、Game、Database 三类启动模式。暂不引入多语言微服务以避免把不同版本的假设拼在同一玩家状态里。保留代码依赖真实数据库和配置表的实现，而不是用 JSON 占位接口模拟所有玩法。

采用 4.2.0 作为 Java 基线，是因为本次取得的 Java 默认分支使用这个协议；它不是八个参考项目中最高的客户端版本。若目标改为 4.6，须先核对命令号、消息字段号、资源格式和关键登录/场景/战斗数据，再逐项移植玩法。

## 能力判定规则

1. 处理器存在：只能证明代码识别这个消息。
2. 有服务与玩家数据模型：可作为已集成源码，仍需要服务端测试。
3. 完成读取、条件、状态、结算、奖励、持久化和通知闭环：可列为玩法实现。
4. 匹配客户端上通过正常流程、失败路径、重连和重启验收：才可列为已验收可玩。

在任何一个项目中，固定完成任务、固定500活跃点、空回复、预设战斗结果和只显示页面，都不能视为相应玩法完整实现。
