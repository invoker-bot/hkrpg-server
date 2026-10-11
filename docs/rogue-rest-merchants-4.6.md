# 原七站本服休整商店

本服新增一个可关闭的原第六站商店配方：room306 / entrance8000103 / plane80001 / floor80001003 / group22 / NPC1013 / instance400001。启用后，已有 4.6 商店接口可在这一个真实来源实例上冻结菜单、支付碎片、取得奇物并保存购买收据。默认关闭；真实游戏客户端从该 NPC 对话打开奇物菜单的入口仍未验明，功能目录保持 `partial`。

## 来源与策略

TurnBasedGameData 固定提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。独立夹具保存 9 个完整 Git blob 与 12 张表的原始选中行，包含完整原楼层；既不裁掉 TextMapHash 字段，也不改原始 `RogueRoom`、`RogueMap` 或 NPC 图。新运行代码为本项目编写，参考源码不是运行依赖，保留 LunarCore 的 AGPL 来源。

| 项目 | 来源事实或本服规则 |
| --- | --- |
| 原七站 | 304 → 302 → 303 → 304 → 305 → 306 → 307，原始地图和入口保持 |
| 303 / G34 | 原 NPC1013 / instance400001，保留原子任务 `403000304` 条件；不会继承第六站配方 |
| 306 / G22 | 原 NPC 声明图为 `Act403810002.json`，只含强化页及退出选项；不改原图 |
| 图的 SHA-256 | 仅 CRLF 转 LF 后为 `fe0ecd2145033bfbc886a0d40cc0c21d6b1677deae00f0b59d22942eb6e21361` |
| G22 的 SHA-256 | 仅 CRLF 转 LF 后为 `50aeab6d84c933dacdbac2ce2dac505448199170045cc43e72f66ce21ea4de59` |
| 原始奇物页 | DLC130 / entrance8020101 / G35 图确含 `RoguePropOpenPage(Type=MiracleShop)`；仅用于已有源商店事务回归 |
| 本服价目 | 默认基价 200 碎片、最多 3 件；支持的时钟效果会在冻结报价时计入额外四分之一 |
| 本服库存 | 从已有可取得、未持有的受支持奇物中抽取，按局 ID 和来源实例生成并保存 |

本机 4.6 DesignData 和原生 TaskFactory 的只读核对边界见 [40398 文档的入口审计](rogue-dialogue-rewards-4.6.md#生产奇物商店入口审计)。G34 主图的 8 字节记录完整解析为 `ShowMenu`；原第六站没有被证明含奇物页，不能把服务器请求可用写成官方入口恢复。未改客户端文件、原始对话图、生成协议或 VERSION。

## 启用与持久化

生产安装机制仅在文件不存在时复制 `data/RogueRestMerchants.json`。包内 `enabled=false`，运营文件原字节不会被覆盖。运营者可在这份文件中启用 `enabled=true`，并调整 `fragmentBasePrice` 与 `stockLimit`：价目必须为 1..800000 的整数，库存为 1..3 的整数。固定来源、全部位置字段、schema 和 ruleId 必须保留；未知字段、字符串开关、非整数数量或无效范围在加载时失败。来源文件缺失或字节失配会关闭绑定。

每次请求同时验证当前探索局、非暂停状态、无战斗或奖励菜单、完整队伍、真实场景入口、源组、NPC实例与完整组数据。文件哈希和运行组都要与固定来源相符。第六站配方独占该位置；关闭或来源失配时不会退回一个伪造的原图商店入口。

首次菜单将局 ID、位置、来源提交、源文件 SHA、基价、库存上限及实际报价保存到存档后才暴露。旧存档已有菜单但缺少新来源租约，也必须先成功保存新租约。调整配方不重定价已经冻结的报价；关闭开关会拒绝继续查询与购买。不同场景 entityId 不作为存档身份，重连重新生成实体后仍须匹配原来源实例。

购买在 Player 锁内扣款、取得奇物、记录 UUID 和收据并保存，成功通知在保存之后发出。重复、并发、未查询先购买、错实例、畸形原生请求或删除收据被拒绝。保存失败回退钱包、菜单、购买收据、奇物 UUID、图鉴、祝福等级、永久强化计数、时钟支出/破损替代、碎片赠送、骰子替换和回响状态；失败重试不能再生成一个已取得奇物。该事务修复也应用于已有真实源奇物商店。

## 验证命令与已验证范围

```powershell
.\gradlew.bat test --tests org.hkrpg.server.protocol.UniverseRestMerchantRecipe46Test --tests org.hkrpg.server.protocol.UniverseMiracleShopTest --tests org.hkrpg.server.protocol.UniverseBuffShopTest --tests org.hkrpg.server.protocol.UniverseDialogueRewards46Test --tests org.hkrpg.server.gameplay.RogueDiceCurioTest --tests org.hkrpg.server.protocol.UniverseDimensionalDie46Test --tests org.hkrpg.server.protocol.UniverseClockExpenditure46Test --tests org.hkrpg.server.protocol.UniverseBrokenClock46Test --tests org.hkrpg.server.gameplay.RogueResonance46Test --tests org.hkrpg.server.DefaultDataTest
python -m unittest scripts.tests.test_rogue_rest_merchants_sources -v
.\gradlew.bat test shadowJar
python scripts/smoke-rogue-rest-merchants.py --jar build/rest-merchant-floorload-runtime.jar --output build/rogue-rest-merchants-floorload-native.json
```

2026-10-11，初始 32 项均为真实断言失败，无初始化、编译错误或跳过，日志/XML 保留在 `build/rogue-rest-merchants-red.log` 与 `build/rogue-rest-merchants-red-tests`。原第六站查询确为空；已有源商店的保存失败确会泄漏付款、UUID、图鉴、库存及时钟状态。使用生产资源适配器读取完整原始表行后才运行这份 RED。

实现后商店 42 项通过，覆盖原 native 查询/购买、成本与持有标记、配置开关/重定价边界、十种场景来源失配、303 教程、暂停/待处理奖励、并发/重复、恶意报文、六类无效配置、默认关闭与运营文件保留、菜单首次保存、旧菜单补租约、收据删除、失败重试和两次独立 Player 文档读写/实体重编号。已有源商店额外覆盖正常取得、修复、自动强化、即时祝福、第三路径祝福触发回响、时钟付款/破损替换和骰子保存回退。统一定向 1166 项 / 47 类已全部通过；XML 保留在 `build/parallel-gameplay-second-red-tests/test`。固定来源 Python 5 项全部通过，逐个比较部署资源与固定 Git blob，并核对完整原表及选中行。

独立原生脚本仅在新的 `build/smoke/rogue-rest-merchants-*` 目录启动冻结 jar。原始全量奇物目录、七站地图、加权敌人、原目标任务 `4030006`、NPC与图均保持；仅四个前置主任务使用隔离无奖励 Auto 夹具，另在隔离运营配方将 `enabled` 从包内 `false` 改为 `true`。HTTP 回环显式禁用代理，输出 UTF-8；启动、每次快照和末尾守卫 jar、隔离 config 与启用配方 SHA。

2026-10-11，新冻结包 B 的原生验收实际成功，报告为 `build/rogue-rest-merchants-floorload-native.json`，日志为 `build/rogue-rest-merchants-floorload-native.log`，独立目录为 `build/smoke/rogue-rest-merchants-v0mkbe6f`。六个 KCP 快照累计 156 个真实请求、26 个无效或重复动作被拒绝；原七站与原 G22 NPC 均实际经过，未替换成 DLC130 房间。

| 快照 | 请求 | 拒绝 | 碎片 | 信用点 | 奇物 / 图鉴 |
| --- | --- | --- | --- | --- | --- |
| 首次进入第六站并冻结报价 | 87 | 14 | 258 | 366 | 无 |
| 同进程新 KCP 恢复未购买库存 | 8 | 0 | 258 | 366 | 无 |
| 正常停止后重启并购买 | 16 | 3 | 58 | 366 | 1 / 1 |
| 同进程新 KCP 恢复已购买库存 | 12 | 2 | 58 | 366 | 1 / 1 |
| 第二次重启并完成第七站首领 | 23 | 4 | 58 | 516 | 1 / 1 |
| 第三次重启恢复已完成局 | 10 | 3 | 58 | 516 | 1 / 1 |
| 合计 | 156 | 26 | — | — | — |

本次冻结库存为奇物 `1 / 59 / 66`，均为 200 碎片；购买奇物 `1` 只扣款一次，并取得原来源图鉴 `1`。奇物 UUID `ffc32695-0ab9-4f44-b6f4-d8ab4783b759` 与购买收据在后续三次 H2 检查中一致。第五站原 40398 选项 `14102` 取得祝福 `612143`，菜单 unique `1`、batch `4` 和完成收据跨全部快照保持；终局信用点从 `366` 到 `516` 只支付一次，重入完成局的终局展示通知不计为新支付。

共四次正常 `stop`、三次同 UID / 同 H2 进程重启、四次停止后的只读 H2 检查。每次两次独立读取真实 Player，共八次读取；每次比较检查前后的 BSON 玩家文档，四次均为 `changedPlayers=0`。取得 UUID、来源租约和付款收据一致；没有编辑数据库使断言成立，不把正常停止恢复计为断电原子性。

包 B 的 SHA-256 为 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b`；隔离 config 为 `2e19a7de915036ca535b115880b4bbd787d11872110d0a84dae8802dd79dbbc5`，启用配方为 `9f53a07f24c286990ebbc3c79d063c6c7b8df5aade2164393c90d4072fe4ea78`。启动前、每次快照后、每次正常停止后和最终报告均核对这些字节；2273 份源资源 SHA 保持不变。`runtime-inputs.json`、各 `normal-stop-*-input-check.json` 和报告保留具体守卫值。包内默认配方仍为 `enabled=false`，隔离文件只将其改为 `true`。

首次两个原生尝试使用包 A `build/parallel-gameplay-second-runtime.jar`，SHA-256 `02e5a8c622c785fe126a28789f8d7055a0e280ceaa618d0e2b06c71cdd890ddd`。首次脚本尝试因为探针误用了图鉴更新通知字段，未形成成功报告；该尝试已正常停止，失败目录 `build/smoke/rogue-rest-merchants-r_ult325` 保留。探针已按现有快照 descriptor 更正，不修改运行包。

第二尝试 `build/smoke/rogue-rest-merchants-owp61ma2` 已正常停止，原 G22 NPC 实际存在，但首次合法查询因来源组比较被拒绝。冻结包的独立复现确认 `FloorInfo.onLoad` 会清理 `G22.PropList[6].ValueSource`；单元场景夹具未经过这一步，来源比较因此漏了真实加载边界。只读复现仅有这一字段差异，完整源楼层与私有单组规范化树相等；报告保留 `build/universe-event-draft/rogue-g22-floorload-difference.json`，两次失败汇总为 `build/universe-event-draft/rogue-rest-merchant-native-failures.json`。

新增第 43 项回归直接使用完整原 `RuntimeFloor`、G22 原索引 18、实际 `registerGroup/onLoad`，不手动清零字段。修复前共 43 项，仅它真实断言失败：期望 `SUCC`，实际 `SCENE_ENTITY_NOT_EXIST`；原 42 项仍全部通过，errors/skips 为 0。归档为 `build/rest-floor-cavalry-red-tests/test`。最小修复为来源副本调用同一个 `FloorInfo.onLoad`，并保留完整树比较和每次请求的源文件哈希；原 42 项夹具也改为经过实际加载处理。

修复后，43 项商店回归连同相关来源、时钟、骰子、回响和保存回退回归，共 735 项 / 36 类全部通过，failures/errors/skips 为 0，且 `shadowJar` 成功。XML 为 `build/rest-merchant-floorload-green-tests/test`，输入记录为 `build/rest-merchant-floorload-build.json`。这次生成包 B `build/rest-merchant-floorload-runtime.jar`，SHA-256 `04cf130bc0ddb6d19f8ea5b8492c8d4ad45bdd08d47303bc9838ec6978698e8b`；包 A 的全量测试结果不作为包 B 的全量证据。

真实 4.6 游戏客户端的对话菜单、画面和战斗操作尚未验收。协议探针按原资源建战斗后上报胜利，不证明真人客户端已完成战斗；200 碎片、库存上限、抽取规则和原七站指定池均为本服策略，不宣称官方价格或完整官方奇物效果已恢复。其他未知事件、随机路线、Tourn/Magic 不属于这份验收。
