# 原始效果定义与回调选择

`RogueModifierDefinitions46` 保存九条经典共鸣能力中的五个局部标记定义和繁育角色能力中的四个全局瞄准定义。局部定义非 null 时直接返回；局部缺失或值为 null 时调用明确的全局读取接口，两处都缺失才通知诊断接口。全局资源的按需载入、依赖和缓存仍由明确读取接口负责，本类不代替整个 `AbilityJsonTable`。

五个局部标记的原件掩码均为零，未显式设置回调。这些记录仍有构造缺省，不能把它们解释成完整的空效果。Count 和 LifeTime 写入整数 -1，Stacking 为枚举 Unique / 1，MaxLayer 为 99，WorkingTurn 为 OwnerTurn / 1，LifeStepMoment 为 ModifierPhase2End / 2，ApplyBehaviorFlagBindEffects 为 true。本类保存这些值，尚不执行叠层、寿命和行为绑定。

LayerAddWhenStack 指向原生静态初始化中实际写入定点数零的对象：配置构造器和静态初始化器读取同一个全局地址 `0x93434a8`，静态偏移均为 `0x21608`。该缺省不是一层增量。这里核对具体加载/写入指令，不根据字段声明顺序推测静态槽含义。

## 四个繁育定义

原始完整能力记录 `Avatar_RogueBattleevent127_Ability.json` 共 8,594 字节，SHA 为 `86061735e9f35fa3d71a0b28e3bd6beb55df25ba283d99dc2e721a8ec853cd36`。四个定义保留五个回调和七个任务，数量包含两个条件任务及其子任务。

- AimTarget 在事件 57 `OnListenCharacterCreate` 使用 ParamEntity / TeamLight 条件，成功分支给 ParamEntity 附加 AimTarget_Sub；失败列表在原件中省略。
- AimTarget_Main 在事件 172 `OnAimAtTargetsRefresh_CL` 对 CurrentAimAtTarget 触发原始 `02` 特效。
- AimTarget_Main2 先保存事件 95 `OnBeforeSkillUse` 的 AllLightTeam 移除特效，再保存事件 172 的 CurrentAimAtTarget 触发特效。后者明确设置 MinMutexTime = float -1，前者不制造该字段。
- AimTarget_Sub 在事件 173 `OnListenAimAtTargetsRefresh_CL` 比较 ModifierOwnerEntity 与 CurrentAimAtTarget；成功列表显式为空，失败列表移除原始 `02` 特效。

上述条件和效果是原始描述。条件判断、实体别名求值、特效触发/清理及附加效果仍要求明确子任务工厂，不会默认条件成功或擅自改变生命值、伤害和属性。

## 回调编译及边界

`callbackInfo`、`callback` 和 `hasCallback` 按原件从前向后找首个事件匹配，不合并重复事件。不存在回调条目、条目存在但程序为 null、显式空程序分别保留；`hasCallback` 根据条目存在判断，而不根据任务数量判断。

有程序时可通过明确子任务工厂编译到既有 `RogueTaskSequence46`。空程序可编译为空序列；缺失程序保留 null。非空程序缺少工厂或工厂返回 null 时，项目入口拒绝，不把未知任务静默跳过。分支描述保留条件、成功/失败列表的省略与空值；原生条件求值器尚未接通。256 项以上程序/回调拒绝，非空文本、非负事件及不可变快照是明确项目边界。

冻结夹具 `src/test/resources/fixtures/rogue-modifier-definitions-4.6.json` 的 LF SHA 为 `4c72a3b8ef1b5dc4f95aa00618eb7273cdb5d94340df0fcd6e391229b0f51bbd`。七个完整 PE 方法共 1,755 字节，包含局部/全局查找、构造/静态初始化和三种回调查找；声明/继承字段、枚举值和能力配置实际偏移独立匹配。

三项真实断言先失败后通过，37 项 Java 回归覆盖九条局部目录、四个全局定义所有回调字段、优先查找、异常、重复事件、null/空区别、绑定工厂和序列执行；10 项 Python 重新核对完整本机字节和原始记录。Java 效果及条件执行采用明确测试工厂，不证明原生特效或实际实体效果。

尚未实现引用效果查找、全局懒加载缓存、附加资格、施加者继承、效果实例创建、叠层/寿命、实体事件监听、原生条件/特效、完整 AddModifier 运行时、在线战斗或真实客户端 UI。完整玩法继续保持 partial。

全量 `scripts/build.ps1`、9,136 项 Java（378 个测试类）和 544 项 Python 均通过；2,813 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-modifier-definitions-verification.json`，封存 JAR 为 `build/rogue-modifier-definitions-9136-certified.jar`，SHA `408769a8dcf88ce09ea95609e9259d75723a4cfb2ad84a88fbc51ad971011d31`。
