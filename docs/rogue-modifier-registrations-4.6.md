# 4.6 修饰器登记撤回

本批将名称覆盖、追加修饰器绑定键、疯狂 AI 缓存、上层分组映射的普通撤回流程补入 Java 21。来源为已固定本机国服 4.6 客户端的只读解析；未执行客户端或参考项目二进制，未改协议、版本、运营资源或数据库，宇宙状态继续保持 partial。

## 普通撤回顺序

名称和绑定键原件使用 PoolablePooledSet<string>，不是 StringHash 集合。捕获原集合并遍历槽的有效前缀，负 hash 跳过，零值结构槽保留 null 字符串；每个有效键读取当前 owner。名称映射必须存在，逐个撤回后检查捕获版本；成功后重新读取当前名称集合并清空。回调替换集合时不会转移正在进行的遍历，最终清空的是新集合。

绑定映射缺失时跳过该键；查找到值时先经显式对象池回收，再移除映射。回收失败保留映射和源键集合；查到 null 值仍进入回收，实际池在移除映射前拒绝。原件在缺失映射分支使用缓存的初始版本，存在映射分支才读取捕获集合的最新版本，本实现和回归保留这一区别，没有自行增加缺失映射路径的版本检查。成功后清空当前绑定键集合。

集合 Clear 先清 slots 的 lastIndex 前缀，再清 buckets 的 bucketLength 前缀，然后重置 count/lastIndex、freeList=-1，最后增加 version。lastIndex 非正时跳过数组和头部重置，仅增加版本；数组和数量故障保留已经清掉的前缀。集合引用和数组尾部均保留。lastIndex/枚举的有限上限 255，bucketLength 上限 4,096；超界停止，未模拟完整 PooledSet 运行时。

疯狂 AI 的空缓存直接返回；owner/entity/component 缺失则清缓存。取得 AI 组件后再读取当前缓存，按身份移除列表首个匹配项。选择时列表缺失或 count 为零采用显式共享默认值；count 至少二时调用绑定的 Sort，然后读取当前列表；其余非零 count 读取第一个槽。成功发布 AI 当前选择后最后清除修饰器缓存，任何失败保留对应阶段的列表变动和旧缓存。priority 的有符号比较已按独立 CompareTo 原件实现；一般 Sort、同优先级排列及默认目录构造仍由明确绑定提供，没有宣称安装。

上层分组依次读取 owner→entity→world→battle→mode→BattleAIPublicKnowledge→UpperGroupMap。空列表、owner/world/map 缺失允许跳过；entity/battle/mode/knowledge 缺失失败。地图准备后读取当前 holder，再捕获内部列表、版本和存储表示；每次 Move 检查版本并读取当前 count/数组。按原始 UInt32 位型移除映射，成功也不清源 ID 列表。回调替换 holder 不切换正在遍历的内部对象；四字节数组和辅助函数的八字节原始槽低位读法分别测试，未声称安装一般装箱/泛型数组工厂。

## 来源与验证

冻结证据在 `src/test/resources/fixtures/rogue-modifier-registrations-4.6.json`：6 个有 PE 异常目录完整边界的方法 2,204 字节，3 个完整辅助体 834 字节；另核对 2,790 字节 Sort 辅助体但保留为待绑定能力。11 类字段、6 个额外方法身份、7 个泛型字段、4 份泛型定义声明、2 份泛型父类型记录、6 份 MethodSpec 均匹配。泛型定义中的零偏移只是模板，不当成具体实例布局。字符串、UInt32、AI 行为和绑定值类型均由独立泛型记录验证；绑定回收调用匹配 ReleaseToPool<MDJOLMBECHA>。

90 项新增 Java 与 1,195 项联合暂存测试（30 类）通过，8 项 Python 来源检查通过。三项联合回归实际调用新对象池，测试回收成功/清空失败/null 值拒绝发生在移除映射与清键集合之前；绑定对象 Clear 由测试显式回调提供，不将其计作原生 MDJOLMBECHA 完整实现。一项首轮暂存测试曾错误预期数组越界的 Java 异常类型，修正为 IndexOutOfBoundsException 后通过，状态前缀断言始终保留。本批没有已安装生产逻辑先失败，不将该断言类型修正计入生产 RED。

全量 `scripts/build.ps1`、10,613 项 Java（414 类）通过；既有 717 项 Python 全量与后续十八批共 144 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,029 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-modifier-registrations-verification.json`，封存 JAR 为 `build/rogue-modifier-registrations-10613-certified.jar`，SHA `1f1e8ddcc881c0363e6c1639f06de173d5c8ba83f8ec09b8c3b3ac5a50b5c7e3`。

## 继续待完成

绑定载荷的原生构造/Clear、完整 AI Sort/默认目录、一般字符串字典/PooledSet/PooledList 运行时、实例化组件查找、实际全局 World 与热修复覆盖尚未安装。上层分组回归保留原始列表，不代表完整战斗 AI 公共知识运行时已经实现。完整 UnStack/AddModifier、所有宇宙能力、剧情/活动脚本及官方客户端入口和 UI 验收继续待完成；本批不增加网络验收，151 份协议快照维持原样。
