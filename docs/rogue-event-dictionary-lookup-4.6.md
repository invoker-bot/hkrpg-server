# 4.6 实体事件键哈希与桶链查询

Java 21 `RogueEventDictionaryLookup46` 对已匹配的 LGGGGGJFKMI 值键补入普通 Equals/GetHashCode，并补入完整普通 Dictionary.FindEntry 的默认/自定义比较器控制顺序。扩展已有监听字典的 buckets/comparer 与 Entry.hashCode/next，继续使用同一个 entries 和 Key，没有复制监听数据。

原始键哈希为 entityId*23 + event + 0x2321，保留 Int32 溢出与符号位；字典使用时再 &0x7fffffff。相等先比较实体编号原始 32 位，再比较事件码。buckets 为空直接返回 -1，不要求条目、键求值或比较器绑定。存在 buckets 时捕获 buckets、entries 与 comparer；默认分支自己求哈希，自定义分支对捕获 comparer 调用明确 HashCode 接口并屏蔽符号位，再取 bucket modulo。

bucket 记录是 index+1，减一保留溢出。条目和桶数组使用捕获引用；哈希回调修改已捕获桶内容会影响选择，替换字典头部不替换已捕获引用。以 unsigned index<entries.length 遍历。hash 不符跳过键，hash 相同才取显式键，默认比较原始 64 位，自定义走捕获 comparer 的 Equals。失败之后重新读取当前槽的 next；回调替换槽结构时使用新 next。成功直接返回捕获 index，不因回调清字典 entries 而重新验证。

默认链碰撞计数从 -1 开始，在失败后先加一再核对；自定义从 0 开始，先核对后读取 next、再加一。循环链触发并发/循环投影失败。越界或负数的 head/next 原样返回，不统一改成 -1；调用方监听撤回会重读当前 entries 再验证。零长度 bucket 保留除零错误路径；有限数组容量 4,096，缺失 Key/Entry 投影拒绝，不制造默认值结构。

34 项新增 Java 与 1,603 项暂存联合回归（41 类）、8 项 Python 来源检查通过：默认哈希/碰撞、hash 不符时缺失键、unsigned 原始哨兵、正数无效索引、零桶/零条目、两种循环计数、自定义 hash/equals、捕获容器与当前槽、回调换表/next、成功后调用方重读失败、4,096 边界及真实查询 → 监听撤回 → 委托比较移除旧回调。新增能力不计为已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-event-dictionary-lookup-4.6.json` 包含 2 个完整键主函数 168 字节、1 个完整桶链辅助体 1,086 字节、3 类声明、2 个比较器方法身份、1 份字典成员泛型、1 个比较器泛型声明与 1 份 TYPE 用法原件。RIP 槽 0x9447ee8 指向 kind 21 的 IEqualityComparer<LGGGGGJFKMI>，不是直接 CLASS 定义；分别核对 pair、原始类型记录、startup 泛型类行和实例参数。Dictionary 模板字段偏移 0 不当作闭包实例物理偏移；辅助体未匹配注册方法身份。

全量 `scripts/build.ps1`、11,021 项 Java（425 类）通过；既有 717 项 Python 全量与后续二十九批共 232 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,095 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-event-dictionary-lookup-verification.json`，封存 JAR 为 `build/rogue-event-dictionary-lookup-11021-certified.jar`，SHA `070f61225402d4783750ffc892bb50b58149fa3abccbbdff859e69e7854d3b6c`。

闭包字典运行时、比较器接口内部、原生集合/委托工厂与真实 World 注册仍待完成；本批自定义比较器调用保持明确绑定。完整销毁事件、属性撤回、宇宙/剧情/活动与官方客户端验收继续待完成。151 份协议快照和版本基线不变，宇宙 partial。未启动客户端/参考二进制，未修改运营数据、资源或配置。
