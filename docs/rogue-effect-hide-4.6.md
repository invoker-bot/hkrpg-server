# 4.6 效果隐藏与技能隐藏

Java 21 RogueEffectHide46补入_SetupEffectHide、_TryHideEffectInSkillUse及实际延迟闭包的普通体。共享修饰器增加_EffectHide、原生名_EffectPrioritytHide、_EffectAutoHideInSkill对应布尔，共享效果增加AutoHideInSkill；既有字段类型、包名及实例对象保持。

普通隐藏在效果NULL、实例NULL/缓存指针零/ID不匹配、TriggerParams为空或有符号AliveState大于5时，在self和绑定前返回。合格实例捕获behavior后读取self两个隐藏标志，再要求behavior。任一标志为真时写入原因0x80并总是_SetActive(false)；都为假时清除此位，只有此前原因恰为0x80才_SetActive(true)。其他原因保留且不调用激活绑定，原因写入后的绑定失败不回滚。

技能隐藏先分配闭包、写owner，后读self标志；owner标志关闭可接受NULLeffect，effect标志关闭不读取实例。开启时复制实例ID、填充字和指针的16字节值；实例有效、ID匹配、参数非NULL且有符号AliveState不大于4才继续。即便wait=false，仍求组件类型并执行组件查询；查询后重新读取闭包中的实例，不重新验证缓存/ID/初始状态。

组件来源独立核对为Component.GetComponent<MonoEffectPluginFade>的MethodSpec（31409、classInst78325），插件_Behavior偏移184类型为69875 CBPBNFJLEMP。等待分支要求当前参数状态3、插件非NULL且Unity指针非零；先捕获_Behavior，分配Action，再读MethodInfo并填入指针、目标闭包和调用字段，最后要求此前捕获的_Behavior。EFBDDJNDKDP调度保持原始float位0xbf800000（-1.0）、Int32零和false，不推测单位、时间政策或该混淆方法的完整含义。

其他分支要求当前实例behavior，写原因0x200并_SetActive(false)。延迟闭包先要求owner；owner活动列表NULL则返回；否则读取当前闭包实例与behavior，执行相同隐藏。它不复查ID、缓存、AliveState或开关，非NULL列表不要求数组/数量有效。回调中的对象替换、计时字段变化及错误保留相应前缀，不添加重试或回滚。

43项新增Java、2,092项暂存联合检查（54类）、8项新来源检查通过。实际异步完成 → 同一效果实例发布 → 两条实际隐藏普通体联合验证；NULL/失效门、唯一原因复活、延迟/直接分支、闭包替换、初始化/调度失败均留证。既有生产行为先失败为0。3个完整方法1,292字节、异步完成辅助263字节、10个字段类型、18处RIP、2个类型槽、1个泛型MethodSpec与1个闭包MethodInfo核对；原始浮点常量的地址、4字节、指令引用和SHA单独冻结。

全量 `scripts/build.ps1`、11,510项Java（438类）通过；既有717项Python全量与后续四十二批共336项独立检查通过（未重跑完整Python集合，既有脚本哈希未变）。3,173个源码文件、151份协议快照核对通过；结果为 `build/rogue-effect-hide-verification.json`，封存JAR为 `build/rogue-effect-hide-11510-certified.jar`，SHA `c5e8097d6da4bf2c277a9eda3492b5c5c22a055ce5cd692ada535db5d21b851f`。

Java Behavior、TimerComponent、Callback为有限投影。Unity GetComponentFastPath、MonoEffectBehavior._SetActive体、混淆计时行为体、原生委托ABI、热修复与实际World仍待完成。完整战斗/宇宙/剧情/活动与客户端验收未完成；151份协议快照、版本基线、运营数据及客户端不变，宇宙partial。
