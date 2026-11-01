# 角色试用领奖状态通知（4.6）

## 现场与修复

2026-11-01 用户确认景元试用已经可以正常结算，但无法正常领奖。经正式服务内部 Mongo 端口只读查询，UID 10001 的 312040 已有真实通关记录、冻结奖励 3011204，`received` 为空，`trial:312040` 没有领奖收据，运行已返回。星琼 520965，背包只有 72 份物品文档，相关堆叠均未满。此前 311020 的已领奖记录仍在。本次没有观察到明确的领奖拒绝返回码，不能把按钮未更新写成一次已捕获的拒绝请求。

原实现胜利后只发送 `CurTrialActivityScNotify(FINISH)`，清理当前试用实例，却没有发送更新客户端已通关缓存的 `TrialActivityDataChangeScNotify`。现由真实通关保存成功后、结束通知之前补发状态变更；成功领奖并保存后、领奖响应之前补发已领取状态。严格沿用已有通关、冻结数量、容量检查及一次领取账本，不伪造通关或直接补发运营账号奖励。

通知仅发送到当前 ACTIVE 的 4.6 会话，且必须存在该关卡真实通关记录。普通退出、未通关领奖、容量不足、重复领取及保存异常均不发布虚假的成功变更。已保存的未领奖通关可在重登查询后直接领取，不必再次试用。

## 固定客户端来源

- `GameAssembly.dll` SHA256：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`。
- `global-metadata.dat` SHA256：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。
- `_OnTrialActivityDataChangeScNotify` RVA `0xe060ad0` 读取通知内的通关信息，调用 `_AddPassedStage`（`0xe0607c0`）并刷新界面；首次查询同样能重建历史，结束当前实例的通知不能替代此更新。
- 消费函数的类型槽 `0x93dbba0` 对应 `ELOPGADIBBE`（typeIndex 25966），其 `WriteTo` RVA `0x1e2ac5e0` 将 native `+0x20` 的信息编码为 tag `0x32`，即消息字段 6。现有命令 2676 的字段 6 使用 `TrialActivityInfo`，关卡字段 14、领奖布尔字段 1；额外角色元数据列表保持空值。

证据在 `build/client46-trial-reward-data-consumers.json`、`build/trial-reward-change-type.json` 与 `build/trial-reward-change-codec.json`。仅静态读取已安装客户端，未执行引用仓库程序、修改客户端或手工改动生成协议类。

## 回归与验收边界

先增加 6 项回归，修复前 3 项真实行为失败，分别锁定胜利后的可领奖通知、成功领奖后的已领取通知，以及拒绝后合法重试的通知；日志和 XML 为 `build/trial-reward-notify-red.log`、`build/trial-reward-notify-red.xml`。另增加旧未领奖通关存档直接查询及领取的第 7 项回归。正向通知同时核对发送瞬间已有保存的通关/成功收据，负向覆盖保存失败、普通退出、未通关、旧协议和未登录会话。

原隔离 `scripts/smoke-trial-settlement.py` 追加真实命令 2676 的两次状态检查，删除胜利后主动刷新历史的请求，验证可领奖状态由推送获得；领奖请求带 packet ID，通知不得继承该 ID，响应仍准确回传。原始景元 NPC、神君事件报告、实际奖励增量、防重及正常 H2 重启检查保留。网络报告使用构造的胜利报告，不替代真实游戏操作；运营账号只读核对，实际领奖界面仍须用户验收。

专项五类 48 项测试通过，新增状态回归 7 项均通过。隔离网络验证实际完成 24 项显式请求检查及三次正常停服，收到一条通关未领奖、一条成功已领奖通知；未在胜利后主动重查通关列表。准确增加星琼 20、212 × 3、113001 × 5，重复结算/领取拒绝，正常 H2 重启后历史、实际钱包和物品保留。报告为 `build/trial-reward-notify-native.json`，JAR SHA256 为 `2fb27659174710e33fdc428a6becac01d5d95a0e82c612c7d34c148d0777bd3a`。

`scripts/build.ps1 -TestForks 8` 完整执行测试及打包通过：642 类 / 15,846 项，失败、错误和跳过均为 0，耗时 12 分 19 秒。汇总及日志在 `build/trial-reward-notify-full-tests.json` 和 `build/trial-reward-notify-full-build.log`。全量、网络与实际部署产物摘要一致。

## 本地部署与待验收

同一产物已部署到 `hkrpg-local-server-1`，实际运行 JAR SHA256 为 `2fb27659174710e33fdc428a6becac01d5d95a0e82c612c7d34c148d0777bd3a`。镜像构建核对全部资源文件数及逐文件摘要；用户退出后正常停服，备份整个运行目录并核对全部已复制文件，保留旧 JAR、原清单及旧镜像。备份、日志和报告在 `build/trial-reward-notify-deploy-20261101/`。

默认只读 Compose 验证、HTTP/Dispatch、网关、UDP/KCP 心跳和容器健康均通过。经正式服务内部 Mongo 端口只读核对，更新前后的全部试用通关、领奖收据、星琼、相关材料及物品文档数一致，312040 仍已通关未领取，311020 仍已领取；12 份运营配置/记录摘要保持不变。本次没有对正式账号执行通关或领奖。证据为该目录的 `compose-final.json`、`preservation.json`、`affected-stop.json` 和 `affected-after.json`。

真实游戏界面的可领取状态、领取提示及实际到账仍须用户操作验收。重新进入游戏后可直接领取已保存的 312040 奖励，无需再次试用；今后的新通关通过本次状态通知同步。
