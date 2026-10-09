# 4.6 共享条件任务

`RoguePredicateTask46` 补齐 `PredicateTaskBase<T>` 的有限普通生命周期，并作为已还原繁育条件分支的默认任务。`RoguePredicateBranches46.factory(targets, leaves, diagnostic)` 不再要求调用者自行提供真假到任务状态的适配器。实体选择和 Add/Trigger/Remove 动作仍需要真实绑定，不存在未知条件直接通过的回退。

## 来源与解析边界

固定本机国服 4.6 的三个文件：GameAssembly SHA `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，global-metadata SHA `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`，startup-metadata SHA `78d090a64d8375574db2e36721e38994265c23ca8ac398ea97061523f2166034`。第三个文件长 4,091,208 字节，泛型类表位于 `0x52d60`，有 430,264 条记录；该表不使用 global 文件的 520 字节头偏移。所有操作只读，不启动或修改客户端。

比较条件运行类 54578 和队伍条件运行类 54727 的父记录都指向定义 54789 ``PredicateTaskBase`1``，具体配置实例化参数分别为 39947（ByCompareTarget）和 40093（ByTargetTeam）。三种 Evaluate 方法的虚槽都是 10，匹配共享开始方法的实际调用。

十四条函数/MethodSpec/实例化记录分别核对原始字节、具体配置类型和函数指针。实际共享主体从注册项 `+0x30` 的函数数组读取，不能拿 `+0x68` 的 invoker 调用入口当作游戏逻辑。builtin OBJECT 的 kind 28 和原始 payload 416 保留，不能把 416 当作普通 CLASS 编号。抽象 Evaluate 的空函数指针保留，未被计入可执行共享主体。泛型模板的声明字段只记录原生类型，不把解析器返回的模板偏移 0 当作真实实例布局；配置/上下文的 `+0x18/+0x20` 来自完整共享构造函数。

证据 `src/test/resources/fixtures/rogue-predicate-task-4.6.json` 的 LF SHA 为 `a0e31564c0fb7186b77221ffa0877452f72f513cf9a4113aa6ff9008afabd77e`。三个完整共享主体共 283 字节，五个完整原生解析辅助共 29,159 字节；两段共享叶方法共 9 字节、注册指令片段 109 字节分别统计，不冒充完整 PE 方法。异常路径还匹配 NotImplementedException 类型 304、构造方法 3113 及其携带的泛型 Tick MethodInfo。

## 普通生命周期

构造后的状态为 READY 7777。开始先求值，再读取当时的 Inverse；两者异或为 true 时写 SUCCESS 9999，否则写 FAIL 43690。状态只在两次读取正常返回后写入；求值或反转读取抛异常时保留原状态与已经发生的动作。重复开始重新读取，不因此前结束而忽略调用。

重置只写 READY，不重新求值。释放是原始空操作，不清空绑定或改写状态。原始 Tick 抛 NotImplementedException；Java 明确映射为 UnsupportedOperationException，任何状态和 delta 都不能静默通过。普通条件分支只对条件调用 RunOnce，因此不调用条件 Tick；它等待的是所选动作列表。

已还原的两个原始条件没有显式 Inverse，默认入口使用 false。需要明确反转的其他已核对配置可绑定实时反转读取，但这不等于已经实现所有条件类型。四参数入口仍供调用者明确提供特殊任务实现。

## 验证

三项断言先失败再修复：反转 false 结果为成功、求值发生在 Inverse 读取之前、Tick 必须抛出异常。32 项 Java 覆盖完整真值表、异常时保留状态、重置/释放、重复求值、两个原始回调默认编译、空/缺失分支、实时目标更新及等待动作不误调用条件 Tick。12 项 Python 核对三个文件、泛型父记录、MethodSpec 参数、真实主体指针、完整字节、虚槽、反转顺序、异常与输出限制。

全量 `scripts/build.ps1`、9,206 项 Java（380 个测试类）和 566 项 Python 均通过；2,825 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-predicate-task-verification.json`，封存 JAR 为 `build/rogue-predicate-task-9206-certified.jar`，SHA `cd4751b4c0d8d7ba1338a9b94562643f7ba5dd996c2e484b3a242bfbf7e16899`。

未新增网络或官方客户端界面验收；未改鉴权、存储、启动、HTTP、客户端文件或运营数据。协议基线仍是 LunarCore lite 4.2，151 份协议快照保持不变。仍缺全部条件类型、分支跳过、在线实体、完整修改器实例/资格/叠层/寿命、实际效果与在线监听，宇宙功能仍为 partial；该任务未宣称完整剧情、日常或活动玩法完成。
