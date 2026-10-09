# 4.6 NK 任务单元显式投影

`RogueResonanceTaskCell46` 覆盖 NK 单元的标准数 getter、缓存回退和忽略写入行为。`RogueResonanceCell46` 为普通 FJN 与 NK 单元提供封闭的公共接口，`RogueResonanceVariables46.withProjectedCells` 可在同一显式 lookup 使用两种单元。实际 Config Task 解码、别名任务上下文/缓存调度和战斗接线仍待完成。

## 原件边界

冻结夹具保存六个完整 PE 方法共 1,439 字节：74761 NK 工厂、74811 输出键初始化、74816/74818 上下限 getter、74820 值 getter 和 74821 setter。工厂类型槽 `0x9361588` 指向 NKDKGFGBPNL，兼容 provider 类型槽 `0x9361fc0` 指向 ENEIHJJPNPI。TaskConfig 在 `+0x40`，下限/上限在 `+0x10/+0x48`，吸附在 `+0x30`，provider 在 `+0x38`，缓存词在 `+0x58`。

输出字面量槽 `0x96fa600` 为 `_ResultValue`，由完整初始化器构造 StringHash 并写入 `0x9345cf0`；原始键为 `-1228589475`，不使用属性自身名字作为任务输出键。

provider 不兼容或 AliasEvaluator 不存在时，原生 getter 直接返回缓存，不重新计算上下限或吸附。有效时，先求 TaskConfig 的别名任务结果，再执行吸附、下限、上限、Clamp，最后写缓存。任何中途错误不提交本次缓存；已经发生的任务回调副作用不回滚。

NK setter 与 FJN setter 不同：它读取属性名字用于日志，不赋值、不替换任务、也不更新缓存。原生日志分支已核对；Java 有限接口保留忽略赋值的行为，不复刻客户端日志文本。lookup 写入仍先执行旧 getter 和范围裁剪；存在通知时再执行 getter，因此即使 setter 忽略输入，两次任务结果也可能不同。

## Java API 范围

调用方必须明确提供任务配置身份和 `AliasEvaluator` 回调。身份作为不透明的进程内对象保留，回调接收原身份和固定输出键；没有客户端字节解码、反射执行或自动安装脚本。此回调只是显式依赖接口，不是完整原生 AliasEvaluator 实现。

调用方传入数字 provider 表示已经选择的兼容 lookup 投影；缺少 provider 或回调返回缓存。普通 FJN 单元忽略别名回调，维持已有固定/后缀取值与 setter 行为。NK 先吸附再裁剪；动态上下限使用所属 lookup/AITag 数字 provider。变量容器只复用底层固定锁，写入通知调用实际单元 getter，AITag 栈顶不替代通知值。

缓存使用 volatile long，每个 lookup 复制模板的当前缓存与范围。有限循环/深度限制沿用本项目的 32 层读取保护，不声称复刻原生别名的全局 64 层计数。上下限 setter、扩展运算和真实属性加载仍未开放。

## 验证

21 项 Java 回归通过，其中任务求值/缓存、吸附和 lookup 写入通知三项先在只返回缓存的接口基线上实际断言失败；441 项共鸣定向回归通过。覆盖任务/吸附/上下限顺序、缺少绑定不重裁缓存、每次 getter 重新求值、任务/吸附/上限失败、任务副作用、倒置范围、忽略 setter、模板隔离、混合普通单元、无通知跳过第二 getter、RoundToInt 完成但值仍由任务决定、AITag provider/通知、快照、循环后的恢复和扩展结果拒绝。

十项 Python 检查核对六个完整方法和字段/类型槽、工厂赋值、缓存回退、任务到裁剪顺序、缓存提交位置、范围字段、setter 丢弃对象引用后的日志路径、固定结果名/哈希及只写 build 的读取边界。读取器不执行 DLL、不安装配置或修改客户端。

全量 `scripts/build.ps1`、8,785 项 Java（366 个测试类）和 428 项 Python 均通过；2,741 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-task-cell-verification.json`，封存 JAR 为 `build/rogue-resonance-task-cell-8785-certified.jar`，SHA `f64e0a39318752bba3d5dcd1d4a08771af7f05c193d07b88962ebc4c35e5cdec`。

完整别名任务序列、实际 Config Task、参数来源、CurrentSP 属性和事件、原始能力分支及客户端 UI 验收继续待完成。有限回调测试不等于完整属性/任务玩法，功能目录保持 partial。
