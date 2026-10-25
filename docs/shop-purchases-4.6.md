# 游戏内货币商店购买（4.6）

本轮按用户选择，仅处理游戏内货币兑换。梦华余额可以购买外观；真实充值、月卡支付、现金礼包不在范围内，也不接入支付或伪造订单。

## 修复内容

- 加载角色时装、列车动态物品和房间动态物品定义，解决商品存在但物品定义缺失的问题。
- 梦华（3）参与实际余额检查和扣除；61、281001、281036、281038 存入普通材料账本，支持同步、消耗和重登加载。
- 时装发放资源定义的赠送头像；已拥有的唯一外观、宠物、名片、帕姆服装等不能再次扣款。背包容量、堆叠上限、数量溢出和整批限购在付款前检查。
- 强制自选礼包按 `ItemGiftPack` 的组顺序校验每组选中的奖励，再展开 `RewardData` 发货。缺选、多选、错误奖励编号不能付款。普通可开启礼包仍作为物品进入背包。
- 循环商品读取 `ShopItemGroup`，购买次数、限购周期、个人名片选择随玩家存档保存。4.2 和 4.6 回复均返回实际累计购买次数。
- 外观购买的解锁通知和换装请求使用 4.6 的 `skin_id`，避免已经解锁后因旧字段无法编码而报错。帕姆服装和个人名片支持查询、选择及重登恢复。
- 宠物配置兼容当前 4.6 资源的字段名，修复宠物编号为零导致无法发货的问题。

## 来源与本服规则

运行时数据来自已有 `resources/4.6/ExcelOutput`，不覆盖运营资源。新增读取的表为 `ItemConfigAvatarSkin`、`ItemConfigTrainDynamic`、`ItemConfigPlayerRoomDynamic`、`ShopItemGroup` 和 `ItemGiftPack`。

协议字段依据现有 March7thHoney-OpenSource 来源 `fe00bdb` 的 `CmdShopType`、`CmdAvatarType`、`CmdPlayerBoardType` 和帕姆皮肤协议。未复制参考仓库运行代码。`BuyGoodsCsReq` 的自选列表另由本机客户端静态调用链核对：`ShopModule._BuyGoodsAsync.optionalRewardIDList` 写入请求对象偏移 `0x10`，对应 packed 字段 4（编码标签 `0x22`）。同时接受等价的 unpacked 表达，不编辑生成协议类。

静态分析客户端 `GameAssembly.dll` SHA-256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。只读取文件，未运行参考仓库的程序。

以下是明确采用的本服兼容规则，未声称与原版逐项一致：

- 每日、每周、每月刷新以 UTC+8 的 04:00 为界，周刷新在周一；周期商品以 2023-04-26 为计时起点。
- 轮换商品按表中 `RotateOrder` 和月份选择；原服轮换起点没有完整参数可核对。
- 任务兑换凭证 140430、140531 在原表中堆叠上限为 1，但同表商店定价需要 2 至 200 个。本服允许持有 99,999 个；不修改资源文件。

## 存档与异常边界

购买在玩家锁内串行执行。发货前先保存包含商品、数量、费用和奖励的待处理记录；实际发货与预期完整匹配后，才保存累计次数并清除待处理记录。正常完成后重复购买唯一物品会被拒绝。

钱包、道具和解锁表仍是分开保存的文档，不能声称跨文档原子事务。付款或发货期间出现存储异常时，保留待处理记录并禁止后续自动扣款，需核对记录后恢复；本轮没有自动补偿或跨崩溃重放。旧存档的历史购买次数无法凭空还原；已拥有的唯一物品仍按所有权防止重购。

## 验证

失败基线与报告位于忽略目录 `build/shop-purchases-20261025/`，包括缺少时装资源、梦华扣款、重复购买、整批限购、自选列表，以及时装解锁通知的失败用例。

- `ShopPurchasesTest`、`ShopPurchase46Test`、`ShopCosmetics46Test` 覆盖付款前校验、到账、保存字段和协议回包；保留现有初始外观回归。
- `ShopCatalogFixture` 对当前资源的 106 个有商品商店、900 条商品逐条用独立内存玩家购买，并验证实际到账、扣费和全部发货通知的 4.6 编码。该检查不证明 900 条商品全部经过数据库或游戏界面验收。
- `scripts/smoke-shop-purchases.py` 使用隔离端口和独立 H2 存档运行真实 KCP 会话：15 次成功购买、7 次预期拒绝，共 32 次请求；包含五件角色时装、宠物、帕姆服装、个人名片、主角饰品、兑换材料、自选礼包和车票。正常停服两次、同 UID 重启一次，检查钱包、背包、角色皮肤列表、限购次数及名片/帕姆选择一致。
- 全量构建和基础服务烟测在发布前执行；生产验证只读，不用 UID 10001 做购买测试。

真实游戏购买按钮、动画及各类外观的即时显示仍需客户端验收；本轮不等同于全商店 UI 或所有收藏品玩法均已实现。

复现命令（Windows PowerShell，使用项目 Java 21）：

```powershell
.\scripts\build.ps1 -TestForks 8
python scripts/smoke-server.py
python scripts/smoke-shop-purchases.py --output build/shop-purchases-native.json
javac -encoding UTF-8 -cp build/libs/hkrpg-server.jar -d build/shop-catalog scripts/fixtures/java/org/hkrpg/server/protocol/ShopCatalogFixture.java
java '-Dfile.encoding=UTF-8' -cp 'build/shop-catalog;build/libs/hkrpg-server.jar' org.hkrpg.server.protocol.ShopCatalogFixture resources/4.6 build/shop-catalog.json
```

## 2026-10-26 发布前结果

618 组、15,516 项完整测试通过，失败、错误、跳过均为零，耗时 11 分 28 秒。3,590 份代码、脚本、协议及构建输入在验证期间保持不变。基础服务两次启动与账号恢复检查通过；900 条商品购买及通知编码、上述 32 次 KCP 请求与同 UID 重启均通过。

验证和候选镜像使用同一 JAR：`5bcc5ffb94958f1c83fe212ff17c976109f69d8024e3287bfd64c53b8677f2e4`。镜像 `hkrpg-local/server:shop-purchases-20261025`，镜像 ID `sha256:ee37a88c707e2a5edbf57da006b6c297f70b786e303f74c292020608f7476899`。汇总为 `build/shop-purchases-20261025/final-proof.json`；原始 XML、失败基线、完整目录报告和网络记录分别保留。
