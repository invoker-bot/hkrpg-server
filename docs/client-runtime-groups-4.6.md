# 本机 4.6 缺失关卡组恢复

已从固定本机客户端恢复资源审计列出的 169 份缺失运行时关卡组，完整读取每份记录，并以新增文件和独立补充清单安装到 `resources/4.6`。原有资源清单及 12 份对照资源的 SHA-256 均未变化。安装后审计 70,602 个清单文件无校验失败，698 个楼层的缺失关卡组引用为零。恢复的是场景布置数据，不代表完整剧情、活动脚本或游戏客户端已经验收。

## 来源和读取范围

工具为 `scripts/recover-client-runtime-groups.py`，字段证据为 `scripts/data/client46-runtime-groups.json`。输入 DLL、元数据、设计数据索引、每份记录的索引键、长度和 SHA-256 均固定；客户端或补丁变化时停止。定位和原始数据块校验见 [设计数据定位](client-design-data-4.6.md)。仅接受证据清单中的 181 个唯一规范路径，其中 169 个为补充资源，12 个为已有资源对照。

138 个类型的字段以及 70 份枚举或常量映射来自元数据；读取顺序、工厂分支和特殊编码另按原生函数核对。根对象只接受运行时分支 2，不读取编辑器分支。道具的 77 个字段和 NPC 的 84 个字段使用两个先行掩码，不能先读第一组载荷再取第二掩码。空对象仍消费掩码；BYTE 为固定单字节；`JsonEnum` 为两个有符号 Int32；`TextID` 的原始缓存编号与 UInt64 哈希分别保留，资源输出使用原有 `Hash` 字段。向量按原有 JSON 的大写 `X/Y/Z` 输出，浮点保留 float32 数值。

已核对六类多态工厂：共享值、氛围条件、锚点参数、小游戏参数、NPC 接近配置和动态对象引用。枚举输出使用实际常量值对应的名称；多态 JSON 类型名只使用已核对的原始名称。字典仅支持已核对的组属性及触发器键值类型；效果覆盖字典当前只接受已验证的空编码。未知分支、字段、类型名、非空未核对字典、重复键、整数溢出、非法布尔、非有限浮点、超出大小或递归边界以及剩余字节均停止，不补默认字段。

## 使用方式

```powershell
# 只读解析并保存报告。
python scripts/recover-client-runtime-groups.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --output build/client46-runtime-groups.json

# 先输出到独立目录，检查生成的 JSON 和补充清单。
python scripts/recover-client-runtime-groups.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --destination build/runtime-group-review --output build/runtime-group-review-report.json

# 显式补充运行时资源，只新建缺失文件。
python scripts/recover-client-runtime-groups.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --destination resources/4.6 --output build/runtime-group-install-report.json
python scripts/audit-resources.py
python -m unittest discover -s scripts/tests -p test_client46_runtime_groups.py
```

所有记录先完成读取、哈希校验和转换，再检查整批输出是否冲突；已有相同内容复用且不改修改时间，不同内容拒绝覆盖。补充清单命名为 `resource-supplement-client46-rtgroups-<内容哈希前12位>.json`，记录来源为运营者本机 CN 客户端及完整输入哈希，不改原始清单。客户端目录禁止作为报告或安装目的地。`jsonInstalled` 仅表示写入所指定目录，不证明 Java 已加载或客户端已接受。

## 验证边界

新增 24 项 Python 回归覆盖字段掩码、多态、向量、文本哈希、字典、严格边界、整批冲突拒绝和幂等输出。向量大小写的两项回归先复现失败，再修复转换。12 份原始对照记录均完整读取，字段键、列表顺序、标量类型、枚举及 float32 位值一致；保留原始文件哈希和可移植语义哈希。一个现有 `P10501_F10501001_G2` 文件没有匹配本机索引，未纳入对照，未用邻近文件替代。

Java 使用实际 `ResourceLoader.getGson()` 解析全部 169 份补充文件，核对 187 个锚点、364 个道具、90 个 NPC、3 个怪物及 83 个组属性的数量、身份和坐标。按原始楼层维度引用装载 167 个组，并完成 21 个楼层的 `onLoad()`；没有强制装载未被维度索引引用的组。这一检查确认现有 Java 数据类能够消费补充资源，不证明未支持的客户端字段已经参与玩法。

全部关卡组布置恢复不等于全部脚本恢复。七份小小哈努脚本仍为只读解析，未安装或执行，见 [原始脚本读取边界](wolfbro-original-scripts-4.6.md)。弹药来源与初始数量、完整剧情和活动脚本执行、所有玩法及真实客户端入口仍需继续实现和验收。冻结协议基线保持 LunarCore lite 4.2.0，不以资源版本或修改版本字符串宣称完整 4.6 兼容。
