# 国服 4.6 共享镜头父能力表的完整有限读取

继共享镜头定义定位之后，`Config/ConfigAbility/Level/Level_RogueBuff_Ability_3.json` 的全部 21,718 字节已通过有限读取，恢复 76 条能力和 246 个后缀表达式。配套 1,999 字节布局也完整读取，全部名称和字节偏移与能力对象逐一匹配。这一步扩大原始配置读取范围，没有执行能力、解析战斗变量或安装运行时 JSON。

## 新增原生证据

六个字段新增匹配：`TurnBasedAbilityConfig.OnAdd`、`AttackData.HitTimeSlowType`、`ModifyHealData.Healer_HealRatio`、`SetDynamicValueByMazeBuffConfig.QueryList`，以及 `MazeBuffParamKey.MazeBuffID` 和 `Index`。每个字段的序列化位、元数据类型及实例偏移与完整原生读取函数的指令锚点匹配。`HitTimeSlowType` 位于位 37，原生使用位测试 `bt rbx, 0x25`，不能将它按低字节掩码处理。

新增动作注册 1751 为 `ModifyHealData`、2847 为 `SetDynamicValueByMazeBuffConfig`；完整注册写入、方法槽、构造工厂和读取函数逐项对应。三个新增对象共 15 个元数据字段，仅开放原件出现的四个字段。另有三个枚举的九个实际 Int32 常量按固定元数据位置核对，只合并原件需要的值。

参数查询字典的键读取器为原始字符串方法，值委托槽 `0x947b978` 解析到 `MazeBuffParamKey` 读取器 `1d8350b0`。该结构的 ID 和 Index 为 UInt32，原始缺省的 Level 保持缺失，不代入当前资源或猜测参数含义。字典数量、重复键、未知字段及溢出继续严格拒绝。

本批追加五份完整 PE 方法，共 1,855 字节。合并后的有限格式包含 124 个对象、355 个已匹配字段、38 个枚举和 76 个动作分支；先前九条共鸣能力及独立镜头读取器保持各自原有范围。共享镜头在整表中的范围仍为 1,617–2,201，与独立读取的 584 字节相符。

## 回归与复核

原有格式读取不到整表、OnAdd、命中减速类型、治疗修正及参数查询。五种实际断言先失败，补入已核对格式后恢复读取；这些是缺失格式的接受性回归，不把元数据查询或工具准备错误当作行为失败。

十项新增回归覆盖完整原件、全部 76 个名称及偏移、缺省字段、UInt32 最大值/溢出、查询字典异常、旧读取器范围隔离、六组字段指令锚点、两组注册与构造工厂、错位/重复/缺失布局，以及原生常量和只读输出边界。全部 21,718 个截断前缀与附加字节均拒绝。

聚焦十项回归通过，完整 `scripts/build.ps1` 在 11 分 33 秒完成，8,448 项 Java／347 个测试类无失败、跳过或错误；全部 253 项 Python 通过。最终源码包核对 2,636 个文件，151 份协议快照保持不变。产物和证据分别封存于 `build/rogue-resonance-parent-8448-certified.jar` 与 `build/rogue-resonance-parent-verification.json`。本批没有修改 Java 运行逻辑，也没有新增网络探针或把先前 KCP 结果冒充整表能力执行证明。

```powershell
python scripts/read-rogue-resonance-shared-parent.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-shared-parent-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_shared_parent -v
```

固定证据为 `src/test/resources/fixtures/rogue-resonance-shared-parent-4.6.json`，规范 LF SHA256 为 `214e77f050d52052916ba453a0aeafa5bf8560b6a595e5c01e2b1cada9d10c9d`。完整 DLL/元数据身份、PE 范围及字节、枚举原始位置、Design 索引和资源哈希先核对；输出仅在工程 `build/`，不覆盖运营配置或客户端文件。

## 未完成边界

此前镜头批次只解析父表中的镜头对象，本批追加整份父表读取；两个批次的原件范围和验证产物分别封存。完整配置读取没有证明后缀表达式求值、查询参数实际取值、伤害/治疗回调执行、强化组合或游戏画面。其他战斗动作、完整剧情与宇宙模式、活动小游戏及真实客户端验收继续补齐，整体功能状态保留 partial。
