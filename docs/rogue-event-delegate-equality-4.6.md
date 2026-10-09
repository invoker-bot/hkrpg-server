# 4.6 事件委托与多播委托比较

Java 21 `RogueEventDelegateEquality46` 对明确的 OnEventCallBack 投影实现普通 Delegate.Equals 和 MulticastDelegate.Equals；扩展已有基类 Callback 的 data/delegates 字段，继续引用同一修饰器 target，没有创建第二份委托目标或生命周期。

普通比较先拒绝空或非 Callback 的参数，再检查 target 引用。之后先查询 other 的 GetMethodImpl 对象、捕获返回值，再查 self，对方法对象作引用比较。回调期间目标改变不重新核对，data 在两次查询之后读取。两侧 data 存在时先比较 target_type 引用，再用 String.op_Equality 对 method_name 内容作序数比较；只有一侧 data 时只要求该侧 target_type 为空，忽略 method_name 和 curried_first_arg，不能补上源函数没有执行的标志检查。

多播比较先执行实际普通比较，再处理两侧 delegates。两侧为空相等；空与存在的零长度数组不相等；两侧存在时初始长度不等直接失败。每轮读取当前左长度和右数组、按当前右边界取值，对左项执行明确的虚比较绑定；左项为空失败，右项为空原样传入。虚比较失败直接结束；成功后即使刚比较最后一项也要求当前左数组存在，再进入下一轮。回调换左数组、缩短左/右数组、清空左/右引用按源顺序处理。有限数组上限 255。

32 项新增 Java 与 1,536 项暂存联合回归（39 类）、8 项 Python 来源检查通过，包含目标/方法身份顺序、两个空方法对象、方法查询异常、data 重读、单侧 data、Unicode 名称、currying 标志不参与、多播空/零长度、虚调用前后换数组与边界失败，以及实际基类两次分配的回调可以相等且销毁/淡出回调不混同。新增能力不计为已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-event-delegate-equality-4.6.json` 包含 2 个完整主函数 805 字节（Delegate 397、MulticastDelegate 408）、1 个完整 GetMethodImpl 辅助来源 155 字节、4 类字段和 2 个被调用方法身份。String.op_Equality 属于无完整 PE 边界的小函数，仅匹配身份，不列为完整 PE 体。Delegate.data=88、MulticastDelegate.delegates=104 与 DelegateData 的 method_name/target_type/curried_first_arg=16/24/32 独立匹配。

全量 `scripts/build.ps1`、10,954 项 Java（423 类）通过；既有 717 项 Python 全量与后续二十七批共 216 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,083 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-event-delegate-equality-verification.json`，封存 JAR 为 `build/rogue-event-delegate-equality-10954-certified.jar`，SHA `aa3c82027b66685d4cbe19ed330a6a0e48b33c086b93cca23646543481fe4576`。

本批针对明确 Callback 投影，不安装所有 CLR 委托类型层次、原生委托工厂、GetMethodImpl 内部或实际 World 注册；方法对象查询和元素虚调用保持明确绑定。EventManager 内部、完整销毁事件、属性撤回、宇宙/剧情/活动与官方客户端验收仍待完成。151 份协议快照和版本基线不变，宇宙 partial；未启动客户端/参考二进制，未修改运营数据、资源或配置。
