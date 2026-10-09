后续 [施加者继承](rogue-modifier-caster-4.6.md) 已补入普通三模式与内置中转查找/创建顺序；下面保留本批封存时的资格验证范围。

# 4.6 修改器名称选择与有限目标资格

`RogueModifierSelection46` 已接入现有 `RogueAddModifierEntry46` 的显式上下文和工厂。它执行原始名称覆盖、定义名称选择、存活/濒死资格与战斗事件的敌方限制。后续目标限制、施加者继承、实例创建、叠层/寿命、回调监听与实际效果仍由调用方明确提供，未接入在线世界，宇宙保持 partial。

## 原件范围

固定本机国服 4.6 的 GameAssembly、global-metadata 与 startup-metadata 三个文件哈希。证据 `src/test/resources/fixtures/rogue-modifier-selection-4.6.json` 的 LF SHA 为 `05972faf59f0ce11dd1b3b8553c5cfa0b1400c05663df1e8e4c54cccc57ed569`。八个完整 PE 方法共 8,710 字节，包含整个 AddModifier.OnTaskBegin、目标覆盖查找、IsAliveOrLimbo、IsAllowAddModifier、ContainsBehaviorFlag、AddModifierAlias、字符串相等辅助和额外限制方法。额外限制的方法原件已核对，但其全部内部逻辑没有在本批实现。

覆盖表实际位于 TurnBasedAbilityComponent.ModifierOverrideMapping 的偏移 208，泛型为 Dictionary<string, OKOCPOFNODF>；startup 记录、两项真实参数及覆盖值的字符串偏移 120/布尔偏移 136独立核对。敌方限制所用 GetComponent MethodSpec 参数确认为 BattleEventDataComponent 56888，其 MuteDarkTeamModifierAdd 偏移为 378；未把名称覆盖字符串当成施加者实体。

## 名称和资格顺序

每个目标先查以原始字面名称为键的覆盖值。替换名称缺失才回退原始名称；显式空字符串保留。覆盖布尔值为 true 时保留原始参考名称，否则参考名称等于实例名称。配置中的 ReferenceModifierName 非空引用决定查找名称；缺失引用使用实例名称。三种名称分别传递，不合并。

定义查找发生在目标资格检查之前。目标缺失返回 false；存活状态只有 1–4 通过，其余状态不继续读取类型。非类型 12 目标直接通过。类型 12 的组件缺失或没有设置 MuteDarkTeamModifierAdd 时通过；只有设置该标志且来源队伍为 2 时才要求定义存在并包含行为标志 84。行为标志是数组成员检查，不能按整数位掩码解释。实际实体字段、组件查找和行为数组读取仍须明确绑定。

资格通过后，查找名称与实例名称按字符串内容不同才调用 AddModifierAlias；别名登记正常返回后才进入后续目标限制。允许资格检查通过但定义缺失的普通目标会在登记之后抛出异常，与原始后续限制首先读取配置的顺序对应。后续限制返回 false 时不施加；它返回 true 才调用明确绑定的实例处理。

完整有限入口仍先判断来源魅惑、一次收集目标，再逐项执行。资格不符的目标不计为异常；非空目标集合在逐项正常返回后继续通知。异常保留已经登记的别名/已经发生的施加动作，不执行最终通知，不额外重写任务原状态。重复开始重新查覆盖和资格，重置只遵循已有入口生命周期。

## 验证

三项行为断言先失败再修复：敌方限制缺少标志时拒绝、覆盖保留原始参考名称、别名早于后续限制且拒绝不施加。29 项 Java 覆盖边界状态、缺失组件/来源/定义、实时覆盖、内容相等、显式空字符串、异常时保留动作、多个目标及重复生命周期；10 项 Python 核对完整字节、字段布局、泛型参数、原始六条来源任务与原生分支顺序。

全量 `scripts/build.ps1`、9,235 项 Java（381 个测试类）和 576 项 Python 均通过；2,831 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-modifier-selection-verification.json`，封存 JAR 为 `build/rogue-modifier-selection-9235-certified.jar`，SHA `0e54e09f3aa3b28bbe92911c3c7abd829f571d5cf634f6513df6d7bdc89d0b5e`。

本批没有增加官方客户端界面或网络验收，也没有修改协议、鉴权、存储、启动、HTTP、客户端文件或运营数据。协议基线仍为 LunarCore lite 4.2，4.6 为实验兼容层。该结果不代表完整宇宙、剧情、日常或活动完成。
