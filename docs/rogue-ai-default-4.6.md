# 4.6 默认 AI 行为目录

本批补入 Java 21 `RogueAiDefault46`，并给现有 `RogueModifierRegistrations46.Behavior` 增加技能布局数组与两个布尔字段，优先级、比较器和选择/撤回仍使用同一对象。静态来源是固定的本机国服 4.6 客户端，不运行客户端或参考二进制，不改运营数据、资源、版本或协议。宇宙状态继续 partial。

构造入口 513265/0x158da7f0 的已核对叶片段是单字节 ret，不清空既有字段；新分配的默认值由分配方提供。完整静态构造 513266/0x158da800 先分配行为，再读取 GameCoreConfigManager.PriorityConfigList，按 CrazyAIBehaviorPriority 查询优先级。PriorityConfigList 的配置字典指向 PriorityConfig，其内层字典取 PROG_Default 的 Int32 值。两键均由原生字符串槽独立解码，外层或内层键缺失均走抛错尾部，没有零值或其他键兜底。Java 使用明确的有限 Map 投影；通用 CLR 字典内部、原生反射/分配与热补丁不在该投影范围。

读取成功后先写优先级，再请求长度为一的 SkillLayoutKey 数组，写第零元素 Normal=1、保存数组、将两个开关都设为 true，最后发布共享默认引用。Normal 的枚举常量、数组元素类型、字段 16/24/25/28，以及 GameCoreConfigManager.PriorityConfigList 与 FOEAEFCBCOM.HGLIFJGHADE 的静态引用布局原始标记分别 0x040016c0、0x04042d70 已核对。静态引用基址槽为 0x93434a8，保留标记，不把它们解释成对象成员偏移。

初始化异常保留已经完成的写入，不将失败的新对象发布，也不回滚回调已经修改的目录。空分配仍先读取/验证优先级；数组分配/空引用/零长度失败保留优先级而不覆盖数组和两个开关。只有正常尾部替换共享引用。显式投影的 Catalog/World 缺失在入口拒绝，不代表原生共享存储本身可为空或原生线程安全/一次性静态构造调度已实现。

40 项新增 Java 与 1,256 项暂存联合回归（32 类）、8 项 Python 来源检查通过。联合测试调用实际 AI 选择、符号比较和登记撤回：撤回最后一个登记恢复本批构造的默认对象，撤回两项中的一项保留另一对象，失败重建后仍使用上次已发布默认引用。测试配置 17 等数值仅为隔离夹具，没有从原始资源确认官方 CrazyAIBehaviorPriority 数值，也没有把这些数值写为生产默认值。新增功能不计作已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-ai-default-4.6.json` 包含 2 个完整 PE 边界方法 414 字节、1 个构造叶片段 1 字节、5 类字段、1 个额外方法身份、2 个原始静态布局、2 组已关联字段的泛型字典记录、2 个字符串槽与 1 个行为类型槽。读回脚本还复核先前选择/撤回的来源。叶片段不计入完整方法数量。

全量 `scripts/build.ps1`、10,674 项 Java（416 类）通过；既有 717 项 Python 全量与后续二十批共 160 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,041 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-ai-default-verification.json`，封存 JAR 为 `build/rogue-ai-default-10674-certified.jar`，SHA `8ed6f4022adf38fa2a8e32ea45a64c26b73f6e037654134d67af861fdb14a7c8`。

官方优先级资源值、实际 World 与全局目录注册、AI 排序/相同优先级次序、完整 UnStack/AddModifier、全部宇宙/剧情/活动与游戏客户端验收继续待完成。本批没有新增网络或游戏 UI 验收，151 份协议快照维持原样，不能据此宣称完整 4.6 或全功能完成。
