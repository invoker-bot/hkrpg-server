# 4.6 配音下拉菜单被审核模式锁定

2026-10-19，补齐中文章节包后，用户仍看到“简体中文 / 英语”，且配音下拉菜单无法点击。本轮定位到独立于资源完整性的网关错误：`Gateway46` 将 `ios_exam` 与普通功能开关一起设为 `true`。

## 客户端实证

仅在用户已打开的客户端中只读检查，没有加载调试 DLL、调用游戏函数或修改进程内存。固定版本摘要与现有分析器一致：

- `GameAssembly.dll`：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`。
- `global-metadata.dat`：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- Lua 索引中的语言设置控件包含 `CanSwitchAudio` 调用；原生 `AudioManager.CanSwitchAudio` 位于 RVA `0xc6bb2c0`，审核标志为真时直接返回不可切换。
- `ServerDispatchData._ParseServerDispatchData` 在 RVA `0xe0a779b..0xe0a77a2` 将网关对象的布尔字段复制到 `ServerDispatchData.IOSExam`（实例偏移 `0x132`）。现用协议中 `GateServer.ios_exam` 为字段 **99**。
- 进程 `66244` 及用户重启后的 `45416` 的只读结果均为 `IOSExam=true`、音频目录含 4 种可配置语言、版本 `V4.6Live`、该判断未被热更新替换。完整下载集合同时含 `cn`、`en`，因此这次不可点击不能再归因于缺中文包。两份记录为 `live-before-client.json`、`restart-before-deploy.json`。
- 同一正式 Compose 的 HTTP 网关也返回审核模式，新增部署检查在原服务上准确失败。

## 修复

正常 4.6 网关保留 `ios_exam=false` 的协议默认值。未修改生成协议类、客户端二进制、其他网关开关或用户存档。Compose 验证器同时检查 CN、OS 两种网关，拒绝将“端口正确、健康检查通过但启用了审核模式”的服务判为通过。

Java 网关回归与部署验证器回归均先在旧行为上失败。验证记录位于 `build/client-audio-selection-20261019/`：

- `gateway-red.log` / `gateway-red.xml`：新增 Java 用例在原行为上失败；`gateway-green.xml` 中两项网关测试通过。
- `compose-gate-red.log`：旧验证器没有拒绝审核模式，新增用例失败；修复后 `compose-gate-green.log` 的 21 项验证器测试通过。
- `build-green.log` / `regression-summary.json`：`scripts/build.ps1` 完整执行，549 个测试类、14,546 项测试通过，失败、错误、跳过均为零；构建耗时 23 分 57 秒。
- `smoke-server.log`：打包产物的两轮独立 HTTP 冒烟通过，重启后恢复同一账号。该检查不代表游戏画面或配音验收。
- 已验证 JAR 的 SHA256 为 `66af84c49432ce98da256dee2c00c02511a54b6012d785dbf48e0f5fa35ff002`。旧 JAR 与部署清单保存在本轮证据目录，更新只替换 Compose 产物并同步摘要，不覆盖用户配置或数据库。
- `compose-up.log` / `live-after.json`：正式 Compose 服务已重建且健康，容器内外 JAR 摘要一致。CNPROD 和 OSPROD 实际网关均返回 `iosReviewMode=false`，原有 SDK 环境、回环地址和 UDP 端口保持正确，UDP/KCP 心跳通过。容器仍采用 `unless-stopped`，本轮没有再次执行会创建账号并停启服务的生命周期验收。
- 部署镜像为 `sha256:5bdc1eddc013325061ef93a62d1ba9f17949bef2628021b4eefcbb6dcac85a3c`，容器为 `e3daffb11efc`；LunaGC 容器未参与本轮操作。

## 配音选择与验收边界

前次补包修复已让客户端识别全部中文语音，但修改 `AudioLangRedord.txt` 并不能证明当前配音已选为中文。该文件是音频更新语言记录；本轮观察到它在启动后回到 `English`。注册表保存值为 `cn`，当前进程的配音偏好却为空、实际选择仍为 `en`，这一读取差异尚未作为已修复结论。

本轮针对下拉菜单的确定性禁用条件。需由用户重新获取网关配置，确认菜单可以操作并选择中文，再确认实际对白声音。文字中文已由用户确认；实际中文配音、持续转圈、稳定进图及 3011014 临时 NPC 和完整演出仍不以服务器测试代替客户端验收。

## 2026-10-19 客户端验收反馈

修复提交 `6161751` 部署后，用户从原桌面入口重新启动，并明确确认“能选择，中文对白正常”。配音菜单恢复操作和中文对白播放已通过本次真实客户端验收；文字中文此前也已确认。

用户随后确认“仍持续转圈，无法操作”。加载遮罩、可操作地图及 3011014 临时 NPC 和完整演出继续待修复与验收，不能由本次语言验收推导为通过。
