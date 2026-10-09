# 4.6 共鸣固定值的实体查找和 AITag

这一批补充的是明确传入的固定容器投影，不是战斗对象的自动绑定，也不是全部共鸣效果的实现。客户端 `GameAssembly.dll`、元数据、设计记录的版本和哈希延续上一批核对；协议、命令号及生成类保持原样。

## 缺失键的原生值

`ENEIHJJPNPI.MPIBNJBBOKM`（74755）的完整 PE 方法，在字典查找返回负索引时把 `0x9343d30` 的值写入输出，并返回 false。FixPoint 初始化器（68417）从文件中的 `0x556db70` 读取 16 字节，写到 `0x9343d28`；第二个 64 位字为零，正好初始化该缺失值槽。证据使用实际文件数据、初始化指令和目标地址，未把 `.data` 的虚拟零填充当作初始化证据。

`RogueResonanceVariables46.readNativeFixed` 据此返回 raw zero。原有 `read`、`bounds` 和直接选定容器的严格舍入 API 保持缺失键拒绝行为。上下文舍入按原生读取路径处理零，随后调用缺失键 setter：不创建单元、不通知 observer，正常返回后才把任务状态置为 `0x9999`。容器本身不存在仍然失败。

上下文作用域 7 选取 Owner 的 AITag 容器，不检查键是否存在。这里的 Entity 投影显式传入 AITag；真实泛型组件类型和获取过程尚未绑定。

## 带实体的重载

`AbilityStatic.GetDynamicValueLookupByKey`（508322，完整 531 字节）先比较目标与 Summoner、Owner 的引用身份。非作用域 6 且身份匹配时，委托原上下文选择器。作用域 6 始终使用传入目标的 custom 容器；其他非上下文角色的目标，作用域 7 使用目标 AITag、作用域 9 委托上下文 callback，其余已匹配的 0～9 作用域使用目标 custom 容器。这些目标容器不检查键，缺失键读取零、写入忽略。

比较采用引用身份，不能用 equals 替代。原生空指针也参与比较；缺少上下文角色且目标为空时可能委托上下文。仅上下文重载的作用域 6 依然不支持，未凭空添加回退。

2791 表达式任务的新实体入口先执行魅惑跳过，再对上下文求值一次，然后顺序选择每个目标的容器并写入。后续目标选择失败不回滚前面的写入，也不继续处理后面的目标。有限入口最多 255 个显式投影；现有三个任务的其他签名保留。

## 验证范围

新增 20 项 Java 回归，包含六项在旧实现上实际先失败的断言。回归覆盖缺失键、同身份/不同身份/空目标分支、上下文 Owner AITag 委托、魅惑跳过、求值一次、部分写入、通知和生命周期。265 项共鸣相关 Java 定向测试通过。新增十项 Python 回归核对七个完整托管 PE 方法（11,128 字节）、沿用的 SetupMethods（1,333 字节）、getter/provider 片段（116 字节）、初始化数据（16 字节）及原始四项声明和六项写任务。

全量 `scripts/build.ps1`、8,609 项 Java（357 个测试类）和 332 项 Python 均通过；2,692 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-entity-lookup-verification.json`，封存 JAR 为 `build/rogue-resonance-entity-lookup-8609-certified.jar`，SHA `f1b5867ee2b908770eb4cbbc8f0d0d2f2ba47f301b7dc0b641b3f9b2f133e719`。

证据读取器为 `scripts/read-rogue-resonance-entity-lookup.py`，输出限定在工程 `build/`。它不修改客户端、不安装 JSON、不启动客户端、不修改运营配置或存档。原生数值 provider、动态单元 provider、真实组件类型和角色投影获取、实际参数值、真实 CurrentSP、战斗任务分支与完整效果、官方客户端入口/接受度均未验证；功能状态仍为 partial。
