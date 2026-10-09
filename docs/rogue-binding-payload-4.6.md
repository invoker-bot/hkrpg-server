# 4.6 绑定载荷初始化与清空

本批补入 Java 21 `RogueBindingPayload46`，用于名称/绑定撤回所回收的 MDJOLMBECHA 载荷。静态来源仍为已固定本机国服 4.6 客户端；不执行客户端或参考项目二进制，不改协议、版本、运营资源或数据库，宇宙继续 partial。

新分配载荷保持字段默认值：名称引用为 null，四组值结构各三个 DynamicFloat 引用为 null，上下文和行为数组为 null。注册构造入口 `534760/0xb8a64d0` 的已核对片段为单字节 ret，没有字段写入；initialize 保留既有载荷，并不借构造器进行 Clear。该片段不计入有 PE 异常目录完整边界的方法数量。

完整 Clear `534761/0xb8a6460` 先读取共享 String.Empty，再写名称；随后七次十六字节零写覆盖四组值结构的十二个引用以及上下文/行为数组。Java 保存明确的共享 Catalog 字符串引用，将四个不可变 Member 值替换为全零值，再置空上下文和行为数组。旧 Member 快照及其指向的 DynamicFloat 对象保持原样，没有求值、清空或回收这些对象。Member 是值结构的 Java 投影，其 Java 对象引用不代表原生对象身份或指针别名。

来源确认 ADBMFDNMBIJ 是载荷四个字段的值类型，每组包含三个 DynamicFloat 引用；元数据字段偏移 16/24/32 是装箱投影，嵌入载荷后的有效组分别从 24/48/72/96 开始，不能把装箱头当作嵌入值结构内容。共享字符串所属类型通过独立 String.EqualsHelper 方法身份核对到类型 330，Empty 为静态字符串声明，布局原始标记 `0x04000110` 保留，读取来自共享引用基址槽 `0x93434a8` 加 0x110；未安装原生字符串目录工厂。

21 项新增 Java 与 1,216 项暂存联合回归（31 类）、8 项 Python 来源检查通过。实际联测调用新的引用对象池与绑定撤回流程，覆盖新分配不写 Empty、Clear 后同实例复用、严格重复回收先清空再拒绝、Catalog 缺失不入池且保留源映射/键集合、成功清空入池后才移除映射和清键集合。Clear 使用本批真实 Java 实现，四组和全部尾部字段一并核对；动态表达式运行时不在本批范围。新增能力没有已安装生产行为先失败，不将功能新增或编译计作生产 RED。

冻结证据在 `src/test/resources/fixtures/rogue-binding-payload-4.6.json`：1 个有完整 PE 边界方法 109 字节、1 个完整静态字段辅助体 1,513 字节、1 个构造叶片段 1 字节、3 类字段、2 个额外方法身份、1 份 Empty 静态布局记录。辅助体没有注册方法身份，报告明确保存 false。

全量 `scripts/build.ps1`、10,634 项 Java（415 类）通过；既有 717 项 Python 全量与后续十九批共 152 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,035 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-binding-payload-verification.json`，封存 JAR 为 `build/rogue-binding-payload-10634-certified.jar`，SHA `47b4cafed004c7bad1ceeaba017d3a70fbe0493468d9e44d5c4f6a8cb2c8b77b`。

原生 Empty 目录、全局对象池与实际 World 注册、完整绑定值表达式求值、AI 排序/默认目录、完整 UnStack/AddModifier 和全部宇宙/剧情/活动仍待完成；本批没有新增客户端或网络验收，151 份协议快照维持原样，不能据此宣称完整 4.6 或全功能完成。
