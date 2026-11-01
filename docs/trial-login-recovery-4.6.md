# 角色试用重登停在敌人画面（4.6）

## 现象与原因

2026-11-01，UID 10001 重登后停在试用场景，只显示敌人，没有角色和操作界面。当前客户端 `Player.log` 持续报告 `RoleTrialUtils.bytes:16: attempt to index nil with 'TrialAvatarList'`，调用来自 `RoleTrialDetailPage.init`；只读等待快照没有未完成的网络等待。

服务端已经恢复 `TrialRun`、试用角色和场景，但登录没有同步客户端的当前试用实例。固定客户端的 `RoleTrialModule._OnGetTrialActivityDataScRsp` 只更新通关、领奖及试用历史；查询回包中的 `activity_stage_id` 不会创建 `RoleTrialInstance`。该实例缺失时 `get_StageID` 返回 0，详情页据此无法查询试用配置。这条静态调用链与本次异常相符；没有把历史结算拒绝直接当作本次加载卡住的原因。

## 修复范围

4.6 登录成功后，为已经恢复为 `ACTIVE` 的实际 `TrialInstance` 补发 `CurTrialActivityScNotify`（命令 2646）：字段 1 是本局关卡，字段 10 是 `NONE=0`。固定客户端在这个状态下创建当前试用实例，供场景脚本和详情页读取。

通知必须绑定已恢复的同一份 `TrialRun`；仅有未恢复的保存意图、返回中的试用、已完成的试用及普通登录不发送开始通知。同步只读现有状态，不生成胜利、不发奖励、不重新创建队伍或修改生命、能量、进度。4.2 登录保持既有路径。

## 固定客户端来源

- `GameAssembly.dll` SHA256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`。
- `global-metadata.dat` SHA256：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- `_OnTrialActivityNotify` RVA `0xe05ed10`：状态 0 且实例不存在时，使用关卡构造 `RoleTrialInstance`；状态 1 清理实例。`get_StageID` RVA `0xe0604e0` 在实例不存在时返回 0。
- `CurTrialActivityScNotify.WriteTo` RVA `0x1df2aa40`：关卡编码至字段 1，状态编码至字段 10；沿用已核对的命令和字段，不修改生成的协议类。
- 当前安装的 Lua 索引完整解析并核对记录 MD5，提取 `RoleTrialUtils.get_avatar_ids` 与 `RoleTrialDetailPage.init`；前者用传入关卡查询 `AvatarDemoConfigExcelTable`，随后读取 `TrialAvatarList`。

只读原生证据保存在 `build/trial-ui-recovery-client-native.json`、`build/client46-role-trial-commands.json`、`build/client46-role-trial-encoders.json`、`build/client46-trial-reward-data-consumers.json`；Lua 记录和解析结果在 `build/trial-ui-recovery-lua/`。这些来源只用于字段和调用链核对，不进入 Java 运行依赖，也未修改客户端。

## 验证边界

先新增真实恢复后执行登录处理器的回归，旧实现 11 项中 2 项失败，均为缺少当前试用通知；失败日志及 XML 保留在 `build/trial-ui-recovery-red.log` 和 `build/trial-ui-recovery-red.xml`。回归同时核对原队伍/生命保留、不制造通关、未恢复意图不公开以及已返回/已胜利不重新进入。

`python scripts/smoke-trial-login-recovery.py` 在独立账号、端口和 H2 数据库验证首次进入、同进程重连、正常服务重启续局及手动退出。两次恢复均收到真实命令 2646 的当前关卡通知；原试用场景、三名演员、编队及生命/能量字节保持一致，退出精确恢复原场景和普通编队，未发送完成通知。三个正常停服均核对退出码。测试前置仅在该隔离数据库设置任务 1000510 完成并移除开场剧情试用队伍，运营资源和玩家均不修改；面板 20003 / 模块 2000401 是隔离窗口。报告在 `build/trial-ui-recovery-native.json`。

`scripts/smoke-server.py` 两次独立 Dispatch 启动及账号重启恢复通过。旧 `scripts/smoke-4.6.py` 在新号首次普通试用入口报 `Native trial start failed or lost its stage identity`，尚未执行到本次重登检查；该探针没有准备当前的新号剧情试用及任务前置，未将其记为通过，日志保留在 `build/trial-ui-recovery-native.log`。新专项使用已有带目录/标记/单账号限制的测试数据库检查器准备明确前置，不放宽生产准入。专项脚本首轮另有等待 KCP 客户端关闭后在线人数归零的超时；删除该无关等待后，按实际重新认证与持久化快照验证恢复，首轮日志保留在 `build/trial-ui-recovery-focused-native-first.log`。

`scripts/build.ps1` 实际执行全量测试和 JAR 打包通过：640 类 / 15,831 项，失败、错误及跳过均为 0；耗时 13 分 1 秒。汇总在 `build/trial-ui-recovery-full-tests.json`，构建日志在 `build/trial-ui-recovery-full-build.log`。试用恢复类 11 项全部通过，含两条修复前真实失败的登录回归。

## 本地部署与待验收

同一通过全量测试和隔离 KCP 的 JAR 已更新 `hkrpg-local-server-1`，运行摘要为 `2d7b49c47a7c4cfe0cb14370c32d1f875d464df6dff818c8663ca2da09ae3806`。镜像构建对资源文件数与逐文件摘要校验通过；正常停服后备份了整个运行目录，数据库副本摘要一致，旧 JAR 和原部署清单均保留在 `build/trial-ui-recovery-deploy-20261101/`。

上线后容器健康及默认只读 Compose 验证通过，HTTP/Dispatch、网关、UDP/KCP 心跳和实际运行 JAR 均已核对。初次验证因部署清单仍指向旧 JAR 摘要而失败；备份清单并更新到已验证的实际产物后，最终报告为 `build/trial-ui-recovery-deploy-20261101/compose-final.json`，没有放宽验收器。

通过正式服务已打开的 Mongo 端口做只读前后比对：UID 10001 仍为关卡 312040、`ACTIVE`、试用成员 2000091/2000092；队长、秘技点、生命能量、当前场景位置、原返回位置和已有试用通关记录完全一致。12 份运营配置/记录摘要保持不变。该检查没有重新认证运营账号，也没有执行战斗、退出或领奖；结果在 `build/trial-ui-recovery-deploy-20261101/preservation.json`。

真实客户端须由用户从原桌面入口重新启动，确认试用详情页、角色及操作界面能够继续显示。此前的试用结算拒绝仍按 [结算诊断](trial-battle-diagnostics-4.6.md) 单独追踪，本次不放宽结算约束；本轮不能代替完整试用战斗与奖励验收。
