# 4.6 卡池主推名称与卡面展示修复

## 问题与修复边界

用户实机在「蝶立锋锷」看到希儿立绘却标注娜塔莎，左侧三个角色位置为黑色 `TEST` 占位卡；其他角色、光锥活动池也存在缺图。运营配置 `2001` 的五星为 `1102`，四星为 `1105,1106,1109`，原始客户端预制体也指向希儿，问题来自服务端展示字段的语义映射。

旧实现参考 Honey 固定提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083` 的字段名和 `BannersConfig.ToInfo`，把五星放入字段 10、四星放入字段 5。对当前本机客户端的只读核对证明这两个展示槽位相反。保留描述符原名和原字段号，在 `GachaInitial46` 中纠正写入；不修改生成协议类，不替换客户端资源。

活动池字段 5 使用经过资源、星级和类型校验的五星 UP；字段 10 使用同一实际抽取配置的四星 UP，保持运营顺序和多个主推目标。常驻、新手池沿用已有五星展示子集，并补齐主推列表。删除没有来源证明的字段 7 固定值 `11`。本次不改变扣费、概率、保底、发奖、存档、卡池排期或运营文件，也不宣称新增自选机制。

## 本机客户端独立证据

`scripts/analyze-client-46.py` 仅静态读取文件，不启动、修改或注入游戏。适用的 SHA-256：

- `GameAssembly.dll`：`8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`
- `global-metadata.dat`：`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`

通过 `GachaModule._CmdGetGachaInfoScRsp`（方法 593076，RVA `d0914e0`）的类型槽定位响应 `LDPGAPPKBBB`，再通过响应静态初始化的字段 13 codec 定位卡池 `LKLKNFHKCBK`。卡池 `WriteTo`（181881，`1e9656b0`）和 `.cctor`（181852，`1e9664c0`）将三个 repeated uint32 对应到以下实际布局：

| 线字段号 | 当前描述符名称 | 客户端实例偏移 | 实际消费者 |
|---|---|---|---|
| 5 | `ONIHOMCELJK` | `0x20` | `PrizeItemDatas`，主推项目 |
| 10 | `prize_item_list` | `0x40` | `DetailItemDatas`，卡面预览 |
| 7 | `item_detail_list` | `0x28` | `SyncDecideData` 的选择元数据，非卡面 |

`.cctor` 的 `0x2a / 0x52 / 0x3a` codec 标签与 `WriteTo` 的对应实例读取交叉核对。`BaseGachaPoolData.SyncGachaPoolData`（593156，`c7e0fb0`）在 `c7e1094..c7e10a7` 把偏移 `0x40` 送入 `_InitDetailItemsRow`，把 `0x20` 送入 `_InitPrizeItemsRow`；两者按物品编号建立展示数据。`SyncDecideData`（593150，`c7e04a0`）另行消费 `0x28`，并要求非零选择类型，不能把它解释成角色活动池标记。

静态证据保留在 `build/gacha-banner-display-20261025/client-gacha-*.json`。这证明当前二进制的字段消费关系，不代表其他客户端版本兼容或游戏画面已经验收。

## 回归与验收

`GachaInitial46Test` 先出现 10 项中 6 项失败，修复后检查角色池、光锥池、多主推顺序、常驻/新手预览、有效期、空目录、认证和只读查询。关键断言直接读取序列化后的字段 5/10/7，不依据具有误导性的描述符名称判断语义。

`scripts/smoke-gacha-banner-display.py` 使用隔离 H2、临时端口和真实 KCP，打开全部 278 条历史活动模板及常驻、新手测试配置；按字段号独立解析回包，检查 280 池完整展示、无效/未开始/到期/停用排除、同 UID 正常重启与配置不变。该检查不执行抽取，不写入运营账号。

2026-10-25 验证通过：

- 专项 Java：9 个套件、56 项，零失败；先失败记录见 `red.log`。
- `scripts/build.ps1 -TestForks 8`：616 个套件、15,501 项，零失败、零跳过，完整构建通过。
- 展示 KCP：每次登录准确收到 280 池，两次正常停服、一次同 UID H2 重启；不抽卡。
- GM KCP：11 项操作、18 次重放检查、两次正常停服，复刻后的展示、时间和库存恢复通过。
- 最终 JAR SHA-256：`0951b65912252f36f02bf64236151d4c26827c249be35d4da0bfad6809e7e8df`；上述网络检查、完整构建与待发布镜像使用同一产物，源码冻结摘要核对通过。

证据位于 `build/gacha-banner-display-20261025/` 的 `focused-counts.json`、`full-counts.json`、`native.json`、`gm-native.json`、`final-proof.json`。当前自动化可以读取游戏窗口，但鼠标返回和 Esc 未使游戏离开商店，因此没有把自动化检查记为画面通过。实际游戏仍需重新进入跃迁页面确认名字、星级、三张卡面和光锥预览；网络检查不能替代客户端画面验收。

## 发布

实现提交 `1334ed2b507a7c88827e3a5deb5573ec7fdd665b` 已更新到 `hkrpg-local-server-1`，运行镜像 `sha256:78c9d02e312afeaf60d6b9a624875888208abd45b885e5867ddcc046ee929853` 与上述已验证 JAR 一致。健康检查通过。正常停服后将存档、配置、凭据和旧产物备份至 `.local/backups/gacha-banner-display-20261025-233958-1334ed2`，从备份副本读取停服落盘状态，再与新服务的只读快照对照。

UID 10001 的 97 项完成记录、125 份物品/角色文档、剧情、选角、队伍、余额和保底全部相同；配置与运行环境摘要保持，未重置账号。发布证据为 `deployment.json`。客户端卡面复测已请求，当前仍待用户确认。
