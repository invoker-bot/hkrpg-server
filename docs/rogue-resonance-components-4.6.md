# 4.6 共鸣实体组件类型与 AITag 栈顶读取

这一批确定两个既有选择调用点传入的组件类型，并补充明确传入的标签值栈投影。它没有获取活的 GameEntity、安装客户端配置或在真实战斗中执行原始任务。原有固定值来源和 151 份协议快照保持不变。

## 组件参数的来源

调用点 `0xe4c344b`、`0xe4c34b7` 分别读取方法槽 `0x944fdb8`、`0x945a398`。固定客户端的元数据使用种类 6 将它们映射为 MethodSpec 21531、24359；定义均为 GameEntity.GetComponent（502972），参数实例化记录分别指向 FODPNNIJCJI（57562）和 NKIKDLEONLC（57764）。类型/字符串表中数字相同的 destination 不属于方法表，读取器严格按 usage kind 区分。

原生元数据初始化函数（完整 1,393 字节）、MethodSpec 实例化函数（完整 848 字节）和注册安装指令（109 字节片段）共同核对参数表地址和布局。参数表从已注册的 `0x4931658+0x38` 指向文件中的 `0x4457ce0`，实例化记录中的类型指针也有实际文件字节。记录中的 +4/+8 保留为位置名称，未擅自沿用其他 IL2CPP 版本的结构名称。

FODPNNIJCJI 的继承链包含 JBFFCBPMKEF；其 `KFKGOMIMNLD` 位于 +0x58，类型为 ENEIHJJPNPI。NKIKDLEONLC 的 `HLCBMLFOJKH` 位于 +0x18，类型为 BAGIHIEADIL。后者继承 ENEIHJJPNPI，并覆写读取方法 MPIBNJBBOKM（524424），槽号同为 7。元数据中的泛型定义没有直接编译方法地址，调用处的共享泛型函数体身份仍未独立闭合。

## AITag 读取与写入

覆写方法的完整 PE 范围为 234 字节：先查 +0x28 的标签表；键存在且对应列表非空时，读取列表最后一个记录的 64 位值。键不存在或列表为空时，尾调用基础固定值读取方法（74755），沿用此前核对的缺失键零值。

`RogueResonanceVariables46.withAiTagProjection` 接受基础固定值容器和明确的标准数标签值列表。列表及键集合复制后只读，最多 255 个键、每键 255 个值；扩展编码、空对象、嵌套投影或超过有限边界的输入拒绝。原生读取入口使用最后一项，基础严格读取、contains、bounds 和 snapshot 仍反映基础单元。

原始 1221 舍入任务在上下文中通过该读取入口得到栈顶，再向基础固定单元写入；它不会替换或弹出标签栈顶。2791 的目标实体写入同样保留基础单元的 clamp 和 observer 行为。视图不引入第二个锁，基础读写与舍入使用同一基础容器监视器，允许 observer 读取视图。没有实现标签身份、push/pop、动态 provider 或真实标签来源。

## 验证范围

新增 12 项 Java 回归，其中五项在旧实现上先发生断言失败。覆盖栈顶优先、空栈/缺失键回退、缺失基础单元不创建、舍入后栈顶保留、基础范围/通知、输入复制、有限边界和 observer 重入。277 项共鸣 Java 定向测试通过。新增十项 Python 回归核对八个完整托管 PE 方法（11,362 字节）、三个完整原生运行时函数（3,574 字节）、225 字节 getter/provider/注册安装片段，以及四项原始变量声明和六项任务输入。

全量 `scripts/build.ps1`、8,621 项 Java（358 个测试类）和 342 项 Python 均通过；2,697 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-components-verification.json`，封存 JAR 为 `build/rogue-resonance-components-8621-certified.jar`，SHA `34f14efda866d17e981308cec8f6c35de076cdc29d1f31ac30ba9012f15f675c`。

读取器 `scripts/read-rogue-resonance-components.py` 的输出限定在工程 `build/`。两个指定调用点的组件类型参数已静态解析；真实对象/组件获取、来源标签栈、参数值、共享泛型函数体、数值 provider、战斗分支执行和完整效果仍未接入或验收。官方客户端入口与操作流程未验证，功能状态仍为 partial。
