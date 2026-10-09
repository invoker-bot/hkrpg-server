# 共鸣函数身份及有限定点数求值

592 个已恢复表达式中的两处函数调用已匹配为 `floor`。Java 核心新增原始定点数回调与有限程序求值器；两条原始程序在显式测试数值上下文下能算出结果。战斗变量来源、能力调度和效果执行仍未接入，这一步不代表完整共鸣技能或游戏画面已完成。

## 原始身份与数值规则

原生环境初始化方法 74376 的完整 PE 范围为 `1ce0a560..1ce0ac00`，共 1,696 字节。十五次注册调用逐一核对字符串槽和 MethodInfo 槽；委托的公共调用桩不是回调方法。注册名称为 floor、round、int、min、max、ceil、mod、clamp01、abs、clamp、leftshift、rightshift、and、or、xor。注册函数 70468 使用交替的 32 位累加器生成有符号键，原件 floor 的键为 1776456860，回调为 74380／`1ce0ac00`。当前只开放该原件使用的单参数调用，其他已注册名称和未知键均拒绝。

floor 回调的完整 179 字节已核对。标准编码以 `2^33` 表示一，使用原始整数掩码向下取整；扩展编码以最低位标记，使用 26 位整体移位。负数分数不能向零截断。扩展结果按原生规则转换回标准编码，并在两端饱和。上下限来自 FixPoint 完整初始化方法 68417 的立即数及 16 字节原始数据，不读取 DLL 初始数据段中尚未初始化的杂值。Java 和 Python 全程使用整数，未经过 double。

函数身份与 floor 补充证据共六份完整 PE 方法、3,164 字节。固定证据为 `src/test/resources/fixtures/rogue-resonance-functions-4.6.json`，规范 LF SHA256 为 `4aee3cef1047c04acd8b7daf5b3e61a3232b3cc796566e9556b958fa5454ad6d`。

## 两条原始程序的有限求值

两条调用均来自 `Avatar_RogueBattleevent126_Ability.json` 的第三条能力，分别有 13 和 10 个程序字节。第一条为 `floor((变量 + 100) / 变量)`，第二条为 `floor(变量 / 变量)`；原始键、常量和顺序保留，没有给变量臆造名称或从玩家状态取值。

有限求值器接受 Constant、GetGlobal、Add、Divide、Call、Return 六种操作，在读取上下文前完整编译程序，限制程序和表长度、栈与深度，并保存不可变计划。缺失变量、错误函数、错误参数、非法返回、未知运算、零除数和未匹配编码均拒绝；不会以零或固定数值替代未知上下文。

加法仅实现已核对的标准编码分支：输入最低位为零，无 64 位溢出，结果整数部分绝对值小于 `2^30`。证据为入口 62 字节与返回指令 1 字节，明确是两个指令片段，没有完整 PE 边界，不计作完整函数。除法有完整 PE 方法 68481／654 字节的证据：绝对值运算按 Q32 量化、半值向较大绝对值舍入，再恢复符号和编码；扩展输入或扩展结果仍拒绝。

固定程序及运算证据为 `src/test/resources/fixtures/rogue-resonance-floor-programs-4.6.json`，规范 LF SHA256 为 `b0044406cb7d58fb18f0f292acd1a4a1e73b15e2b48937aa8696878736170f06`。这是原始程序及证据夹具，不是安装到运营资源中的脚本。

## 回归与复核

函数绑定先复现五个真实断言失败；floor 扩展编码的十六个断言和除法舍入的六个断言先失败后修复。工具准备错误及一个错误的超范围测试输入不计作行为失败。Java 两个测试类新增 32 项，Python 新增 14 项聚焦回归均通过。floor 分别用 4,096 个混合有符号输入核对独立整数取整；除法用 2,048 组数值与高精度 Decimal 舍入交叉核对。两个原件在明确数值夹具下验证正负结果，全部 23 个截断前缀及两处附加字节拒绝；这些夹具不证明实际战斗变量的语义。

完整 `scripts/build.ps1` 在 9 分 50 秒完成，8,480 项 Java／349 个测试类与全部 267 项 Python 无失败或跳过。源码包核对 2,647 个文件，151 份协议快照保持不变；最终产物 `build/rogue-resonance-functions-8480-certified.jar` 及证据 `build/rogue-resonance-functions-verification.json` 已封存。当前仍无本批原生网络或真实客户端验收；没有修改认证、存储、HTTP或启动，Java 新增组件尚未接入活动战斗。

```powershell
python scripts/read-rogue-resonance-functions.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-functions-evidence.json
python scripts/read-rogue-resonance-floor-programs.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-floor-programs-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_functions scripts.tests.test_rogue_resonance_floor_programs -v
```

## 仍待接入

显式数值 Map 由调用者提供，三个原始数值变量键尚未绑定真实战斗对象、动态参数或生命周期。其余十四个函数仅匹配注册身份，没有实现；减法、乘法、取负及扩展算术尚未加入该数值求值器。592 个表达式的结构可读不等于全部表达式可求值。共鸣效果、强化组合、镜头调度、其余战斗动作、完整剧情、宇宙玩法、活动小游戏和国服客户端验收继续保持 partial。

后续静态定位已在同一原始能力的 `DynamicKey` 赋值项找到 `_CurrentSP`、`DelayCount_Levelup7` 和 `Extra_AttackTime`，与原始局部声明及三个键对应。两处 `_CurrentSP` 赋值的原始属性枚举 12 是 `CurrentSP`，目标为 `Caster`；这是配置中的来源链。尚未证明技能扣费前后读取时点、外部参数赋值和运行对象身份，不将服务器当前能量直接代入该变量。
