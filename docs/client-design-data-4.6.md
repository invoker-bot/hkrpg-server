# 本机 4.6 设计数据的只读定位

`scripts/read-client-design-data.py` 依据已固定客户端的原生路径转换，定位设计数据索引中的原始记录。它不启动客户端，不向客户端目录写入，不安装资源，也不表示客户端接受或玩法完成。输入 DLL、元数据和索引哈希不匹配时停止，不能用于其他补丁版本。

固定 DLL 与元数据见 [静态协议证据](client-protocol-evidence.md)。原设计数据索引 SHA-256 为 `2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89`，完整读取为 33 块、141,295 条记录；它仍是工具默认值。每个实际读取的数据块核对索引声明的长度和 MD5，并记录块及记录的 SHA-256。缺少的块和缺少的索引条目分别报告，不以相邻记录代替。

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

更新目录没有某个块时，现在再检查游戏自带的 `StarRail_Data/StreamingAssets/DesignData/Windows` 中同名文件；更新目录中已经存在的文件始终优先，即使其内容损坏也会按原有长度/MD5 检查报错，不会用另一份文件掩盖错误。只读索引查找的单目录接口保持原有结果。

本机索引第 18 块位于游戏自带目录：`3fe3df4c53e2f8389ce65eb73ec8c55e.bytes`，76,204 字节、2 条记录，MD5 与索引相符。两个目录合并后，20 个非语言数据块均可定位。当时工具未识别语言子目录，曾误将 13 个语言块全部报告为缺失；该问题已于 2026-10-19 修正，见文末。报告分别列出 `bundledChunks`、`missingCoreChunks` 和 `missingLocaleChunks`，保留原有 `missingChunks`。文件可定位不代表其全部内容已经解析，更不代表玩法完成。

新增 6 项实际目录回归，与原有 7 项联合通过。改动前，两个实际读取/导出保护断言失败；另四项检查依赖新增的双目录接口，原接口报告参数错误。原始块中一条 58,289 字节记录用于验证长度、散列和导出保护；该项测试固定索引键，测试路径是标签，未宣称已识别该记录的原始配置路径或 JSON 内容。本轮完整重跑 1,075 项 Python，通过；11,571 项 Java（440 类）、3,186 个源码文件及 151 份协议快照核对通过。封存报告为 `build/client46-design-fallback-verification.json`，JAR 为 `build/client46-design-fallback-certified.jar`，SHA-256 为 `01abbea7a9f47cbfa916a8c7e7be1fbb38296dd08450d8af3815e730a8b8bb5d`。未执行实际客户端玩法或 UI 验收。

## D16728363 热更新索引

2026-10-15 只读核对本机新索引 `5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed`。四个大端头字段仍为 255、4、33、141295，完整消费所有记录且没有重复键或越界；与旧索引的完整存档相比，仅块 1、13、19、23 的摘要或条目变化。两个安装目录中的 20 个非语言块全部符合声明长度和 MD5；证据为 `build/recover-acceptance-20261015/index-audit.json`。该旧报告对 13 个语言块的缺失判断受语言子目录问题影响，不能继续用于判断语言包是否安装。

读取新索引必须显式传入 `--index-sha256 5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed`，或 Python 接口的同名 `index_sha256` 参数。只允许两个已固定摘要，不接受调用者随意提供任意文件 SHA。索引头的块数和总记录数现在也逐项核对，报告记录所选实际摘要。省略参数仍按旧索引拒绝本次热更新；各原始图读取器自己的索引、记录和字段证据没有自动升级。

新回归先复现显式选择新索引后仍被旧保护拒绝；修复后原路径/导出、双目录回退和新索引三组共 17 项检查通过，其中实际读取依赖本机客户端。原 G156 与两份弹药记录的长度和 SHA 保持原值。未知或改过字节的索引、多个相同候选、不同内容的导出和损坏块继续拒绝。现有 20 块的实际长度、MD5 和 SHA256 另记录于 `build/recover-acceptance-20261015/index-storage-proof.json`。3011014 的八份 Act 使用独立的新固定原生证据，见 [演出验收记录](daily-recover-performance-4.6.md)。

## 语言子目录修正（2026-10-19）

语言块应按索引中的 `locale` 查找 `<目录>/<locale>/<MD5>.bytes`。此前工具只查根目录，会漏掉已经存在的 `en/fc21263410cd08ba5f5ac5ff280c1582.bytes`。解析器现在先读取语言标记，再选择更新目录或自带目录中的同一路径；更新目录优先，损坏文件仍报错，语言标记拒绝路径分隔符。`bundledChunks` 同时识别自带目录中的语言文件。

两项新增回归先在旧实现失败，修复后与原路径/导出、目录回退、固定索引检查合计 **19 项通过、0 跳过**。实际英文文件 60,437,779 字节且 MD5 匹配。补入中文文字后，20 个非语言块与 `cn`、`en` 两个语言块均存在；其余 11 种语言未安装。中文设置和资源校验见 [中文语言修复记录](client-chinese-language-4.6.md)。
