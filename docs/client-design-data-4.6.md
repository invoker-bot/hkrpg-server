# 本机 4.6 设计数据的只读定位

`scripts/read-client-design-data.py` 依据已固定客户端的原生路径转换，定位设计数据索引中的原始记录。它不启动客户端，不向客户端目录写入，不安装资源，也不表示客户端接受或玩法完成。输入 DLL、元数据和索引哈希不匹配时停止，不能用于其他补丁版本。

固定 DLL 与元数据见 [静态协议证据](client-protocol-evidence.md)。设计数据索引 SHA-256 为 `2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`；当前索引完整读取为 33 块、141,295 条记录。每个实际读取的数据块核对索引声明的长度和 MD5，并记录块及记录的 SHA-256。缺少的块和缺少的索引条目分别报告，不以相邻记录代替。

原生 `JsonToBinaryPath` RVA `1d6d0530` 使用字符串槽 `9729e18` 的 `BakedConfig/` 和 `96f5968` 的 `.bytes`。例如 `Config/Level/GroupGraph/F10301001/Group_F10301001_G156.json` 转换为 `BakedConfig/Config/Level/GroupGraph/F10301001/Group_F10301001_G156.bytes`，再以种子 0 的 XXH64 查找索引。大小写与斜杠不能自行调整。

```powershell
python scripts/read-client-design-data.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --paths Config/Level/GroupGraph/F10301001/Group_F10301001_G156.json Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_01.json Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_02.json --extract-dir build/client46-recovered-source --output build/client46-design-records.json
```

`--paths-file` 接受 JSON 字符串列表，每次最多 256 个唯一、规范的相对 JSON 路径。导出保持目录结构和 `.bytes` 扩展名；已有相同内容可以复用，已有不同内容拒绝覆盖，并在写入任何记录前检查所有目标。导出文件仍需逐个核对序列化函数和完整消费边界后，才能作为资源恢复的输入。

已定位 G156 的 **401 字节 GroupGraph**，与它的 2,949 字节 LevelGroup 布置记录是不同资源。CartoonAmmo、CartoonGun、变身管理及存档点共七份脚本已完整读取，限定解析器及验证边界见 [原始脚本说明](wolfbro-original-scripts-4.6.md)。记录级回归固定索引键、长度和 SHA-256，不能把路径命中或完整读取当作玩法已实现。

资源审计列出的 169 份缺失关卡组已由独立、限定的解析器完整恢复并新增安装；12 份已有资源对照一致，Java 已验证全部补充文件的实际数据类解析和原始维度引用装载。使用方式、只新建规则和未执行脚本的边界见 [关卡组恢复说明](client-runtime-groups-4.6.md)。

战斗事件表另有完整读取及编号核验工具，已逐行核对 79,030 条记录和原始字段顺序，见 [战斗事件完整表说明](client-plane-events-4.6.md)。表中缺失编号单独报告，不自动建立相邻编号映射。

`scripts/analyze-client-46.py` 另提供数组元素、父类型实例字段、Int32/UInt32 常量、MethodInfo 槽和字符串槽核对。原生注册表 RVA `49316f0` 的 `+0x08` 为 MethodInfo 表、`+0x10` 为字符串字面量表、`+0x48` 为 CLASS 表；这些是不同索引空间。父类型解码由原生 RVA `1f8f2e18`/`1f8f2e26` 的加法常量核对。字段继承链本身不证明二进制掩码、默认值或业务含义。

```powershell
python scripts/analyze-client-46.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --types WaitEntityEvent ByCompareGroupState WolfBroPuzzleBoard --inheritance --method-slots 0x94500e0 0x9522af8 0x9522b08 --string-slots 0x96f5968 0x9729e18 --output build/client46-design-metadata.json
python -m unittest discover -s scripts/tests
```

目前新增 5 项元数据回归和 6 项路径/导出回归；依赖本机客户端的检查在客户端不存在时明确跳过，不能把跳过视作通过。任务工厂表已有 12 个保留槽，不能用工厂方法排序推算任务编号；必须读取实际数组赋值。完整脚本执行、弹药来源和数量、活动界面与游戏客户端验收仍需另行验证，功能状态保持部分实现。

三份固定战斗关卡表已整理为可重复运行的完整读取工具，原始字段顺序、大小限制、测试表加载和六种新类别解析的边界见 [关卡表核对说明](client-stage-tables-4.6.md)。
