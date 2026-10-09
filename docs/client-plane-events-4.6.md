# 本机 4.6 战斗事件完整表核对

`scripts/inspect-client-plane-events.py` 完整读取固定客户端的 `PlaneEvent` 表，再查询指定事件编号。不是搜索二进制中的数字，也不以相邻事件代替缺失编号；不安装资源、不执行任务或战斗、不启动或修改客户端。

原生 `PlaneEventExcelTable` 初始化使用字符串槽 `97847b8` 的 `BakedConfig/ExcelOutput/PlaneEvent.bytes`。设计数据中的记录键为 `04e1e47c07cb2349`，长度 1,353,762 字节，SHA-256 为 `fd3bdba0459bab5136d665f9c96253ae4cab279d65c0bb5cc60e4e2c3add4c76`。定位工具仍核对客户端输入、完整索引及所读块的长度、MD5 和 SHA-256。

读取顺序依据 `PlaneEventRow.FromBinary`（方法 94806、RVA `1d8f1bd0`），不能使用元数据中经过布局整理的字段顺序：

| 掩码位 | 字段 | 编码 |
|---:|---|---|
| 0 | EventID | UInt32 |
| 1 | WorldLevel | UInt32 |
| 2 | StageID | UInt32 |
| 3 | DropList | 有符号数组长度，各元素 UInt32 |
| 4 | Reward | UInt32 |
| 5 | IsUseMonsterDrop | 单字节布尔 |
| 6 | AvatarExpReward | UInt32 |
| 7 | DisplayItemList | 当前固定表的全部记录均为空数组 |

完整表包含 79,030 行，全部字节消费完毕。它与现有 `PlaneEvent.json` 的每行字段、数组顺序、缺失字段和数值一致，规范化语义 SHA-256 为 `75cee5e400d5ee59b5dd86fa228124a197c6e552f1806246d366d81da602b3f5`。默认查询的 20134221 和 20134222 各有世界等级 0..6 的七行；20134220 在这份完整表中没有记录。

剧情 3011602 的现有脚本使用 20134220，完成目标使用 20134221。这次核对只确认固定本机表的缺失情况，不自动改写脚本、放开任务或构造战斗映射。其他剧情和宇宙效果仍需各自核对资源及执行语义。

```powershell
python scripts/inspect-client-plane-events.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --events 20134220 20134221 20134222 --output build/client46-plane-events.json
python -m unittest discover -s scripts/tests -p test_client46_plane_events.py
```

工具仅接受 1..256 个唯一正整数 UInt32 查询编号。记录及完整表的哈希或行数不匹配时停止；解码另限制输入大小、行数、每行节点、数组长度和整数宽度。未知字段、非法布尔、重复事件/世界等级身份、没有正整数事件身份、未核对的展示道具数组及剩余字节均拒绝。缺失字段不补默认值；仅在检查重复身份时把缺失世界等级视为零。报告相同内容复用且不改修改时间，不同内容拒绝覆盖；客户端目录禁止作为输出目标。

新增 10 项回归覆盖字段顺序、缺失字段、世界等级、数组、严格边界和完整原始表的重复查询/输出冲突。实际读取测试依赖固定本机客户端，缺少客户端时明确跳过，不作为通过或客户端玩法验收。
