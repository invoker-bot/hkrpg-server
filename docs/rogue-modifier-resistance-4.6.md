# 4.6 行为抗性与命中加成

`RogueModifierResistance46` 补入明确数值投影下的行为抗性最大值、普通抗性补数乘积和命中加成。数值必须由显式接口从当前字典/属性读取；本批没有建立真实组件字典、属性累计器或随机提供者。宇宙玩法继续为 `partial`。

## 有限计算

行为抗性遍历原始标志顺序，从 Zero 开始取匹配当前值的最大值。缺失键跳过；缺失字典、缺失明确查找结果或已命中对象读取失败继续抛出异常。重复标志重复读取当前值但不累加数值，每项后重新读取列表长度。缺失/空标志列表直接返回 Zero，不读取字典。

Anti 列表命中某个标志时，仅跳过小于 One 的抗性；等于或大于 One 的抗性仍保留。概率加成也从 Zero 取最大值，不使用 Anti 列表。

普通抗性先读取属性135 `StatusResistance`，然后计算行为抗性，最后执行 `(One - property135) × (One - maxBehaviorResistance)`。额外命中先读取属性132 `StatusProbability`，然后加行为概率的最大值。结果没有统一 0～1 限幅或整数舍入；负属性和超出 One 的标准值按原件算术保留。标准编码与最多255项是明确项目有限边界；需要扩展结果的运算继续拒绝。

## 原件与验证

六类字段与五个完整 PE 方法共8,963字节独立核对：共鸣入口、FixPoint常量构造、普通抗性、行为抗性与行为概率。另有61字节标准减法与结果范围指令片段，并核对其 `op_Subtraction` 元数据身份；该叶方法没有完整PE异常表范围，没有计入五个完整方法。

组件 `_StatusChanceResistanceDict` 偏移224和 `_StatusProbabilityDict` 偏移416均核对到 `Dictionary<ModifierBehaviorFlag,GKMKOMHLGDC>` 的真实泛型参数，后者当前 FixPoint 字段 `EJNMGLJJEOO` 位于120。这证明读取位置，不证明属性累计器、真实字典安装或在线数值生成。One和Zero继续使用实际成对初始化来源。

三项有意义的 Java 回归先因错误累加、错误跳过完全免疫及错误合并两种抗性而失败，修复后新增28项Java与10项Python通过。全量 `scripts/build.ps1`、9,359 项 Java（385 个测试类）和 616 项 Python 均通过；2,855 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-modifier-resistance-verification.json`，封存 JAR 为 `build/rogue-modifier-resistance-9359-certified.jar`，SHA `968ad9a8bc03b779ae8ec6e0aa149b9fab25ddb3c4e23b9b357f85af320b0946`。

## 继续待完成

真实字典值累计器、在线属性提供者、字典安装、行为/状态免疫表、随机算法与状态计数、实际修改器实例及叠层/寿命继续待实现。接口结果尚未替代实际战斗世界，也未进行真实游戏入口验收；未修改151份协议快照、客户端或运营资源，未启动客户端或参考项目二进制。本批不涉及认证、存储、启动或HTTP，未添加网络验证。
