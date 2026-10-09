# 4.6 叠层弱点、附加队伍点数与装饰回调撤销

新增明确的叠层撤销状态，与RogueModifierStackEvent46共享替换对象和附加队伍点数列表。弱点项保留能力组件、操作类型和栈位置，点数项保留队伍组件和注册标识；整数按原始有符号32位传递，不把注册标识当作点数直接扣减。装饰回调使用同一个修改器身份撤销。

弱点列表缺失立即失败，空列表正常清空而不读取世界。每项能力组件和CharacterWeakness要求存在，实体允许明确为空，再调用匹配的弱点撤销入口。附加队伍点数和装饰回调列表初始缺失跳过；非空项的队伍组件为空也跳过，组件存在则BoostPointModule要求存在。三种列表在读取长度后重新取当前列表和元素，回调后继续读取当前列表，索引不因列表替换而重置；追加、替换、清空和缺失影响后续处理。

成功完成逐项撤销后清空当前列表。发生异常时，不清空未成功完成的列表、不恢复已经执行的撤销前缀。装饰列表清空后，还会重新读取当前容器并整理容量；清空期间替换容器时整理新容器，替换为空时失败。255项保护、编译后的记录类型和明确ArrayList装饰容器属于工程适配，不冒称原生PoolablePooledList工厂、池回收或一般容器行为。

来源核对三个完整PE方法共668字节、七类字段和枚举、三个实际组件撤销方法身份，以及三个原生泛型列表的元素类型。弱点列表480为PoolablePooledList<HPLDJOLCDOE>，附加点数列表504为PoolablePooledList<DGOAGABJAPC>，装饰列表224为PoolablePooledList<BPPIINCJEPP>。弱点项第一字段为TurnBasedAbilityComponent，Weakness在组件544偏移；队伍项为TeamDataComponent，BoostPointModule在32偏移。泛型共享的三个列表取值方法未定位到注册身份，保持关闭，没有根据相近地址给方法命名。

新增41项Java回归和8项Python来源检查。覆盖缺失/空列表差异、组件要求、整数原值、列表重读与变化、失败前缀、清空后整理容器、有限边界，以及实际OnStack参数帧、点数暂存和同一修改器身份的联动。新实现不冒称既有生产实现的先失败缺陷。

全量 `scripts/build.ps1`、10,043 项 Java（403 个测试类）通过；既有 717 项 Python 全量检查与后续七批共 56 项独立检查通过（未重复运行完整 Python 集合，既有脚本文件哈希未变）；2,963 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-modifier-stacked-effects-verification.json`，封存 JAR 为 `build/rogue-modifier-stacked-effects-10043-certified.jar`，SHA `cccbc9333f649b1ceb6f21dd0e4eec73b92360b3fb8d3ea0ab2ccba5c2969cc4`。

CharacterWeakness/BoostPointModule撤销内部处理、原生池与取值方法、完整属性撤销、完整Unstack、世界安装、修改器实例、IFix和官方客户端验收仍待完成，`rogue`保持`partial`。未修改151份协议快照、客户端或运营资源，未启动参考二进制；本批不涉及认证、存储、启动或HTTP，未新增网络验收。
