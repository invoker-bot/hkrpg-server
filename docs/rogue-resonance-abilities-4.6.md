# 国服 4.6 共鸣能力的有限读取

九条 `Avatar_RogueBattleevent120..128_Ability` 原始记录已完整读取，共 71,510 字节。角色配置中的主动、被动技能入口与能力名称交叉核对；毁灭回响另引用 `BattleEventAbility_620101_Camera`，该共享镜头依赖不在这九条记录中。

## 已核对范围

读取器匹配 121 个原生类型、1,061 个序列化字段位置，只开放九条记录实际使用并核对的 348 个字段。74 种动作、26 种条件、4 种目标和 2 种目标序列操作的类型标记，分别匹配完整原生注册函数、委托槽、工厂函数、分配类型和读取函数。两个目标别名工厂实际调用缓存读取函数，没有按普通分配工厂伪造类型证据。

固定证据保存 248 个完整 PE 函数，共 253,291 字节，包括注册表、组件读取函数、字典委托及不同宽度枚举的读取函数。`GameAssembly.dll`、元数据、Design 索引、原始记录长度和 SHA256 均先核对，其他客户端拒绝沿用这些布局。字段通过原生类型索引核对，避免 `LookAt`、`EffectConfig` 等同名类型混用。

`ModifyDamageData` 使用两个 64 位字段掩码；收到伤害的比例字段位于序列化位 65。`EntityTypeMask`、`AliveStateMask`、`GameCoreParamFlag` 分别按原生 64、16、32 位无符号枚举读取，其他已核对枚举按其实际类型读取。无法匹配的枚举类型或值停止解析。

`AddModifier.DynamicValues` 与 `CreateBattleEvent.DynamicValues` 使用字符串到 `DynamicFloat` 的字典；局部及全局效果配置使用字符串到 `TurnBasedModifierConfig` 的字典。长度、重复键和嵌套读取受限，其他泛型字典停止。能力配置的 `_TaskListTemplatesMap`、`JsonPath` 和效果配置的 `IsGlobalModifier` 没有冒充序列化字段。

## 表达式与运行边界

原始记录中的 346 个动态后缀表达式保存其完整字节、定点常量和变量键。已匹配表达式的二进制框架，但没有求值、推断运算符、猜测定点缩放或将动态结果代入固定数值。记录还保留条件分支、效果回调、伤害、治疗、行动延迟、技能按钮、自动终结技、镜头和演出配置。

这一步提供后续能力接线的原始依据，**不等于效果执行、强化组合、完整战斗动作或真实国服客户端验收**。工具不启动游戏、不执行 DLL、不修改客户端，不向运营资源、配置或数据库安装 JSON。经典宇宙仍保持 `partial`。已有角色、回响菜单和能量验证分别见 [角色配置](rogue-resonance-character-4.6.md)、[共鸣菜单](rogue-resonance-4.6.md)、[能量网络证据](rogue-energy-network-4.6.md)。

## 复核命令

```powershell
python scripts/read-rogue-resonance-abilities.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-ability-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_abilities -v
```

输出仅接受工程 `build/` 内的证据路径，临时原始导出在结束后清理。固定证据位于 `src/test/resources/fixtures/rogue-resonance-abilities-4.6.json`，读取器核对规范 LF 内容 SHA256 `aecd7c770a4c85adfbdb927a1603210f7077dda96e6ae07f7c79745352b4b13e`。

回归覆盖四种已知但尚未匹配字段的拒绝、完整注册指令、两个掩码、三种无符号枚举宽度、表达式框架、重复字典、未知类型/模式、同名类型、非规范整数、递归和节点上限、输出边界、原始技能入口、全部 71,510 个截断前缀与九个附加字节。

## 最终构建与验证

本批新增 13 项 Python 回归。四个已知但尚未匹配字段先实际触发 `AssertionError: ValueError not raised`，补入字段白名单检查后通过；测试准备时的夹具哈希和错误预期不计入行为先失败证据。全部原件截断前缀和附加字节均拒绝。

完整 `scripts/build.ps1` 通过 8,448 项 Java 测试、347 个测试类；全部 Python 回归 225 项通过。最终源码包核对 2,624 个文件和 151 份冻结协议快照。产物 SHA256 为 `ca748870ca4e1a7d995c21a8758765dcdd6f1b9c485fa5f7864571c8337c97b2`，封存于 `build/rogue-resonance-abilities-8448-certified.jar`，详细报告为 `build/rogue-resonance-abilities-verification.json`。

同一产物重复验证既有能量流程 60 次请求／96 项检查、共鸣菜单流程 64 次请求／76 项检查，以及四处明确缺失菜单的恢复流程 56 次请求／64 项检查。三套自有 KCP 探针分别正常关服六次、同 UID 的 H2 正常重启五次，分别进行十二次实际 Player 读取；每套 2,254 份输入资源 SHA 保持不变。Dispatch 及账号正常重启恢复也通过。这些是隔离夹具和自有探针的服务端回归，**没有验证游戏内镜头、表达式求值、回响效果执行、断电原子性或真实国服 UI**；本批没有新增 Java 玩法实现。
