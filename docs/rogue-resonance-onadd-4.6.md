# 4.6 经典共鸣 OnAdd 的有限 Java 编译入口

九条经典共鸣的原始 OnAdd 共 24 个任务，已按原始顺序编译为 Java 子任务序列。每条先执行 2847 参数查询，再执行 1122 创建事件；41001–41005 和 41008 还包含任务 50。六条附加效果不会因缺失执行器而被忽略。

实现为 `RogueResonanceOnAdd46`。该入口编译已核对的三个有限配置类型，查询与创建使用现有 Java 求值、原始事件表、当前波次准备和创建生命周期。附加效果必须由调用方提供 `ModifierFactory`，返回真实子任务状态；等待、失败、重置和释放由序列调度。它尚未接入在线战斗，也没有实现完整 AddModifier 原生运行时。

## 原始配置

| 事件 | 原始附加效果字面量 |
| --- | --- |
| 41001 | StageAbility_612020_Modifier |
| 41002 | StageAbility_612220_Modifier |
| 41003 | StageAbility_612320_Modifier |
| 41004 | StageAbility_612420_Modifier |
| 41005 | StageAbility_612520_Modifier |
| 41006、41007、41009 | 原始 OnAdd 无任务 50 |
| 41008 | MAvatar_RogueBattleevent127_AimTarget |

九条创建任务显式 Team=3、TotalDamageTeam=1，155 项参数绑定来自此前冻结的完整原始定义。六条效果任务均为 TargetAlias 变体 12、Alias=Caster、DynamicString 字面量；原始字段掩码 0xa，未包含 AliveOnly、成功/失败/抵抗子任务及其他效果字段。编译入口只表达这一子集，不假设省略字段的完整运行时意义。

24 个任务均省略 TaskEnabled。TaskConfig 的原始叶构造器在 +0x10 写入 true，指令字节为 `c6411001c3`。该 5 字节叶片段无 PE 异常目录项，独立核对并单列，未计入六个完整方法的字节数。

## 执行与项目边界

省略使能字段按 true，显式 false 在绑定解析和任务工厂分配前过滤；启用任务保持源顺序。编译期间先核对所有启用配置所需的明确上下文、来源表和工厂，再分配任务；这项预检及缺失效果工厂/返回 null 时拒绝编译为项目规则。原始通用工厂存在返回 null 后跳过的分支，不能以该分支为借口隐藏未实现的任务。

创建请求保留任务的 Team 和 TotalDamageTeam，参数为不可变快照。九条原始创建任务均无前后回调。测试工厂接收的是显式对象与当前波次行，尚未建立真实战斗实体、属性系统、效果存储或网络副作用。缺失事件/关卡或工厂返回 null 时，原始创建任务仍可处于完成状态，后续效果任务继续调度；序列完成并不证明事件或效果成功。

查询和创建的已有状态语义继续保留：先记完成再读来源或调用创建工厂，异常停止本次调度，之前的写入与完成状态保留，不补隐式回滚。重试或重置由调用方明确选择。普通序列未启用演出即时分支；编译列表、绑定事件表及参数快照均保存独立不可变副本。

通用工厂的前后任务连接接口、任意 TaskConfig、效果叠层/概率/生命周期/UI/抵抗回调、全局插件、客户端热修复覆盖和实际战斗上下文仍未接入。六个完整本机方法只用于静态证据，不加载或执行 DLL。

## 核对与验证

固定客户端 GameAssembly SHA 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据 SHA 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。效果配置 CLASS 槽 0x9392050 对应 AddModifier 23067，工厂 MethodInfo 槽 0x94aba48 对应 516303，工厂分配 CLASS 槽 0x9393c78 对应 JAJPDPAHFOA 54816。原生构造器、Dispose、OnTaskBegin、工厂、注册表和通用任务数组构造器共六个完整 PE 方法，95,918 字节。

冻结证据为 `src/test/resources/fixtures/rogue-resonance-onadd-4.6.json`，LF SHA `9b5a0a693fe39ffc997e8cf7f0ceacfc2226dde3c9690cebe6ea1a1e9401c383`。`scripts/read-rogue-resonance-onadd.py` 重新读取客户端方法、类型/方法槽、字段及原始能力记录，核对九条任务的全部已解码字段；七条父能力表完整读取，额外两条仅选中能力完整读取，不声称额外父表整体已解析。

三项真实断言先失败，随后通过；29 项新增 Java 回归覆盖九条原始配置与原始祝福/事件表联动、三类使能过滤、绑定预检、缺失效果拒绝、调用顺序、等待/失败、异常后状态、重置/释放和快照。10 项新增 Python 核对完整方法、叶片段、原件及未完成边界。测试中的效果子任务与事件工厂为明确夹具。

全量 `scripts/build.ps1`、9,070 项 Java（376 个测试类）和 524 项 Python 均通过；2,801 个交付源码文件、151 份协议快照核对通过。结果为 `build/rogue-resonance-onadd-verification.json`，封存 JAR 为 `build/rogue-resonance-onadd-9070-certified.jar`，SHA `3265844f07c28aaea91604516bc640a061eca69bfb3d6a1d45034d3eb9c5e914`。

本批未修改认证、存储、启动或 HTTP，未新增服务器 smoke 或真实客户端验收；未改写操作员资源、客户端、生成协议或版本号。宇宙、战斗、剧情/日常与活动的完整功能继续保持 partial。
