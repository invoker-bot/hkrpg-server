# 小小哈努原始脚本读取边界

已按本机固定 4.6 客户端的路径转换、完整索引、块 MD5 和记录 SHA-256，恢复并完整读取下面七份原始记录。没有启动客户端，没有安装到运行时资源，没有执行任务或发放弹药；小游戏功能仍为部分实现。

| 原始 JSON 路径末段 | 字节数 | 启动序列数 |
|---|---:|---:|
| GroupGraph/F10301001/Group_F10301001_G156.json | 401 | 12 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_01.json | 502 | 3 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_02.json | 503 | 3 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonGun_01.json | 1,513 | 7 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonGun_02.json | 2,359 | 9 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_WolfBroSaveManagar_01.json | 2,165 | 15 |
| Props/Chap03/InitLevelGraph_Prop_Chap03_WolfBroSavePoint_01.json | 59 | 1 |

完整路径以 `Config/Level/` 开始，原始 `SaveManagar` 拼写保留。G156 的 GroupGraph 与场景布置 LevelGroup 是两份不同记录。资源审计中的 169 个缺失 LevelGroup 已另行完成字段读取、12 份原始资源对照和新增安装，见 [关卡组恢复](client-runtime-groups-4.6.md)。该补充只恢复场景布置数据，不改变本页七份脚本尚未安装或执行的边界。

```powershell
python scripts/decode-client-level-graphs.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --output build/client46-level-graphs.json
python -m unittest discover -s scripts/tests -p test_client46_level_graphs.py
```

`--paths` 可选择上述七份中的唯一规范路径。工具固定客户端、索引、记录及字段证据的哈希；不接受未核对路径。输出保留 `$mask`、位置和原始字段，缺失字段保持缺失，不补默认值。相同内容可复用，已有不同内容拒绝覆盖；所有读取和解码完成后才写输出。其他客户端、补丁或记录需要重新核对。

字段证据保存在 `scripts/data/client46-level-graphs.json`，包含 67 个任务分支、7 个谓词分支和 4 个目标分支的限定解析表。数组元素和继承字段来自已核对的元数据索引；任务编号来自工厂数组的实际赋值，不能按方法排序推算。无读取载荷的工厂不消费任务掩码，例如 `PropStateChangeServerListenerConfig` 与 `LoopWaitServerPropStateChange`。目标 12 的工厂尾跳到 `TargetAlias` 读取函数，不能把反汇编窗口中下一工厂的分配误认成当前目标。

G156 脚本包含 TV 事件和组状态 1/2 分支，还监听 `FSV_Shrink`。它没有写入该楼层值，不能仅凭监听或“变小/变大”说明建立同步赋值规则。枪械脚本包含按钮、道具状态分支、装填/拾取入口、假角色限制、状态监听、效果和路标。管理脚本包含控制锁定、存档恢复、场景加载等待、进入/退出、棋盘初始化及重置事件；棋盘路径来自场景 `PuzzlePrefab`。这些静态读取结果不是服务端已执行这些动作的证据。

`DynamicFloat` 仅保留已核对固定模式的有符号原始整数，不推测比例或单位；表达式模式停止。未知任务、目标、类型、字段掩码、整数溢出、非法布尔、非有限浮点、超长字符串/数组、过深嵌套或剩余字节均拒绝。新增 20 项回归覆盖上述边界、七份完整记录、重复输出与拒绝覆盖；其中四项整数边界在旧研究解析器先复现失败后通过。本机客户端不存在时，实际读取测试明确跳过。

后续仍需棋盘资源中的弹药/枪械来源及初始数量、完整脚本执行与保存、物件恢复、所有关卡与游戏客户端实际验收。原生生命周期、坐标和控制组的既有验证见 [生命周期](wolfbro-lifecycle-4.6.md) 和 [控制状态](wolfbro-control-4.6.md)，不能用这次只读解析替代运行时验证。
