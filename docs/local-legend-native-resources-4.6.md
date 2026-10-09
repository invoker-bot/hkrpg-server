# 4.6 传奇挑战原始资源核对范围

本轮交付只读检查工具 `scripts/inspect-client-local-legend-tables.py`。它完整读取固定本机客户端的四份配置，共 173 条记录；没有启动客户端、安装资源、执行关卡或接通传奇挑战处理器。现有玩法覆盖状态仍按 `features.json` 标注，不能把本次配置核对算作活动完成。

## 原始记录与读取边界

| 原始路径 | 记录键 | 字节数 | 完整行数 |
| --- | --- | ---: | ---: |
| `ExcelOutput/LocalLegendStageConfig.json` | `367e3912aa7875c8` | 2949 | 15 |
| `ExcelOutput/ActivityLocalLegendGroup.json` | `7820c7364640609f` | 719 | 5 |
| `ExcelOutput/ActivityLocalLegendReward.json` | `c1d7c4b8835aff9e` | 72 | 10 |
| `ExcelOutputGameCore/BattleTargetConfig.json` | `36867b21343ea0d2` | 10275 | 143 |

客户端目录为 `C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current`。工具复用本项目只读设计数据索引检查，验证完整索引、分块校验、记录键、原始字节 SHA256 和完整表语义 SHA256。DLL 固定 SHA256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`，设计索引为 `2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`；其他客户端不能沿用这些解码常量。

每份表必须完全消费字节；单份记录上限 16 KiB，数组上限 64 项，三份活动表上限 64 行，战斗目标表上限 256 行。四份固定记录均有原始单字节 `00` 前缀，只核对该字节，不把它解释成通用版本号或推广到其他表。缺少身份、重复身份、未知掩码、未核对的枚举、整数溢出、非法 UTF-8、非规范整数、截断或剩余字节均拒绝。文本保留原始无符号 64 位 Hash；运行时 TextID 缓存索引不写入作者配置。null 数组与空数组保持区别。

位顺序来自本机 `FromBinary`：关卡 81215/RVA `1d33cb10`，分组 81229/`1d33c4f0`，奖励 81243/`1d33c960`，战斗目标 87469/`1d43bb30`；不能按元数据字段排列解码。难度显式值 Easy=1、Hard=2、Fearless=3，奖励分类 EasyHard=1、Fearless=2。战斗目标 Type 的 ChallengeTarget=1 是配置分类，本轮没有把它当成协议 `battle_target_info` 的字典键。

原始表与当前 `resources/4.6/ExcelOutput` 对应四份 JSON 的完整记录逐字段一致。15 个关卡为 5 组各 3 个难度；分组模块关系为 1→5007003、2→5007001、3→5007002、4→5007004、5→5007005。传奇挑战使用 35 个不同战斗目标，原始条件均为 GreaterEqual，但阈值分别为 1、2、3、6、10、10000 或 18000，不能用统一“战斗胜利”代替条件判断。143 行目标表中不属于传奇挑战的记录只保留原始配置，本轮没有新增它们的执行支持。

## 已核对的客户端消费连接

`ActivityLocalLegendModule.TriggerEnterBattle`（567533/`c52e000`）调用 `AdventurePhaseEnterBattle.TriggerBattleByLocalLegendActivity`（552788/`c613110`），再调用 `NetworkManager` 的本机方法 634306/`d980730`。该发送点使用请求 6591（`0x19bf`），等待响应 6583（`0x19b7`）。这是本机静态发送点证据；没有据此声称全部入口命令、注册回调或客户端联网验收已完成。

`ActivityLocalLegendData.SyncGroupInfo`（567441/`c526c80`）按难度取得关卡，再向 `ActivityLocalLegendLevelData.SyncBattleTargetCount`（567476/`c526f30`）传递目标掩码。原始 `JJEFLBCLBFN` 的 wire MergeFrom（196301/`1e72bfd0`）核对为：字段 1 的目标位掩码保存到物理偏移 `0x18`，字段 4 的 group_id 保存到 `0x1c`，字段 9 的难度保存到 `0x20`。位掩码对应原始 BattleTargetList 的位置，不能改成完成目标数。`get_IsCompleted`（567482/`c52cb00`）比较已完成目标数与该关卡原始目标列表长度。

上述连接来自固定原件的静态代码和完整配置。方法周边窗口没有自动被视作整个方法控制流或实际运行结果，协议注释中的物理偏移也没有替代真实读写指令。冻结协议文件、生成协议类、版本常量、运营配置和游戏安装目录均未修改。

## 重复核对

```powershell
python -s -X utf8 scripts/inspect-client-local-legend-tables.py --client-dir C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current --output build/client46-local-legend-tables.json
python -s -X utf8 -m unittest discover -s scripts/tests -p test_client46_local_legend_tables.py -v
```

报告不得写入客户端目录；已有不同内容的报告不覆盖。15 项 Python 检查覆盖四类位顺序、显式枚举、Hash、空值、数组/行数/字节限制、重复身份、非法编码、完整原件消费与重复报告保护。没有固定本机原件时，实际原件核对测试明确跳过，其他读取边界仍执行。这些是新工具的读取检查，不计为已有玩法错误的先失败回归。

后续仍需接通组开放时段和剧情前置、实际正式/试用队伍、目标报告归属、难度解锁与重启保存，核对单目标及 BattleChallenge 整组奖励的完成条件，并做原生 KCP/H2 和客户端入口验收。本轮没有猜测整组奖励编号的含义，没有让空查询或固定完成响应冒充可玩活动。
