# 原始回响角色配置的有限读取

`scripts/read-rogue-resonance-character.py` 直接从本机固定国服 4.6 设计数据读取 `Avatar_RogueBattleevent120` 至 `128` 的九条 CharacterConfig 记录。九条合计 5395 字节，全部消费；它们的路径、XXH64 索引键、长度和 SHA256 已逐条固定。索引完整消费 141295 条，来源块核对 MD5 与 SHA256，不启动游戏或运行引用仓库的二进制。

本次核对十一份完整 PE Exception Directory 方法范围，合计 25520 字节，覆盖 CharacterConfig 类型分派、BattleEventConfig、技能/目标/相机/行动延迟/AI 权重及当前使用的 DynamicValues 读取分支。静态布局保留 224 个字段位置，解析仅允许九条原始记录实际使用的 39 个字段。CharacterConfig 的 `ApplyDLCPaths` 不参与二进制序列化，排除后 BattleEventConfig 使用 95 个字段与两个 mask；第 89 位 MaxSP 由 signed Int32 读取并写到原生对象偏移 `0x248`。SkillConfig 为 77 个字段，不能把元数据字段顺序直接视为序列化格式。

九条记录均显式给出 MaxSP 100、玩家控制标记、主动 `Skill03` 和被动 `SkillP01`，各自的 EntryAbility 出现在相应 SkillAbilityList 中。MaxSP 与已有固定资源 BattleEventSkillConfig 的主动 SPNeed 100 交叉一致。这确认记录中的数值和绑定，尚未证明技能消耗、奇物增益、初始能量、伤害或官方游戏 UI。

智识 127 的目标为 LightSelect，并显式 AvoidSelf；其 DynamicValues 缺省，报告不替它补空表或客户端默认值。非有限 BuffPanelOffset 浮点保留原始位与 `Infinity` 字符串，定点量只保留 signed 原始整数，因此报告始终是标准 JSON，却不是可直接安装的客户端 Config JSON。

未知 mask 位、已知但未匹配字段、对象类型、枚举常量、AI 权重类型、DynamicFloat 模式、DynamicValues 整数表/表达式/范围/非零 ReadInfo 模式都立即停止。另限制记录大小、整数位数、数组、字符串、节点及递归深度；拒绝非规范 varint、截断和剩余字节。原始记录中的 DynamicValues 只使用常量、无范围、ReadInfo 0 分支；不由此推断其他模式的语义。

复验命令：

```powershell
python scripts/read-rogue-resonance-character.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current'
python -m unittest scripts.tests.test_rogue_resonance_character -v
```

完整证据输出至 `build/rogue-resonance-character-evidence.json`，原始临时导出在读取结束后清理。只接受工程 `build/` 下的报告路径，不写客户端、运营资源、配置或数据库。十四项 Python 检查包含九条真实记录的 5395 个截断前缀与九个尾随字节检查；全部回响 Ability 动作、强化组合与游戏客户端入口仍待补齐，经典宇宙功能状态继续为 `partial`。

本次十四项检查及完整 Python 212 项均通过，完整 Java 8448 项（347 类）也通过；证据归档与同产物网络验证见 `build/rogue-resonance-binding-verification.json`。这些数字分别表示解析、服务端回归与自有网络验证，没有把只读解析记为动作执行覆盖。
