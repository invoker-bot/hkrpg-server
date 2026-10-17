# 开场影片 8 的播放参数

2026-10-18 04:01 的实际客户端已经走到 `AdventurePhase._OnLoadingFinish` 和 `MissionSystem.StartLevelGraph`。日志随后记录 `ManaVideoPageContext video DecodeKey is 0. [ CS_Chap00_Act210 | 8 ]`，以及 CRIWARE 解码错误。这是与邮件查询断连分别确认的问题；客户端日志没有逐行时间，不能推定两者发生的先后因果。

既有 `GetVideoVersionKeyCsReq`（9）只有成功返回码，响应 27 没有任何影片参数。实际传输记录也显示响应体仅 2 字节。`VideoConfig` 的 8 对应 `CS_Chap00_Act210.usm`，同版本 `VideoEncryptionConfig` 明确标记该影片加密。

## 原始数据与本机字段

本次仅补入影片 8 的原始参数 70220919，来源为 AGPL-3.0 的 `hkrpg-go` 固定提交 `ef47e49a233133c46d70ab5a4deb4bc71ae49571`，文件 `data/VideoVersionKey.json` 的 `videoKeyInfoList`。原 Git blob SHA256 为 `7fe0bb5f9c8824bcc3d64318246b625965562522c7929257216c59d34c4e3b99`。独立核对 `HyacineDH-Core` 的 `dd54de89ca8df8355fae5727e49e9e2bbba55344` 和 `March7thHoney-OpenSource` 的 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，两份数据的同 ID 参数一致，但列表分类不同，因此没有沿用它们的列表位置。原数据文件完整对象与固定 Git 内容一致；Windows 工作区换行导致原字节 SHA 不同，具体记录保留在 `build/login-mail-loading-20261018/video-key-source-proof.json`。

只读解析仍限定既有国服 4.6 文件：`GameAssembly.dll` SHA256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据 SHA256 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。未运行参考程序，也未修改客户端、生成协议或运营资源。

- `PlayerModule._OnCmdGetVideoVersionKeyScRsp`（`dbee740`）在返回码 0 时调用 `BKLKPCOJLAB.GJAFOMJPJIO`（`ba5a100`）。
- 响应类型 `KAHKFFDIHNN.MergeFrom`（`1e7b1d40`）将字段 15 写入偏移 `+0x10`，字段 14 写入 `+0x20`。前者经 `15b57e40` 登记到普通影片字典 `+0x10`；后者经 `15b57f50` 登记到活动字典 `+0x18`。
- 子项 `LGHGDCFAHGM.MergeFrom`（`1e9266e0`）将字段 6、13、8 分别写到 `+0x30`、`+0x28`、`+0x20`，字段 14 是影片 ID。普通登记传入这三个数值时，以字段 6 为首个旧版参数。
- `ManaVideoPageContext` 的影片 ID 构造入口 `d502a50` 经 `15b58060`、`b988ad0`、`b988ca0` 查询普通字典。`b989590` 保留旧版参数分支，`b9896a0` 再结合原文件名和客户端模数生成最终解码值。因此服务端发送来源参数，不能把最终解码值或任意非零数字替代它。

详细原生证据保留于 `build/login-client-retry-20261018/native-video-*.json`。其函数读取来自同一固定文件，属于静态字段证据，不表示已播放成功。

## 实现与验证边界

`VideoVersionKeys46` 只返回普通列表中的影片 8；不编造活动影片或其他现代加密分量。空请求可查询，非空或截断请求返回参数错误，未登录状态不返回数据。操作不修改玩家任务、存档或奖励。

新增 4 项回归先在旧实现出现 2 项真实失败，锁定没有影片参数以及重连仍缺失的问题；RED 日志及 XML 保留在 `build/login-mail-loading-20261018/video-key-red.log` 和 `video-red-xml`。修复后与既有初始化合同联合验证。完整构建和部署由本轮邮件与加载修复统一执行。

这项补齐仅覆盖日志中明确失败的开场影片 8。没有核实全部影片密钥，也没有宣称本机实际解码或 3011014 完整演出通过；仍需用户实际播放验证。
