# 开场影片 8 的播放参数

2026-10-18 04:01 的实际客户端已经走到 `AdventurePhase._OnLoadingFinish` 和 `MissionSystem.StartLevelGraph`。日志随后记录 `ManaVideoPageContext video DecodeKey is 0. [ CS_Chap00_Act210 | 8 ]`，以及 CRIWARE 解码错误。这是与邮件查询断连分别确认的问题；客户端日志没有逐行时间，不能推定两者发生的先后因果。

既有 `GetVideoVersionKeyCsReq`（9）只有成功返回码，响应 27 没有任何影片参数。实际传输记录也显示响应体仅 2 字节。`VideoConfig` 的 8 对应 `CS_Chap00_Act210.usm`，同版本 `VideoEncryptionConfig` 明确标记该影片加密。

## 原始数据与本机字段

影片 8 的原始参数 70220919 来源为 AGPL-3.0 的 `hkrpg-go` 固定提交 `ef47e49a233133c46d70ab5a4deb4bc71ae49571`，文件 `data/VideoVersionKey.json` 的 `videoKeyInfoList`。原 Git blob SHA256 为 `7fe0bb5f9c8824bcc3d64318246b625965562522c7929257216c59d34c4e3b99`。独立核对 `HyacineDH-Core` 的 `dd54de89ca8df8355fae5727e49e9e2bbba55344` 和 `March7thHoney-OpenSource` 的 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，两份数据的同 ID 参数一致，但列表分类不同，因此没有沿用它们的列表位置。原数据文件完整对象与固定 Git 内容一致；Windows 工作区换行导致原字节 SHA 不同，具体记录保留在 `build/login-mail-loading-20261018/video-key-source-proof.json`。

只读解析仍限定既有国服 4.6 文件：`GameAssembly.dll` SHA256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据 SHA256 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。未运行参考程序，也未修改客户端、生成协议或运营资源。

- `PlayerModule._OnCmdGetVideoVersionKeyScRsp`（`dbee740`）在返回码 0 时调用 `BKLKPCOJLAB.GJAFOMJPJIO`（`ba5a100`）。
- 响应类型 `KAHKFFDIHNN.MergeFrom`（`1e7b1d40`）将字段 15 写入偏移 `+0x10`，字段 14 写入 `+0x20`。前者经 `15b57e40` 登记到普通影片字典 `+0x10`；后者经 `15b57f50` 登记到活动字典 `+0x18`。
- 子项 `LGHGDCFAHGM.MergeFrom`（`1e9266e0`）将字段 6、13、8 分别写到 `+0x30`、`+0x28`、`+0x20`，字段 14 是影片 ID。普通登记传入这三个数值时，以字段 6 为首个旧版参数。
- `ManaVideoPageContext` 的影片 ID 构造入口 `d502a50` 经 `15b58060`、`b988ad0`、`b988ca0` 查询普通字典。`b989590` 保留旧版参数分支，`b9896a0` 再结合原文件名和客户端模数生成最终解码值。因此服务端发送来源参数，不能把最终解码值或任意非零数字替代它。

详细原生证据保留于 `build/login-client-retry-20261018/native-video-*.json`。其函数读取来自同一固定文件，属于静态字段证据，不表示已播放成功。

## 04:33 实际崩溃与缺失的视频引导参数

上一轮只补影片 8 的参数，没有补齐视频管理器的模数初始化，修复不完整。04:33 的实际客户端收到 11 字节响应 27，但响应 91 仍只有 2 字节成功返回码。崩溃报告明确记录 `Integer Divide by Zero (0xc0000094)`，位置为 `GameAssembly.dll+b9898b3` 的 `div rsi`，当时 RSI 为 0。后续 `SymGetSymFromAddr64` 提示只是生成崩溃报告时的符号解析失败。该轮没有输出解码值，也没有影片播放通过证据。

同一固定客户端中，`MKECAGMHDAC` 构造函数 `b98a100` 初始化两个字典，却让 `+0x20` 模数维持 0。`PlayerModule._OnCmdGetSecretKeyInfoScRsp`（`dbee6b0`）在返回码成功时调用 `BKLKPCOJLAB.LJDHHAFHMLP`（`ba59a90`），读取类型 2 的十进制字符串，再经 `15b57d70` 与 `0x00dc0748fcc0c8da` 异或，写入这个模数。因此 31→91 不能只返回成功码。

视频引导字符串 `10120425825329403` 来自上述固定 AGPL 来源：`HyacineDH-Core` 与 `March7thHoney-OpenSource` 的 `GameServer/Server/Packet/Send/Player/PacketGetSecretKeyInfoScRsp.cs`，以及 `hkrpg-go/gameserver/player/player_base.go` 的 `SECRET_KEY_VIDEO` 项。三个工作区文件均与各自固定 Git 提交的完整规范化文本一致；Git blob 和工作区的独立 SHA256、行号及匹配结论保存在 `build/login-first-talk-20261018/video-bootstrap-source-proof.json`。这里只采用一致的视频项，不复制其他安全协商条目。

本机字段证据再次独立核对：`AHIEHELEMMN.MergeFrom`（`1e244160`）将响应返回码字段 8 写入 `+0x28`，重复列表字段 15 写入 `+0x10`；`GFDCKPPKGAC.MergeFrom`（`1e435760`）将子项类型字段 2 写入 `+0x20`、字符串字段 14 写入 `+0x10`。类型值 2 才进入视频处理分支。静态调用链与字段证据保留于 `build/login-first-talk-20261018/native-secret-*.json`、`native-video-modulus*.json`。

这条路径对开场影片的计算能够逐项对上实际崩溃寄存器：

- 视频模数：`10120425825329403 XOR 0x00dc0748fcc0c8da = 72043514036987937`。
- 文件名 `CS_Chap00_Act210` 按 UTF-16 字符逐个计算无符号 64 位 `hash = hash * 11 + character`，再加原始参数 `70220919`，得到 `0x045ecf944c2a1cb2`，与崩溃报告 RAX 完全相同。
- 使用正确模数的无符号余数为 `26743203654499374`；这是客户端计算出的解码值，不是服务端要发送的影片参数。

脱敏观测保存在 `build/login-first-talk-20261018/observed-first-talk-crash-evidence.json`，包含原日志 SHA、时间、消息编号/长度和故障指令；没有读取或复制内存转储，没有记录账号凭据或挑战正文。

## 实现与验证边界

`VideoVersionKeys46` 只返回普通列表中的影片 8；不编造活动影片或其他现代加密分量。空请求可查询，非空或截断请求返回参数错误，未登录状态不返回数据。操作不修改玩家任务、存档或奖励。

`VideoBootstrap46` 为空请求或合法的单个不透明 bytes 字段 11 返回视频引导项。请求上限仍为 16384 字节，仅验证结构，不保存、记录或回显挑战值；重复字段、截断、额外字段、超限及整数溢出别名均返回参数错误。响应不含未经核实的 `secret_rsp`、server-check 或 battle-time 项，未登录状态仍不返回数据。

上一轮影片参数的 4 项回归先出现 2 项真实失败，证据保留于 `build/login-mail-loading-20261018/video-key-red.log` 和 `video-red-xml`。本轮视频引导的初始 4 项回归先出现 3 项真实失败，其中直接使用上述真实开场分子复现除零；另两项锁定重连缺失和溢出请求别名。RED 日志及 XML 保留于 `build/login-first-talk-20261018/video-bootstrap-red.log` 和 `video-bootstrap-red-xml`。修复同时增加请求长度边界检查，并与既有初始化合同联合验证。完整构建和部署由本轮加载修复统一执行。

这项补齐仅覆盖日志中明确失败的开场影片 8。没有核实全部影片密钥，也没有宣称本机实际解码或 3011014 完整演出通过；仍需用户实际播放验证。
