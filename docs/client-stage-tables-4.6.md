# 本机 4.6 完整战斗关卡表核对

`scripts/inspect-client-stage-tables.py` 完整读取固定本机客户端的常规、测试及 LD 三份关卡表，然后查询指定关卡 ID。它不安装 JSON、不执行关卡、任务或战斗，也不启动或修改客户端。设计数据定位器现在接受客户端实际使用的规范 `ExcelOutputGameCore/*.json` 路径，保留路径、索引、数据块和输出目录的原有校验。

三份表的定位和当前 Java 测试表加载范围见 [测试关卡加载说明](stage-test-resources-4.6.md)。读取器逐份核对记录键、长度、SHA256、行数及完整语义 SHA256，完整消费所有字节。只读关卡表使用独立的 8 MiB 上限，原有剧情关卡图读取器的 4 MiB 上限不变。

原生 `StageRow.FromBinary` 是方法 101757 / RVA `1dade570`。序列化掩码位顺序如下，不能照搬元数据中的物理字段顺序：

| 位 | 字段 | 编码 |
|---:|---|---|
| 0 | StageID | UInt32 |
| 1 | StageType | 有符号 Int32，固定客户端的显式枚举 |
| 2 | StageName | TextID 运行时缓存索引与 UInt64 Hash；JSON 只保留原始 Hash |
| 3..5 | HardLevelGroup、Level、EliteGroup | UInt32 |
| 6 | LevelGraphPath | UTF-8 字符串 |
| 7 | StageAbilityConfig | 字符串数组 |
| 8 | BattleScoringGroup | UInt32 |
| 9 | SubLevelGraphs | 七字段原始子记录数组 |
| 10 | StageConfigData | 两字段原始子记录数组 |
| 11 | MonsterList | Monster0..4 的原始波次数组 |
| 12..13 | LevelLoseCondition、LevelWinCondition | 字符串数组 |
| 14..17 | ForbidAutoBattle、ForbidViewMode、Release、ForbidExitBattle | 单字节布尔 |
| 18 | MonsterWarningRatio | 有限 float32 |
| 19 | ResetBattleSpeed | 单字节布尔 |
| 20 | TrialAvatarList | UInt32 数组 |

`SubLevelGraphs` 的原生子读取器为 `101745 / 1d063260`；掩码位对应 `BFLIFKBEOPJ、KACPIAGHKMN、EOAHMJFOLHD、MEDEFCIKNLA、APNFMFEMPNI、LGNKFIIEICM、BIEJJCIFBGA`，保留原始混淆名称，不猜各字段业务意义。`StageConfigData` 的读取器为 `88853 / 1d2c6ef0`，顺序为 `BFLIFKBEOPJ、MNDFOPKBHKP`。波次使用类型 15135 的 `101739 / 1dae34c0`，顺序为 `Monster0..4`，不读取未序列化的处理缓存。

全部 29,515 条常规关卡与现有 JSON 一致；比较前仅把原始 MonsterWarningRatio 转为其实际 float32 位模式，语义 SHA256 为 `f890f7b9886f40e4bdef2e6dba36027455bb3192e148cce6a83f1df714f71ad5`。829 条测试关卡原始行完全一致，语义 SHA256 为 `df9bb14121b90ecb36c61e84cd428af95da1bdb88c1ade20f7551c577126b259`。本机 LD 表为零行，不能把其他区域 JSON 的 51 行算作本机表内容。

Java 另补齐元数据类型 10419 明确声明的六个 StageType 值：FateActivity=34、LocalLegend=35、ElationActivity=36、FateRin=37、ExpeditionBattle=38、FinalityActivity=39。它们以前被 `StageExcel.onLoad` 降为 Unknown，现在保留原始类别。八项 Java 回归中六项在修复前真实失败；旧 VerseSimulation=8 和未经核对的未来类型回退行为保留。只修类别解析，不自动增加活动规则、脚本、奖励或完成状态。

```powershell
python scripts/inspect-client-stage-tables.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --stages 100 166 177 190 425001 426005 4270010 429001 428001 431001 --output build/client46-stage-tables.json
python -m unittest discover -s scripts/tests -p test_client46_stage_tables.py
.\gradlew.bat test --tests '*StageType46Test' --tests '*StageTestResource46Test' --no-daemon
```

14 项读取器回归及一项真实路径回归覆盖完整三份表、全部字段顺序、原始六种类型、无符号 Hash、缺失字段、显式 null 数组、大小限制、未知枚举/掩码、重复 ID、非法整数/布尔/字符串/浮点、截断、剩余字节、查询范围和报告冲突。固定客户端不存在时，实际读取测试明确跳过，不算客户端验收。相同报告复用并保留修改时间，不同内容拒绝覆盖；客户端目录禁止输出。缺失关卡不补造，指定关卡若同时出现于多个表则拒绝合并覆盖。
