# 国服 4.6 毁灭共鸣共享镜头的有限读取

已定位并严格读取 `BattleEventAbility_620101_Camera` 的定义，核对毁灭能力 `Avatar_RogueBattleevent120_Skill03_Phase02` 的实际 `TriggerAbility` 调用。这里只解决原始定义的查找、字段读取和引用关联，没有执行镜头或战斗效果，没有安装运营资源，也没有完成真实游戏画面验收。

## 原始路径与字段依据

完整原生清单加载函数读取 `BakedConfig/ConfigManifest.json`；固定原件为 188,044 字节，SHA256 为 `cfb12e86c336b1fac50c275f953fd69497c6a724f92701b806d0aca19b716bb6`，包含 1,540 个 `TurnBasedAbilityConfig` 路径。只读检索后，名称落在 `Config/ConfigAbility/Level/Level_RogueBuff_Ability_3.json` 及配套 `.layout.json`。

父能力记录为 21,718 字节，布局为 1,999 字节。布局完整读取 76 条记录；原生 `ConfigBakeLayoutInfo` 的字段掩码、名称、UInt16 类型标记、有符号偏移和依赖数组与完整读取函数匹配。八个类型值来自固定元数据位置，而非枚举字段排列。原生加载分支将类型 1 注册为能力，保存布局偏移；实际延迟读取函数把偏移交给字节游标，再调用 `TurnBasedAbilityConfig` 读取器。

共享镜头起始偏移 1,617，下一布局对象偏移 2,201；584 字节能力完整消费，SHA256 为 `28e355c08649df098b09459c15c7889f06621920c38c2803da82856b6c4e1ed2`。15 个 `OnStart` 动作顺序保留，包括强度渐变、相机切换、等待、抖动及径向模糊。只追加实际出现的 `RadialBlurEffect.BlurFeather` 字段：原生掩码位 10、实例偏移 `0x44` 和浮点读取函数逐项匹配。原先九条能力读取器的字段范围保持独立。

调用端原始记录为 6,735 字节，完整读取后的动作 3573 为 `TriggerAbility`，静态名称模式指向该共享镜头，目标别名为 `Caster`，`IsSkillPerform` 为真。报告同时记录调用对象偏移 497 和被引用定义偏移 1,617；不存在执行或动画同步证明。

本批核对三份资源共 30,452 字节的身份与哈希，**实际解析 9,318 字节**：布局 1,999、调用端 6,735、镜头能力 584。父记录的其他 21,134 字节尚未完整解析，不能以资源校验通过宣称整份能力表已解码。新增固定证据包含十三份完整 PE 方法，共 5,621 字节；基础能力字段继续依赖先前 248 份完整方法。

## 拒绝规则与回归

错误类型、重复名称、重复偏移、未解析依赖和负偏移五种用例先触发实际 `AssertionError: ValueError not raised`，补入校验后全部拒绝。镜头只允许固定名称、类型、字节范围和内容；布局身份缺失、未知类型、越界或重复偏移、空列表及未知掩码均停止。固定原件没有镜头依赖字段，因此新增依赖（包括显式空数组或 null）也拒绝，不推定缺省值。

九项 Python 回归覆盖真实调用关联、动作顺序、字段范围隔离、位宽和数组边界、二十种非法布局身份、调用任务/目标/模式损坏与重复关联；1,999 个布局截断前缀和 584 个能力截断前缀全部拒绝。附加布局字节拒绝，能力读取器明确保留附加字节差额，引用解析仅接受完整且哈希匹配的固定能力范围。

```powershell
python scripts/read-rogue-resonance-shared-camera.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-shared-camera-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_shared_camera -v
```

固定证据为 `src/test/resources/fixtures/rogue-resonance-shared-camera-4.6.json`，规范 LF SHA256 为 `0226e0afabb27e9ed58d81cceefae0d45b57ecff71623c776b7d86d95ab1c3b1`。客户端 DLL、元数据、完整 PE 范围及字节、原始枚举位置和 Design 索引先核对；输出限制在工程 `build/`，不改客户端或运营资源。

## 验证边界

聚焦九项回归及三份原件读取报告通过；完整 `scripts/build.ps1` 在 9 分 15 秒完成，8,448 项 Java／347 个测试类无失败、跳过或错误，全部 243 项 Python 通过。源码包核对 2,632 个文件，151 份协议快照保持不变。本批封存产物、原件和回归边界见 `build/rogue-resonance-camera-verification.json` 与 `build/rogue-resonance-camera-8448-certified.jar`。

本批只改只读工具、固定测试证据和覆盖说明，没有改 Java 运行逻辑；既有 KCP 网络和正常 H2 恢复结果仍引用此前各自封存的产物，不冒充本批镜头执行证明。准备阶段的换行哈希、泛型槽解析限制、继承元数据限制与原生指令断言地址纠正均不计入五种行为先失败证据。

共鸣表达式求值、战斗上下文、完整动作执行、强化组合、客户端显示及其余剧情/宇宙/活动仍待完成。协议和客户端版本声明沿用已核对的原有边界。
