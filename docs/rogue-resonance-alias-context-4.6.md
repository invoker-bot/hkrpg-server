# 4.6 临时别名上下文绑定

`RogueResonanceAliasContext46` 为已经选定的 lookup 投影提供任务回调。逐键读取先选择 lookup，保存原别名求值器，临时绑定当前上下文，调用单元 getter，最后恢复原求值器。正常返回、RuntimeException 和 Error 均执行恢复。任务型 NK 单元因此可以使用当前上下文的数值来源；普通 FJN 和固定单元不执行别名任务。

## 原件与范围

夹具冻结三个完整 PE 方法共 811 字节：508325 `GetDynamicValueByKey`、509825 `RPG.GameCore.IAliasEvaluator.EvaluateAlias` 和 74820 NK getter。lookup 的 `<AliasEvaluator>k__BackingField` 在 `+0x20`，类型为 GGMPKKNCLHF（10849），与数字 provider PFMDDEGLECM（10805）不同。

508325 保存原 `+0x20`，写当前 TaskContext，然后按虚槽 7 读取原始数字键；正常返回和异常清理都恢复保存的指针。509825 将原任务配置身份、当前上下文和输出键转交别名执行 helper。NK getter 从所属 lookup 取临时别名求值器，仍使用固定 `_ResultValue` 输出键。

Java 对应接口只使用明确传入的进程内配置身份和回调，不解码客户端 Config Task。`RogueResonanceLookup46.read/readInScope`、前段/完整作用域投影和实体投影读取共用绑定入口。无别名回调的上下文临时绑定 null，因此 NK 返回现有缓存，不借用该 lookup 的默认回调。直接调用 lookup 读取仍使用其默认回调。显式上下文可用同一物理 lookup，回调收到实际包装上下文，而不是容器或输出值的副本。

已有 `read` 保留严格单元读取语义；`readInScope` 与实体投影采用原生缺失键返回零及 AITag 栈顶读取。AITag 对绑定的转发到达基础动态 lookup，普通动态值的数字 provider 仍从所属 lookup 解析。没有变更 lookup 选择次序、写入规则或 RoundToInt 的任务完成状态。

## 共享与异常

临时绑定与动态 getter 使用同一个基础固定 lookup 锁，避免两个线程共用 lookup 时串用回调。锁可重入，嵌套上下文正常或异常结束后恢复外层绑定；最外层恢复默认绑定。普通读取、任务型读取、写入通知和回调内部读取共用该锁。这个 Java 并发隔离是服务端实现保障，不声称原生客户端也使用同样的线程锁。

任务回调仍是可信调用方提供的同步逻辑。动态单元沿用 32 层/同键循环保护；不是原生别名 helper 的全局 64 层限制。回调已经产生的外部副作用不回滚，NK 只有吸附与范围计算全部成功才更新缓存。

原生 helper 的配置身份缓存、100 项 LRU、TaskContext CopyTo、临时结果栈和任务序列调度还未接入。技能原始参数、CurrentSP/事件、战斗对象、完整能力分支和官方客户端 UI 继续待验收。此项完成只覆盖显式上下文绑定与恢复，功能状态仍为 partial。

## 验证

18 项 Java 测试和 459 项共鸣定向测试通过。其中当前上下文覆盖默认回调、异常恢复、无别名上下文不借用默认回调三项先实际断言失败。其余覆盖身份/输出键、明确作用域、嵌套正常/异常恢复、非法结果后的缓存、循环清理、AITag、缺失选择、普通单元、实体读取、写入通知、Error 和并发上下文/直接读取隔离。

八项 Python 检查核对三个完整方法、类型/字段、绑定顺序、正常/异常恢复、原始键/虚槽、上下文别名适配入口，以及仅输出到 build 的边界。读取器不执行 DLL、不安装脚本或修改客户端。

全量 `scripts/build.ps1`、8,803 项 Java（367 个测试类）和 436 项 Python 均通过；2,747 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-alias-context-verification.json`，封存 JAR 为 `build/rogue-resonance-alias-context-8803-certified.jar`，SHA `81a2ae610211694c4fb5eebe3c1a150c6eb35b9b924c6a793f64521650844c1f`。
