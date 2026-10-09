# 4.6 修改器的有限施加者继承

`RogueModifierCaster46` 补入普通 Inherit/CasterSelf/TargetSelf 选择，保留上下文重复读取、类型拒绝和内置中转能力的查找后创建顺序。六条已有原始效果任务省略 InheritCaster，工厂通过 `RogueModifierSelection46` 的资格入口使用原始模式 0。实际组件、能力实例创建与修改器处理仍需调用方明确绑定；没有建立在线实体工厂或实现完整修改器效果。

## 原件

证据 `src/test/resources/fixtures/rogue-modifier-caster-4.6.json` 的 LF SHA 为 `01d8cd6415fdb87676d5ca71494cb6666abecc5012dfad49c6d670e00ba851a0`。固定本机 GameAssembly、global-metadata 与 startup-metadata 哈希，六个完整方法共 8,490 字节；另一个完整上下文读取辅助共 461 字节。能力查找/创建方法的完整原件也包含在其中，实际实例创建仍由明确绑定的工厂执行，没有把这些原件统计成已实现的所有创建流程。

ModifierCasterType 的实际枚举常量为 Inherit 0、CasterSelf 1、TargetSelf 2。TaskContext 的 OwnerAbilityInstance/OwnerEntity 虚槽为 12/10；预期能力类为 FABMDCOFHCG 55823。实例继承链的 AbilityComponent 字段偏移为 104，组件的 OwnerRef 偏移为 16。配置字段 InheritCaster 偏移为 128。

本机初始化方法 508748 创建 TurnBasedAbilityConfig，并只赋值 Name。字符串槽 0x9706340 的字面索引 9642 明确为 `INTERNAL_ModifierCasterTransit`，随后缓存到静态区域偏移 0x11bd8。Java 使用不可变的同名配置投影，未猜测一个外部 ConfigAbility 文件来替代它。

## 普通顺序

Inherit 先读取 OwnerAbilityInstance；存在时要求它属于已核对的能力类，否则抛 ClassCastException，不降级到目标或中转实例。它缺失时进入原始上下文辅助：再次读取 OwnerAbilityInstance；仍缺失才读 OwnerEntity。第二次读取出现实例时，第三次读取的实际实例决定所属实体，不能复用第二次结果；第三次变为缺失时抛异常。

CasterSelf 使用上下文 OwnerEntity，而非先前入口得到的 SourceEntity/Instigator。TargetSelf 使用当前目标；目标缺失返回 null。其他模式返回 null，不改成默认模式。有限接口仍要求明确绑定的 World，这是项目的绑定约束。

获得实体后读取其 TurnBasedAbilityComponent，再按 `INTERNAL_ModifierCasterTransit` 查找已有能力实例。查找返回存在实例时核对类型并复用；缺失才调用明确绑定的 AddAbilityWithInheritTarget，配置为原始 Name 投影，继承目标与选项均为 null。选择器没有额外实例缓存，每个目标和重复调用重新读取实际存储。

资格或后续限制拒绝时不读取施加者。选择正常返回且非空后，默认接口将能力身份连同已解析的名称/定义传给明确绑定的实际处理；异常保留此前别名与动作，不做额外回滚，不执行最终通知。需要创建实际能力的工厂、组件注册、完整任务/修改器实例、叠层/寿命、监听与特效仍待完成。

## 验证

三项行为断言先失败再修复：不兼容的继承能力拒绝、CasterSelf 使用上下文所有者、创建之前必须先查找。26 项 Java 覆盖三种模式、未知/缺失输入、重复读取期间的变化、类型与工厂异常、实时实例存储、资格拒绝、别名保留、多个目标及组合入口。10 项 Python 核对方法/辅助字节、真实字段和枚举、虚槽、字符串/类型槽、内置初始化、查找/创建顺序与六条来源任务。

全量 `scripts/build.ps1`、9,261 项 Java（382 个测试类）和 586 项 Python 均通过；2,837 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-modifier-caster-verification.json`，封存 JAR 为 `build/rogue-modifier-caster-9261-certified.jar`，SHA `a065c52fd1ca1107e94e8617bfa2713b000c746038c2ee300ec76b4e624fa487`。

没有新增官方客户端界面或网络验收，未改协议、鉴权、存储、启动、HTTP、客户端文件或运营数据。LunarCore lite 4.2 协议基线及 4.6 实验兼容边界保持；完整宇宙、剧情、日常和活动仍未完成，功能状态保持 partial。
