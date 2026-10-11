# 4.6 哈努爆炸上报与原弹药账本

本批补入实际 `6502` 请求的合法目标列表，使用既有哈努弹药账本保存击败、掉落和原始 NPC 身份。爆炸不消费弹药；普通 `6514` 射击仍消费一发。完整小游戏、移动 AI、爆炸碰撞和游戏客户端界面继续待验收。

## 协议与来源

当前 4.6 描述符中，`LBMFCJLHNAA` 只有 repeated uint32 字段 12；它没有可依据名称自动登记的 CsReq 命令。处理器明确登记 6502，回包是 `WolfBroGameExplodeMonsterScRsp` / 6518，retcode 字段 15。合法 packed、unpacked 和混合实体列表均可解析；解析前检查完整原始 uint64 varint，防止溢出值别名当前实体。

只读核对本机 `GameAssembly.dll` SHA256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`：完整 PE 函数表明，爆炸任务从实际 NPC 与爆炸道具组件检测目标，过滤已死亡对象，再调用发送 6502 的辅助函数。发送列表中只有 NPC 实体编号；包内没有道具编号、爆炸原点、半径或碰撞结果。只读反汇编没有运行参考仓库中的二进制或启动官方游戏。

固定数据提交为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。新夹具 `src/test/resources/fixtures/wolfbro-explosion-4.6.json` 保存十一份完整配置对象及完整 MazeProp 103055 行，包括四份原 NPC Ability、四份 Character、爆炸道具 Config / Entity 与完整 level graph。`scripts/freeze-wolfbro-explosion-sources.py` 逐份核对固定 Git 原始 blob，另外保存包含完整 MazeProp 表的十二份未经转换原文件；本次核对共 1,179,864 字节。数字词法由 `source_json.py` 保留，源数据没有被裁成任务片段。

运行时只依赖已安装的四份原 NPC Ability、既有原关卡和完整 MazeProp 行，不自动安装另外七份参考配置。源数据只用于核对；没有引入参考运行时代码，Java21、LunarCore 来源和 AGPL 约定保持。

## 行为与持久化

请求必须属于当前玩家、当前哈努 Run、原 Level11 / Floor / Group 以及实际小型形态。枪械需要已激活；激活后即使弹药为零，合法爆炸仍可上报。空目标列表在合法上下文中只读返回成功。

附加来源门槛核对四份完整原 Ability、完整原道具行及实际 loaded PropExcel，并要求当前场景至少有一个原始摆放的 103055 爆炸道具实例。原件缺失、损坏或实际实例不符时拒绝。所有受害目标必须是当前场景中与原 NPC 身份、事件和原位置一致的存活实体；先验证全部目标，避免部分成功。

成功请求把所有目标的击败凭据和原坐标掉落一起保存到同一 Player 文档，保存成功后才移除实际实体和发送变化通知。爆炸与普通射击使用同一击败账本；重复请求、旧实体编号或重载后的已击败 NPC 都不能再生成掉落。保存失败恢复旧 Run，保留实体供原请求重试。既有弹药来源摘要和存档字段保持兼容，存档恢复不复活已提交目标。

这是客户端上报目标的服务器处理。6502 没有足够字段让服务端重新计算道具碰撞、射线或爆炸半径。原始出生位置校验也尚未扩展到完整移动 AI；本批不把来源门槛写成物理验收。

## 验证记录

新 Java 类 `WolfBroExplosion46Test` 使用完整原件和既有实际场景工具，包含 27 项回归。修正观测顺序后的相同测试对冻结 F2 产物 `dd9db2e3d2e2976c1b4034ac7253b635c53e555fed9d8017b56aa16ced308a09` 实际产生 24 个纯断言失败、三个对照通过，无 errors / skips / aborts；生产类 CodeSource 来自 F2，未使用当前 main 输出。只覆盖三份新生产源码的独立 javac / JUnit 运行随后 27 项全部通过。报告分别保存在 `build/wolfbro-explosion-draft/formal-f2-red-observation-corrected` 与 `formal-owned-green-observation-corrected`；独立运行不等于项目完整构建。

测试覆盖零弹药、混合编码、重复及溢出目标、错误形态 / 枪械 / 来源、完整资源变更、保存失败回滚、共享普通射击账本、归档恢复及 UID 隔离。文档复制恢复使用 Gson；实际 Morphia / H2 恢复另由原生工具验证。

`scripts/smoke-wolfbro-ammo.py --explosion` 为旧弹药原生工具增加明确可选场景，默认弹药场景保留。新场景实际验证未激活拒绝、合法空请求、真实原 NPC 爆炸、弹药不变、重复拒绝、普通射击继续扣弹、原掉落拾取及三次正常停止后的同 UID H2 检查。运行目录为新建 `build/smoke/wolfbro-ammo-native-c286hoxr`，完整来源物理复制，运营配置与资源不覆盖。

三个实际 Java 辅助工具已编译并通过冻结描述符预检；这没有启动服务器。五项新增 Python 守卫先复现三个纯断言失败，修复为完整检查成功后一次更新准备状态，并严格匹配四份原文件名。与旧十三项工具守卫合计十八项通过。首轮临时文件 CRLF 设置错误只计工具夹具错误，旧日志保留。

正式统一 Gradle 专项共 433 项全部通过，含本批全部 27 项及既有哈努 124 项，2,535 项输入前后稳定。报告为 `build/manual-sibling-save-blast-root-focused-verification.json`。

共同冻结新产物 SHA256 为 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be`。新爆炸原生报告 `build/wolfbro-explosion-current-native.json` 实际通过 122 次请求、167 项检查、三次正常停服及两次同 UID H2 进程恢复，三个停服后的数据库均只读打开并双读实际 Player 文档。报告 SHA256 为 `e7658536b27f619af9ad09984a26e8f647f0b156033d4fd551eb7cbaea3145b6`。真实目标实体 212 的爆炸返回成功，弹药 4 → 4、掉落 0 → 1，重复爆炸拒绝；随后普通空射弹药 4 → 3，拾取恢复为 4，两次重启后原 NPC 仍缺席且已消耗掉落不能再次领取。

统一全量502类 / 13,715项通过，2,535项输入前后稳定，记录见[本批验证](manual-sibling-save-blast-batch-4.6.md)。实际原生结果是隔离自有探针对4.6报文的验证，不等于官方游戏客户端、物理碰撞、所有关卡或断电原子性验收。
