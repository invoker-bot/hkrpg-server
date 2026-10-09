# 4.6 效果激活

Java 21 RogueEffectActive46补入MonoEffectBehavior._SetActive普通体。共享Behavior增加_OwnerMonoBehavior和_EffectInited对应字段，共享MonoEffect增加IsActive布尔；既有字段类型、包名及效果对象保持。

首先要求behavior，未初始化则先调用_InitEffect，不复查初始化标志；随后读取并按已匹配MonoEffect类型转换当前owner。当前IsActive与要求值相等时直接返回，不要求World，不添加Unity缓存指针验证。不同则先写入IsActive，再取此捕获owner的GameObject；第一次对象为NULL或缓存指针零时返回，状态写入保留。

第一次对象有效则SetVisible，随后重新读取/转换behavior当前owner，再获取GameObject。第二次GameObject必须非NULL，但不追加缓存指针验证；求角色特效类型并查询组件，仅当组件非NULL、缓存指针非零才SetCharaEffectEnable(active,true)。可见性/对象回调替换owner时保持此顺序，不为替换owner补写IsActive。初始化、可见性、查询或启用失败保留已发生状态变化，没有回滚或重试。

角色特效独立匹配为GameObject.GetComponent<MonoEffectPluginCharaEffectBase>（MethodSpec31472、classInst51529）；owner类型槽69911、IsActive偏移57、初始化偏移124及组件/对象缓存字段核对。原生运行时类型转换只以有限Java类型投影表达，Unity查询、类型系统与函数指针ABI未安装。

26项新增Java、2,118项暂存联合（55类）、8项新来源检查通过。实际修饰器隐藏 → 实际激活 → 实际技能隐藏 → 相同实例第二次激活联合验证；初始化门、匹配值早退、对象两种检查、回调改owner、true/false及失败前缀留证。既有生产行为先失败为0。1个完整方法454字节、4个辅助1,555字节、4个字段类型、25处RIP、1类型槽和1个泛型MethodSpec核对。

全量 `scripts/build.ps1`、11,536项Java（439类）通过；既有717项Python全量与后续四十三批共344项独立检查通过（未重跑完整Python集合，既有脚本哈希未变）。3,179个源码文件、151份协议快照核对通过；结果为 `build/rogue-effect-active-verification.json`，封存JAR为 `build/rogue-effect-active-11536-certified.jar`，SHA `15f0d06b5765cc4ea721a2aae06e8e63437b354938943c1920eef0fbe9f2b314`。

_InitEffect、SetVisible、SetCharaEffectEnable体、Unity对象/组件查询、运行时类型转换ABI、热修复与实际World仍待完成。完整战斗/宇宙/剧情/活动及客户端验收未完成；协议快照、版本基线、资源、配置和客户端不变，宇宙partial。
