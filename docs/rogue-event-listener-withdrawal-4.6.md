# 4.6 实体事件监听撤回与池化集合移除

Java 21 `RogueEventListenerWithdrawal46` 补入 EventManager.UnRegisterEventListener 的普通控制顺序，以及通用 PooledList.Remove 的接口调用顺序。事件表为空直接返回，不要求查询绑定；否则捕获当前字典，按 entityId 的 UInt32 位值作为低 32 位、EventType 作为高 32 位组合 Key，查询索引。负数索引直接返回，不读取条目数组或调用移除。

找到索引后读取捕获字典的当前 entries，核对数组边界，要求显式条目及 PooledCallbacks 值存在。查询回调替换 manager 的字典时仍使用捕获字典；修改该字典 entries 时使用当前数组。找到条目后不重复检查 key，也不在最后一个回调移除后删除字典项。通用池化移除先捕获当前底层 collection，再调用明确的接口 Remove；回调改 wrapper.collection 不改变已捕获 collection。null 回调原样传递，接口的 boolean 返回不改变 manager 的 void 结果。

33 项新增 Java 与 1,569 项暂存联合回归（40 类）、8 项 Python 来源检查通过。测试的明确集合适配器调用上一批真实 Delegate/Multicast 比较，首个等价监听移除、不同目标/方法保留、null 项、重复撤回、换字典/entries/collection、查询与接口失败以及 4,096 有限容量边界均验证。真实普通销毁 → 基类撤回 → 事件管理器控制函数 → 委托比较联合调用，两个新分配回调能移除旧监听；第二次失败保留第一次移除。适配器属于显式测试投影，没有安装原生集合运行时。

冻结证据 `src/test/resources/fixtures/rogue-event-listener-withdrawal-4.6.json` 包含完整主函数 182 字节、完整池化 Remove 辅助体 360 字节、完整字典查找辅助来源 1,086 字节、3 类字段、1 个方法身份、1 份字典泛型成员与 1 份 Remove MethodSpec。_EntityEventHandleMap 是 Dictionary<LGGGGGJFKMI,PooledList<OnEventCallBack>>；键装箱声明 UID/EventType 偏移 16/20，对应参数的低/高 32 位。PooledList.Remove 的 MethodSpec 使用 methodInst=41628、classInst=-1。泛型声明字段 _processor 的偏移 0 不当作闭包实例偏移，辅助体自身未匹配注册方法身份。

全量 `scripts/build.ps1`、10,987 项 Java（424 类）通过；既有 717 项 Python 全量与后续二十八批共 224 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,089 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-event-listener-withdrawal-verification.json`，封存 JAR 为 `build/rogue-event-listener-withdrawal-10987-certified.jar`，SHA `1d798de3fb011be234fa5ff14abcc5334ad792ed9bbf700c47f2e3210d29d854`。

字典 FindEntry 的完整内部、底层 ICollection.Remove 原生体、闭包字典运行时、真实 World/监听存储注册与官方客户端验收仍待完成。本批查询和集合接口保持明确绑定，不把测试集合适配器称为已安装原生体。完整销毁事件、属性撤回与完整宇宙/剧情/活动继续待完成。151 份协议快照和版本基线不变，宇宙 partial；未启动客户端/参考二进制，未修改运营数据、资源或配置。
