# 共鸣作用域查找后段的固定容器投影

`RogueResonanceScopedLookup46` 补入明确角色投影中的查找后段，与前段共同支持作用域 0、1、2、3、4、5、8、9。`RogueResonanceLookup46` 供三种变量任务共享；既有前段 API 的签名保留，通过重载委托新接口。调用方必须预先取得可用角色容器和实体身份，尚未建立真实 TaskContext 绑定，功能继续为 `partial`。

## 角色选择与原生依据

默认作用域先使用已实现的回调临时、参数栈、模板、Modifier 前段。Ability 存在时先查 Ability 固定变量，再查其 Caster custom data；Ability 不存在时才先调用 Owner custom data 的投影。之后查 Summoner，再在 Owner 与 Summoner 身份不同时查 Owner 实体 custom data，最后查可用的全局 alias 容器。这里的 Owner custom data 主投影可能来自 OwnerGraph、LittleGameEntity 或 OwnerEntity，不能等同于最后一个 Owner 实体容器。

显式 Ability、Caster、Owner 作用域分别只取对应容器；Caster 作用域需要 Ability 存在。显式 Summoner 作用域仍可能回退到不同身份的 Owner 实体容器，但不查全局 alias。实体身份按引用比较，不能用 `equals` 或比较容器内容代替。没有自动创建缺失 cell，未支持分支或缺失变量拒绝。

冻结夹具保存选择器、VM、按键读取、lookup 固定值读取及 Owner custom data 获取五个完整托管方法对应的 PE 范围，共 10,204 字节；另存一个完整原生 SetupMethods 函数 1,333 字节。逐键 provider 与两个 getter 的三段指令共 116 字节，明确不称为完整方法。

SetupMethods 的原始 SSE 换序和异或常量用于解码方法定义槽号。OwnerEntity getter 对应槽 10、OwnerAbilityInstance getter 对应槽 12、lookup 固定值读取对应槽 7；它们与选择器/读取器中的类内偏移 `0x110`、`0x120`、`0xf8` 对应。ToString 槽 3 作为额外交叉核对。上下文跳表、Ability 存在分支、实体引用比较及非默认作用域的全局查找门槛均按原件验证。

## 尚未覆盖的对象获取

这些投影不是实际 GameWorld、Ability 实例或 GameEntity 的 Java 重建。原生获取路径的泛型组件类型参数、图/小游戏对象转换、可用全局容器和当前回调状态尚未绑定。原始四个变量在测试中明确投影到 Ability 容器，参数放到隔离的额外帧；这不证明实际当前战斗的参数数值或角色绑定。

AITag 作用域的原生路径直接返回容器而不先检查键；该路径的缺失值表示尚未确认，因此作用域 7 未开放。带显式实体的另一选择器重载及 TargetEntity 作用域 6 也未接通。alias evaluator、动态 cell provider、真实属性、能力条件/调度、扣费及完整效果仍未实现。

## 验证

新增 15 项 Java 回归，其中七个断言先失败后通过；141 项相关定向回归通过。覆盖 Ability 有无带来的不同顺序、显式作用域隔离、Summoner 回退、引用身份、缺失/畸形投影拒绝，以及三种任务在隔离 Ability 容器中的属性→表达式→舍入→floor 组合与通知/生命周期顺序。

新增九项 Python 回归通过，核对完整 PE 范围、SetupMethods/槽常量与元数据、getter/读取器身份、原生角色分支、实体比较、全局门槛、原始四个声明和六处任务及篡改拒绝。全量 `scripts/build.ps1`、8,589 项 Java（356 个测试类）和 322 项 Python 均通过；2,687 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-scoped-lookup-verification.json`，封存 JAR 为 `build/rogue-resonance-scoped-lookup-8589-certified.jar`，SHA `1ac01c9864c59e27733c582a43902829307313d48548e12e64c5827122a1153a`。

本批未修改生成协议、命令号、版本常量、运营配置、数据库或客户端，未增加网络验收，未把这些固定容器投影标为官方客户端完整能力执行。
