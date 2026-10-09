# 4.6 普通动态数值单元读取

`RogueResonanceDynamicCell46` 与 `RogueResonanceVariables46.withDynamicCells` 提供普通 FJN 单元的有限标准输入读取投影。固定值或已核对的 18 操作码表达式可作为值、下限、上限，变量由所属 lookup 逐次读取。该投影接入现有显式作用域容器，但未绑定真实战斗对象或原始任务调度。

## 原生来源

冻结夹具 `src/test/resources/fixtures/rogue-resonance-dynamic-cell-4.6.json` 保存十个完整 PE 方法共 7,650 字节，包括动态单元创建/通用求值/值与上下限 getter、本地 lookup 读取及虚方法转交、完整 VM 和 FixPoint 初始化。另有 IsDynamic 的九字节只读片段；无 PE 范围，不计作完整方法。全部方法身份、字节、字段偏移和创建点类型槽与已固定哈希的本机 4.6 文件核对。

原生创建方法 74760 将值放入单元 `+0x40`、下限放入 `+0x18`、上限放入 `+0x10`，provider `+0x38` 指向所属 lookup。DynamicFloat 的 `fixedValue` 在 `+0x10`；`postfixExpr` 在 `+0x18`。原生通过 postfix 的首个指针是否为空选择固定值或 VM。项目对应 `Scalar.fromPostfix`：代码参数为空时读取固定词；代码存在时忽略未使用的固定词，编译后按当前 provider 求值。空或未知程序在项目有限编译边界内拒绝。

值 getter 74797 按值、下限、上限的顺序求值，随后调用 Clamp。值缺失读取零；上下限缺失加载已核对的 `-99999999999` 与 `99999999999` 扩展编码。所有标准数都严格位于这两个范围内，项目保留标准原始词，不把扩展上下限当作普通有符号整数参与裁剪。显式标准上下限保持下限优先，即使范围倒置。上限会在截断前计算，因此不能跳过可能失败的上限表达式。

## 容器行为与边界

普通动态单元的变量从所属容器读取，不自动合并其它上下文帧。本地缺失变量仍遵循原生零值路径；严格直接读取不存在的键继续拒绝，作用域选择缺失也继续拒绝。动态值不缓存，底层固定值写入后再次读取使用当前值。AITag 投影作为同一容器的 provider：表达式依赖的变量优先使用栈顶，空栈或缺失标签回退到本地单元。

该阶段动态单元为只读投影。写入动态键明确拒绝，不静默覆盖底层同名固定值；普通固定键写入保留既有通知逻辑。原生 setter 含 PropertyAdsorptionConfig 处理，尚未实现；NK 属性/任务配置单元、AliasEvaluator 路径、真实 provider/参数来源及活对象绑定均未完成。接口名称 `readNativeFixed` 为兼容现有调用保留，目前也能读取显式添加的普通动态单元。

合并后的容器最多 255 个键，递归读取最多 32 个动态单元，循环依赖明确拒绝。限制为项目执行边界，原生没有在本批证据中证明这些上限。线程间读取栈独立，失败在 finally 释放读取记录；它不证明真实客户端的线程或任务调度规则。

## 验证范围

新增 27 项 Java 回归：四项基本读取在实现前实际断言失败，另有 AITag provider 传递错误实际先失败后修复。测试覆盖 4,096 组独立 BigInteger 截断预期、输入复制、固定/空/postfix、两端标准词、全部上下限读取顺序与错误、倒置范围、本地缺失/作用域拒绝、固定值更新、嵌套/循环/深度、标签 provider、bounds/snapshot、写入拒绝、合并键数与并发读取恢复。

十二项 Python 来源回归核对完整方法和片段、DynamicFloat/单元/lookup 字段、创建点类型和 provider、求值顺序、上下限偏移与初始化、虚方法转交及输出限制。读取器只向工程 build 目录写证据，不运行 DLL、不安装配置。

全量 `scripts/build.ps1`、8,724 项 Java（363 个测试类）和 398 项 Python 均通过；2,723 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-dynamic-cell-verification.json`，封存 JAR 为 `build/rogue-resonance-dynamic-cell-8724-certified.jar`，SHA `a39b50f8d700760817e45ccc996f0bb21a12c7f3a552e8ab5bb135cbf73130a2`。

该批不提供新的协议字段、命令号或客户端版本声明；原始能力分支、真实技能扣费/效果和游戏 UI 尚未验收。宇宙功能状态继续保持 partial。

后续空吸附配置标准 setter 已另行补充，见 [动态写入说明](rogue-resonance-dynamic-setter-4.6.md)；本页记录先前读取阶段的封存验证边界。
