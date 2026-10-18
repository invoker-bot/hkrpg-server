# Avatar 类剧情文本默认值

2026-10-18，本轮只修正 4.6 查询中未设置的 AvatarID 覆盖项。普通剧情文本仍按现有选项返回；4.2 处理器未改。剧情写入和持久化覆盖值尚未实现，不能把这次修复称为完整 TextJoin 功能。

## 已确认问题

04:33 的真实客户端日志对 550、560、570、580、830、840、850、860 分别报告 Avatar 配置不存在。对应 TextJoin ID 为 55、56、57、58、83、84、85、86，资源中均是 `Type=AvatarID`、`IsOverride=true`。这些 `DefaultItem` 指向 TextJoinItem 的“勇士一”至“勇士四”默认文本，不是角色编号。

旧实现忽略 Type，把所有配置的首个选项写入回包选中值。原生 AvatarID 消费分支把这个值当作角色编号，触发八条错误。现在资源模型保留 Type，4.6 只读查询省略没有已实现服务端覆盖状态的 AvatarID 行，让客户端保留自己初始化的默认文本；没有猜测队长、主角或剧情角色。

## 原生默认分支证据

本机 GameAssembly SHA256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，global-metadata SHA256 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。通过仓库 `scripts/analyze-client-46.py` 只读解析，没有运行或加载客户端 DLL。

- `TextJoinOverrideType.AvatarID=2`，`TextJoinConfigRow.Type/DefaultItem` 实例偏移为 `+0x20/+0x24`。工厂 `c296b90` 的跳转表 `c296c54` 将类型 2 指向 `c296bf6`，进入 `BBLCLJCNBIG.BPLPBPMEODA`（`15e83770`）。
- 默认工厂读取 DefaultItem，经 `1ccd1cf0` 查默认文本条目，把 TextID 写入对象 `+0x10`；未设置服务端值的标记 `+0x30` 初始为 false。读取函数 `15e83970` 在标记为 false 时从 `15e83a02` 读取该默认文本。
- 接收回包行时，`15e83aa0` 把标记置 true，并把线协议字段 15（实例 `+0x20`）送入 `15e83880`。这里明确调用 `AvatarExcelTable.GetData`（`1d3e60f0`）；不存在则打印旧日志中的错误并回退到默认 TextID。
- `_GetAvatarIDProto`（`e2099b0`）直接使用调用方提供的角色编号，没有根据主角或队长生成值。因此不能拿任意有效角色消除警告。

完整方法、字段和跳转记录在 `build/login-loading-followup-20261018/native-*.json`，汇总为 `textjoin-native-contract.json`。只保留静态代码与脱敏诊断证据，没有读取内存转储或复制凭据。

## 验证与边界

先添加两项失败回归：八个实际类型配置不应产生伪造角色覆盖；混合查询应保留普通条目的顺序与重复项。修复前 7 项专项中 2 项失败，证据是 `textjoin-avatar-red.log/xml`。修复后 TextJoin 7 项与相邻 ProtocolBridge 5 项共 12 项通过，XML 保存在 `focused-green-xml`。新增网络探针已独立编译通过。完整构建、隔离网络与部署结果见下节。

网络初始化探针在原有 18、19 查询中加入上述八项，仍要求只返回原有两个普通条目，输出 `textJoinAvatarDefaultsRetained=true`；仍为 143 个初始请求、88 个预期响应、30 个后续快照契约；这些数字描述合成探针，不是完整玩法覆盖。真实客户端是否仍报错单独等待用户重试。

初查时 Player.log 仍停在 04:33:42，与旧崩溃留证 SHA 完全相同；它早于 05:28:06 的 `259043a`。12:45 以前的 Compose 后续连接均对应自动探针。该时间线保存在 `log-timeline-evidence.json`，不能拿旧错误证明新部署失败。

随后用户在 12:55:37 通过已修复入口真实启动，进程 53976；Player.log 更新到 12:56:19，SHA256 为 `cda080a3fe86b57e65528456e694ebbcb7338ca7c70af3fe95f0a952687b39a9`。这次仍使用未部署本次 TextJoin 修改的旧服务：12:56:15 记录八条 Avatar 默认项错误，12:56:16 记录 TerrainStageLodConfig 提示。服务于 12:56:18 记录断开，客户端随后明确执行 `OnApplicationQuit` / `Application will quit`，卸载资源并输出分配器统计。没有新的 `Crash!!!`、除零、商店 988、FirstTalk 回包拒绝或 Java ERROR；崩溃目录中的 error.log 仍是原来 04:33 的记录。退出是谁触发、弹框原文是什么，日志没有证据，需要用户反馈；不能把退出时的 AssetBundle 卸载警告或分配器 Failed Allocations 计数判为崩溃原因。

这次没有 `_OnLoadingFinish` 日志；旧 04:33 日志曾在上述文本和地形提示后记录该回调。两次均不足以证明这些提示造成转圈，也不能宣称它们无害。新证据为 `real-retry-125537-evidence.json`，崩溃盘点/比较为同目录 `crash-inventory-20261018-1256-*.json`、`crash-comparison-20261018-1256-*.json`。随后只读核对 TerrainStageLodConfig：当前完整设计索引的 141,295 项中确实不存在目标键 `a3f4e4a26857ba33`。原生 `LoadTerrainStageLodConfig`（`e119290`）在缺失分支记录提示并于 `e119387` 返回 null；直接调用方 `b6f675d` 转到 `b6f6789`，分配实际类型为 TerrainStageLodConfig 的替代对象，初始化 LodMap 后继续场景初始化。服务端默认 plane/floor/entry `20001/20001001/2000101` 与 Chapter00 资源相互一致，存档场景优先恢复，未发现选错地图的源码或配置证据；本轮没有读取用户存档。

这些证据只证明该缺失分支有继续执行的回退，不能据此宣称全部地形 LOD 效果不受影响。没有补造资源、改地图或修改客户端来压制提示；目前未确认它是独立阻断点。完整原生函数边界、代码字节 SHA256、索引与地图对应关系保存在 `terrain-lod-static-triage.json`，边界直接取自 PE 的 `.pdata`。稳定进图、影片播放与 3011014 完整演出仍需真实客户端验收。

## 最终构建与 Compose 更新

`build/login-textjoin-final-20261018/` 保存本次统一验收记录。`scripts/build.ps1 -TestForks 6` 实际执行 545 类、14,473 项测试，失败、错误、跳过均为 0；2,819 个构建输入摘要在构建前后保持一致。最终 JAR SHA256 为 `2ea727bf3f72da641c6055245e38036fe857c6a89ab363a26b7d0e099fff6f2e`，结果见 `full-build-proof.json` 与 `full-xml/`。

隔离网络两轮初始化为 2,185 / 2,242 毫秒，均保留八项 Avatar 默认文本，同一账号更换密码并在 H2 重启后仍恢复同一玩家；Dispatch 两轮检查通过。这些是合成网络与数据库验证，未启动游戏客户端。

13:12 更新 `hkrpg-local` Compose 服务。旧容器正常退出（143，无 OOM）后备份 10 个数据文件，保留旧镜像回退标签；继续使用原运营数据目录，没有用探针数据库替换。配置、热修复与资源清单均未修改，LunaGC 容器未操作。新镜像 `sha256:5d9e03f716ca842abd15d0c6d6ccbd553855b1d9ed634b949e302baa1ba01f4e` 中的实际 JAR 与上述最终摘要一致，重启策略仍为 `unless-stopped`。备份及更新证据见 `before-upgrade/` 和 `deployment.json`。

更新后的 Compose 检查验证了 CN/OS Dispatch、UDP/KCP 心跳、实际容器 JAR 与映射端口；使用一个独立探针账号更换密码连续初始化，两轮为 2,173 / 1,721 毫秒，均为同一账号、同一玩家且 `textJoinAvatarDefaultsRetained=true`。结果见 `compose-readonly.json` 与 `compose-login.json`。已请用户从原桌面入口重试并保留游戏现场；稳定进图、转圈是否解除以及 3011014 实际完整演出尚未通过。

另对真实日志末尾的 6160、1439、7434 做了只读协议核对：分别为偏好写入、位置版本通知和旧三消初始化。旧三消自身等待收到 7406 后解除，但未找到主地图加载依赖它的确定证据。当前偏好写入也缺少持久化支持，不能仅补成功回复来宣称功能完成；本轮未改这三条消息。完整边界见 `build/login-loading-followup-20261018/unhandled-6160-1439-7434-contract-07bded2b.json`。
