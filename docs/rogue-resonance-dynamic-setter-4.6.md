# 4.6 无吸附配置的动态单元写入

普通动态单元的显式投影可通过现有 `RogueResonanceVariables46.write` 写入，范围仅为标准原始数值与空吸附配置。写入替换值表达式，保留动态上下限；既有 1221 RoundToInt 和 2791/2855 写入任务可使用这些显式容器。真实战斗容器和原始调度仍未接入。

## 原生顺序

冻结夹具 `src/test/resources/fixtures/rogue-resonance-dynamic-setter-4.6.json` 保存三个完整 PE 方法共 2,239 字节：74798 动态单元 setter、74692 吸附 helper 和 74764 lookup 写入。方法身份与字节均核对，吸附配置字段 AdsorptionThresh 在 `+0x10`、FractionalDigit 在 `+0x18`。

lookup 先读取旧值 getter，再读取下限和上限，执行 Clamp 后调用单元 setter。setter 调用吸附 helper，然后创建固定 DynamicFloat，替换单元 `+0x40` 的旧表达式。吸附参数为空时，helper 在读取或修改原始数值前直接返回；本批只覆盖这一分支，不丢弃或忽略非空配置。

单元赋值后，lookup 检查通知回调。回调存在时再调用一次值 getter，把旧 getter 值和新 getter 值传给回调；没有回调时跳过第二次读取。动态值 getter 自身会重新裁剪，这使倒置范围下的通知值可能不同于 setter 写入词。发生通知错误仍保留已写入值，RoundToInt 的完成状态仅在 setter/通知正常返回后赋值；表达式任务既有入口完成状态规则保持。

## Java 范围

写入将值替换为不可变固定标量；动态上下限继续使用当前所属 lookup/AITag provider。标签栈顶不被写入覆盖。通知直接读取底层单元 getter，表达式依赖仍使用容器 provider；不能用直接读取同名 AITag 栈顶替代通知的新值。

每个 lookup 复制单元模板，避免一个容器的赋值更改另一容器的值。单元值使用 volatile 引用，读取一次快照；写入和 RoundToInt 共用底层固定容器的 monitor，通知可重入。视图不额外引入 monitor；两线程锁持有/重入的实际回归覆盖了此前容易出现的反向锁等待。并发模型为本项目的 Java 执行约束，不声称与客户端任务线程完全相同。

扩展数值、非空 PropertyAdsorptionConfig、动态上下限 setter、NK 属性/任务配置单元、AliasEvaluator、实际参数/属性/调度和游戏 UI 继续待完成。原始未知配置不进入这个有限接口，功能目录仍为 partial。

## 验证

新增 17 项 Java 回归，其中三项在关闭写入的旧实现上实际先断言失败；397 项共鸣定向测试通过。覆盖表达式被替换、动态上下限继续计算、旧值 getter 裁剪、倒置范围/标准两端、零值/不变通知、无效值/求值失败不修改、通知异常后的状态与值、重入更新、模板隔离、标签 provider/通知区别、原生 RoundToInt 的标签读取以及实际双线程等待/重入。

十项 Python 来源回归核对 setter 的吸附调用、空参数提前返回、固定值创建/替换、lookup 的四个接口槽、裁剪顺序、有无通知和第二次 getter。读取器只向工程 build 写来源证据，不执行 DLL 或安装配置。

全量 `scripts/build.ps1`、8,741 项 Java（364 个测试类）和 408 项 Python 均通过；2,728 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-dynamic-setter-verification.json`，封存 JAR 为 `build/rogue-resonance-dynamic-setter-8741-certified.jar`，SHA `ee9dd4dfa2abeab78f1e6a17c141c975b1163090a085a926969e090c098509ce`。

后续非空配置的标准数吸附另行补充，见 [吸附说明](rogue-resonance-adsorption-4.6.md)；本页封存范围为此前空配置写入阶段。
