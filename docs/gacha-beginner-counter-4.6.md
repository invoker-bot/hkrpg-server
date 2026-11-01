# 始发跃迁次数与耗尽隐藏修复

用户实机抽卡后仍显示「剩余次数：50/50」。服务端已独立保存新手累计次数，但 4.6 查询和抽卡回复把次数写入了普通卡池字段；此前测试也沿用了错误字段，不能证明游戏页面更新正确。另一个独立问题是查询列表没有排除已耗尽的新手池。

## 字段来源

通过项目现有 `scripts/analyze-client-46.py` 只读核对本机固定客户端，未启动或修改客户端。内置反汇编工具连接中断后，使用该脚本的 PE/元数据与 Capstone 静态读取完成交叉核对。文件 SHA-256：

- `GameAssembly.dll`：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`
- `global-metadata.dat`：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`

| 消息 | 线字段 | 客户端读取链 |
|---|---|---|
| `GachaInfo` | 8 | `LKLKNFHKCBK.WriteTo`（181881，RVA `1e9656b0`）将实例 `+0x54` 写为标签 `0x40`；`BaseGachaPoolData.SyncGachaPoolData`（593156，`c7e0fb0`）的新手分支将其存入 `NewbieGachaPoolData.DoGachaTime`，偏移 `+0x90` |
| `DoGachaScRsp` | 4 | `IAOPGJEOHOM.WriteTo`（181994，`1e5e0190`）将实例 `+0x2c` 写为标签 `0x20`；`GachaModule._CmdDoGachaScRsp`（593074，`d090b70`）的新手分支将其存入同一 `+0x90` |

客户端枚举常量独立读取为 `Normal=1`、`Newbie=2`。不能用旧服务器枚举编号判定当前客户端的分支。`NewbieGachaPoolData` 声明 `DoGachaTime=0x90`、`TotalGachaTime=0x94`，其 `IsValid`（593189，`d9c8ae0`）比较前者小于后者。原来写入的查询字段 15、回复字段 6 对应基类 `CurrentGachaTime=0x80`，不会更新新手专用次数。

两个消费者引用的类型槽 `0x93e71e0` 也解析为 `NewbieGachaPoolData`（类型 64313），未仅凭相似偏移推测类型。静态记录位于 `build/gacha-beginner-counter-20261101/` 的 `client-current.json`、`client-newbie.json` 和 `client-newbie-slot.json`。这些关系仅针对上述文件摘要，不能据此声明其他客户端版本兼容。

## 行为

- 查询字段 8 与成功抽卡回复字段 4 写入已保存的新手累计抽数，客户端据此从 50 次逐次扣减。
- 达到 50 次后，旧协议和 4.6 查询均按玩家状态隐藏全部新手类型卡池；其他账号和运营配置保持独立。客户端收到第 50 次的正确专用计数后，也能按自身有效性判断关闭卡池。
- 隐藏后直接重放抽卡请求仍返回 `GACHA_NEWBIE_CLOSE`（3006），不再扣票或增加奖励；提前获得五星仍可抽完 50 次。
- 沿用每十抽消耗 8 张通票、独立保底、累计次数保存和正常重启恢复。不会重置玩家已有抽数或根据旧常驻保底猜测补写历史。

## 验证边界

`GachaInitial46Test` 修正了两个错误字段断言，增加 40/50/超限、提前出五星、旧/4.6 列表、历史重载、多个新手配置及其他玩家隔离检查。修复前 16 项中 5 项真实断言失败，日志及 XML 保留在本次构建目录；首次因共享 Gradle 输出锁而失败的记录另存，不能算作问题复现。

`scripts/smoke-gacha-beginner.py --gacha-only --jar <已验证JAR> --output <报告>` 使用临时端口、独立账号和独立 H2，通过真实 4.6 KCP 完成五次十连，按真实字段号解析查询，核对剩余次数序列 `50→40→30→20→10→0`、40 张通票、耗尽拒绝，以及两次正常重启后的计数和隐藏状态。抽卡回复同时检查新手专用字段；测试不消费运营账号资源。

服务端回归、静态客户端字段核对与隔离网络验收不能代替真实游戏页面验收；实际窗口中的剩余次数刷新、第五次十连后的界面切换仍需用户重进跃迁页面确认。当前功能状态保持原有部分实现边界。

## 2026-11-01 实际验证记录

- 专项 Java：7 个套件、56 项，零失败、错误或跳过；16 项协议检查中修复前的 5 项真实断言失败保留为独立记录。
- 隔离 KCP/H2：五次十连、40 张通票、剩余序列 `50→40→30→20→10→0`，耗尽后两次请求拒绝、三次正常停服、两次同 UID 重启，存档仍为 50 次且卡池隐藏。该测试号未执行角色试用。
- 独立 Dispatch 冒烟：两次进程启动及账号恢复通过。
- 本次独立导出的早期基线执行 `gradlew test shadowJar`，642 个套件、15,849 项全部通过；日志和 XML 保留在首个证据目录。
- 并行发布完成后，当前运行包已包含本次四个抽卡类；与本次冻结源码的编译结果逐字节核对一致。最终 JAR SHA-256 为 `a36a069a81fa4e98b394dd48879a8ea2d104e842a9a871a52412a452cf34ccc2`。直接取出此运行包，重新完成相同的 50 抽、40 张通票、两次重启、耗尽隐藏与超限拒绝验收，报告为 `running-native.json`。
- 核对最终同一 JAR 的完整门禁：644 个套件、16,003 项，失败、错误和跳过均为 0，其中包含本次 16 项抽卡协议回归。原始完整日志为 `build/rogue-selection-20261101/full-build.log`，XML 位于该目录的 `validation-build/test-results/test/`，汇总和产物摘要另存本次 `running-full-counts.json`。
- `scripts/verify-compose.py` 对当前运行实例的只读检查通过，运行 JAR、宿主产物和清单一致。运营账号 UID 10001 仍为 `newbieDrawCount=20`，正确显示应为 `30/50`；与排查开始时比较，抽卡存档、三类货币、全部物品和角色文档摘要保持一致。

静态证据和失败基线位于 `build/gacha-beginner-counter-20261101/`，最终运行包的验收记录位于 `build/gacha-beginner-counter-current-20261101/`。后者包含 `running-counter-classes.json`、`running-native.json`、`running-full-counts.json`、`operator-final.json`、`operator-preservation.json` 和 `compose-final.json`。

发现运行包已包含修复后，正在执行的另一份中间候选检查被停止，原因单独记录在 `superseded-build.json`；该中止记录不算通过。排查慢用例时的旧包 90 秒限时诊断同样未计入通过数量。最终门禁以同一运行包的完整 XML 和上述终态报告为准。实际游戏页面仍待用户重登后确认。
