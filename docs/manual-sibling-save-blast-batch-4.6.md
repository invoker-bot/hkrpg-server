# 4.6 输入动作、短信日常、棋盘保存与哈努爆炸

本批在既有 Java21 / LunarCore 核心上补齐四条有限玩法链路。内部消息基线仍为 4.2.0，4.6 线协议仍为实验适配；全部剧情、宇宙、活动及官方游戏客户端验收没有完成。

## 实现范围

| 范围 | 本批行为 | 详细记录 |
| --- | --- | --- |
| 1510 探索输入动作 | 原独立 Input UseID 与 base snapshot 绑定，成功 HIT / END 按原任务扣费，失败入战可重试，瞬时身份不跨会话或落盘 | [输入动作](maze-manual-ability-4.6.md) |
| 日常 3000603 | 原 Talk、1211402 的17个完整节点与八条路径、按日三次回复 / 提交、5000信用及200活跃分别防重 | [短信日常](daily-message-3000603-4.6.md) |
| 棋盘宇宙 | Query 永久资料、5462 保存返回普通场景 / 队伍、暂停查询和5437恢复，保留当前局金币 / 战斗收据 / 奇物 / HP-SP / stock | [棋盘入口与保存](chess-rogue-merchant-entry-4.6.md) |
| 哈努爆炸 | 原6502受害实体列表、6518响应，与普通射击共享击败 / 原掉落账本，爆炸不扣弹、保存成功后移除实体 | [哈努爆炸](wolfbro-explosion-4.6.md) |

参考代码和数据继续保持固定来源；原始数据提交为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`。额外运行时代码没有从未知许可证的项目引入。`emu.lunarcore` 包、反射入口、AGPL许可证及运营资源保持。

## 统一构建与冻结产物

正式专项为433项，正式全量为502类 / 13,715项，均零 failures / errors / skips。全量通过 `scripts/build.ps1 -RerunTests`，不是缓存的 UP-TO-DATE 测试结果；实际 `:test` 与 `:shadowJar` 均执行。全量开始和结束的2,535份源码 / 测试 / 资源 / 构建输入 SHA256 一致。

- 专项报告：`build/manual-sibling-save-blast-root-focused-verification.json`。
- 全量报告：`build/manual-sibling-save-blast-full-build-verification.json`，SHA256 `b12dedcaae9e304769734f955cd67d6c333c5ee13d986f1bd212a3f8c9134d59`。
- 全量日志：`build/manual-sibling-save-blast-full-build.log`，SHA256 `6767a1faeb1508b2d95cd2f56dcb38689ae2ce6268d9986d270dba9ebfdfafff`。
- 共同原生产物：`build/manual-sibling-save-blast-runtime.jar`，SHA256 `6525ccb985ca4a8efe6a420f843faf82aeae074ad09bd667a6dd2b6cec4f52be`。

为减少串行等待，在433项专项通过且实际 shadowJar 完成后冻结产物，原生链路与全量测试并行；随后同份输入的全量构建通过。冻结记录独立保留当时 `fullTestStillInProgress=true` 的事实，不能把提前冻结时刻写成全量已通过。

功能目录更新后另行打包为 `build/manual-sibling-save-blast-catalog-runtime.jar`，SHA256 `1a83ade6d6fcc171b4bccce6db24c8c4c7fdbbbd4d9fbc9ae0ba2cab01697255`。`build/manual-sibling-save-blast-catalog-equivalence.json` 核对22,403个运行时类的名称与字节全部相同，只有 features.json / feature-backlog.json 两个目录文件变化；因此保留上述共同原生包的行为验证。最终目录包另外通过两轮Dispatch / 登录 / token / combo及重启账号恢复，日志 `build/manual-sibling-save-blast-final-http.log`。HTTP工具的进程退出不计入上表22次玩法正常停服。

当前六份新增 / 修改的Python工具测试合计50项通过；预检只编译描述符及隔离配置，不计实际游戏通信。功能目录仍39项，逐项请求清单为487项（455个固定参考入口、32个本地新增入口），所有 client46 验收标记保持 false。

五个新 Java 测试类共93项。输入19项中10项、短信34项中23项、资料3项中2项、保存首六项及爆炸27项中24项都对旧冻结 F2 实际复现纯断言失败。保存的旧loaded来源漂移另在新owned实现上先复现再修复。所有编译失败、夹具错误和工具计数错误保留原记录，未混入行为 RED。

## 同一产物的实际 KCP / H2

| 链路 | 实际请求 | 检查 | 正常停服 | 同 UID H2 进程重启 | 报告 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1510 Input | 243 | 230 | 3 | 2 | `build/maze-manual-ability-current-native-third.json` |
| 3000603 结算 case0 | 178 | 206 | 7 | 6 | `build/daily-message-3000603-current-settled-native.json` |
| 3000603 到期未付款 case0 | 123 | 139 | 6 | 5 | `build/daily-message-3000603-current-expired-native.json` |
| 棋盘首次四战购买 / 保存 / 恢复 / 再保存 | 109 | 351 | 3 | 2 | `build/chess-rogue-saved-leave-current-native.json` |
| 哈努爆炸 / 原射击 / 拾取 / 归档恢复 | 122 | 167 | 3 | 2 | `build/wolfbro-explosion-current-native.json` |
| 合计 | 775 | 1,093 | 22 | 17 | 各报告的实际 jar SHA256 均相同 |

所有停服 / 数据库检查使用各自新建的 `build/smoke` 目录和 loopback 端口。Player文档在停止后的 H2 中以只读方式独立恢复；这不同于只复制 JSON 或进程内 MemoryBackend。隔离前置任务、受控日界、秘技点和本服商店配方都在各链路文档中标明，不作为官方自然流程证据。

Input第一轮错误使用不存在的击败枚举名称，第二轮真实行为通过但封存请求计数早于末次查询。最终第三轮实际请求数逐进程与 observedReplies 相等；首两轮报告和计数失败回归保留，未覆写。短信只跑结算 / 到期各一个case；其他分支的单元覆盖没有被计成全部原生验收。

## 运营数据与后续缺口

`build/manual-sibling-save-blast-after-operator.json` 和全部原生 / HTTP结束后的 `build/manual-sibling-save-blast-final-operator.json` 核对70,623份运营资源及根 config.json，70,624个文件全部 SHA256 不变。根运营 data 目录仍不存在；原生工具没有对运营数据库写入，未替换现有资源或默认配置。

功能目录保持 `partial`，逐项清单新增原6502别名及5462保存入口的来源记录。游戏客户端界面 / 演出 / 物理、完整召唤生命周期、其他输入变体、3000601与所有剧情、自然墙钟跨日、棋盘5472结束 / 首奖 / Nous / 全部三层和骰子、全奇物后效及全部活动关卡仍待继续补齐。正常H2重启通过也不代表突然断电的原子性验证。
