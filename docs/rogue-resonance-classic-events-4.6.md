# 4.6 九条经典共鸣参数目录

Java 21 的 `RogueResonanceClassicEvents46` 补入全部九条经典事件的原始参数定义：41001 至 41009、155 项输出绑定和 154 项源默认声明。`find(eventId)` 只返回对应的经典定义；其他事件编号不会借用相同客户端配置。`Definition.newTemplate()` 每次建立独立标准数源单元，`evaluate(context)` 依照原始绑定顺序从调用方明确提供的上下文读取值。

此前七条事件转交与完整 76 条共享能力表的证明保持原范围，见[事件转交说明](rogue-resonance-event-params-4.6.md)。本批新补两条：

| 事件 | 原始能力 | 原始父记录 | 选中能力范围 | 输出绑定 |
| --- | --- | --- | --- | ---: |
| 41008 | `StageAbility_612720` | `Config/ConfigAbility/Level/Level_RogueBuff_Ability_DLC1.json` | `[11167,12128)`，961 字节 | 19 |
| 41009 | `StageAbility_612820` | `Config/ConfigAbility/Level/Level_RogueBuff_Ability_Erudition.json` | `[2,389)`，387 字节 | 9 |

两份完整布局各 36 条，均完整读取。完整父记录与布局原件合计 40,044 字节，路径、索引键、数据块、范围和 SHA 重新核对；两条选中能力合计 1,348 字节，由原始布局名称、UInt16 类型 1 和相邻偏移定位后完整消费。额外两个父表的其余能力尚未完整解码，不能据此声称额外父表全部读取。

## 原始配置差异

41008 包含 18 个单变量表达式转交和固定 `Egg_MaxLayer_612720 = 0`。固定值不查询不存在的源变量。源 `FirstBattle_Flag` 的原始默认字为 `8589934592`。原件另有 `AddModifier` 任务 50，目标 `Caster`，修饰器 `MAvatar_RogueBattleevent127_AimTarget`；目录保留其源证据，但没有执行该修饰器任务。

41009 的三个查询键为 `ADF_612821_1`、`ADF_612822_1`、`ADF_612823_1`。其事件输出还包含 `ADF_612822_2`、`ADF_612822_3`，两个源声明原本为零，不能因为某个祝福有多个参数就自行补入额外查询。其 `FirstBattle_Flag` 默认字同样为 `8589934592`。

155 项绑定中，154 项保持原始 `010011` 后缀程序及唯一源键；一项保持原始固定零。输出名称保持源配置拼写，不增加隐式 `#` 前缀剥离。传入上下文缺少值则失败，不读取编译目录中的默认值作为隐式回退；默认模板必须由调用方明确建立。

## Java 与原件验证

三项真正断言失败的 RED 回归后实现目录。30 项 Java 回归逐项比较九条定义的名字、154 项声明、155 项绑定、原始求值键和输出顺序；每条定义均使用[原始祝福表](rogue-maze-buff-table-4.6.md)的精确定点参数验证显式转交，还验证独立模板、不可变定义/结果、缺失上下文拒绝和非经典事件排除。

10 项 Python 核对客户端原件、额外两份完整布局、选中能力的完整消费、固定卵上限、原始查询列表、额外修饰器任务，以及全部九条参数定义。事件创建参数准备的两个完整 PE 方法、1,567 字节复用此前已核对证据；共享父能力的配置字段/注册/读取器及布局读取器再次核对。本批没有将额外父表的其他任务提升为已实现。

全量 `scripts/build.ps1`、8,905 项 Java（371 个测试类）和 474 项 Python 均通过；2,771 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-classic-events-verification.json`，封存 JAR 为 `build/rogue-resonance-classic-events-8905-certified.jar`，SHA `e1a07554505263adef8f072c7f4656dff7b69fd8b739f3beb0fa7b3762710a4b`。

## 继续补齐的边界

目录和显式转交是原始数据与 Java 接口的补齐。后续批次已依据完整本机实现，将 `SetDynamicValueByMazeBuffConfig` 的缺省等级 1、原始索引与九条 74 项查询补入[显式查询层](rogue-resonance-maze-query-4.6.md)。持有祝福应用、事件创建、回调、修饰器执行以及真实战斗上下文仍待接入。九条定义完整不代表九条玩法完整，`clientGameplayVerified`、`abilityExecutionVerified` 和 `completeResonanceRuntimeImplemented` 保持 false。协议、命令号、游戏版本与运营资源不变。
