# 4.6 互斥效果重新挂载

Java 21 RogueMutexAttach46 补入TurnBasedModifierInstance._TryAttachMutexEffects普通控制及其异步加载完成闭包。复用销毁状态的cleanup.effects/mutexEffects与References；效果和互斥参数是明确的有限投影，不是Unity对象或真实战斗世界登记。

等待与来源列表可空，非正等待数量或来源零数量返回。按初始等待/来源数量倒序，访问时重新核对当前数量/数组。来源效果复用次数非零跳过，等待参数NULL直接候选；其他参数按路径引用、NULL、长度及明确相等绑定筛选。仅淡出分支查询当前拥有者/世界/MonoEffectManager，类型0/1分别调用全局/实体互斥判断；实体判断重新读取拥有者并保留当前实体NULL，其他类型仍读取管理器但不补造判断。浮点参数原样保留。

活动列表先加版本，再写入候选效果（不足容量用已有实际添加扩容及容量设置），随后重新读取闭包当前效果并增加复用次数。有效Unity指针与实例ID匹配时，淡出才调用Revive、再重新读取实例调用Replay(false)，然后隐藏设置及技能隐藏(false)。未创建且ID为0、有TriggerEffectParams时安装新的CompleteCallback；先分配委托再读取已核对MethodInfo，写入捕获的参数对象，不因回调替换参数对象而换目的地。

闭包捕获owner/effectData，实际目标510352是<_TryAttachMutexEffects>b__0，转发至510095_OnAsyncLoadEffectFinish，不是淡出完成通知。回调要求当前owner，再读取当前效果；效果及完成参数可NULL并原样转发。异步完成体尚未接入，独立保留边界。

挂载处理成功后才从当前等待列表删除原位置，先减数量、按需移动当前数组，再重新读取当前数组和当前数量清空尾部，最后加版本。复制失败保留数量已减前缀，不提前加版本。41项新增Java、2,026项暂存联合检查（52类）、8项来源检查通过；暂存实现中复制后当前尾部的1项回归先失败，修正后通过，归档build/mutex-attach-current-tail-red.xml；既有生产行为先失败为0。实际其他淡出回调 → 实际互斥重试 → 实际添加扩容 → 实际容量设置联合验证。255等待/来源数量为有限投影策略。

完整原件2方法1,699字节、7辅助2,119字节，10个字段所属类型及两种List泛型、闭包类槽/MethodInfo、7个明确绑定身份和32处RIP使用点留证。共享EffectInst的类型布局记录为盒装视图，嵌入ID/实例控制另由实际指令核对，不直接将盒装偏移套入嵌入结构。

全量 `scripts/build.ps1`、11,444项Java（436类）通过；既有717项Python全量与后续四十批共320项独立检查通过（未重跑完整Python集合，既有脚本文件哈希未变）。3,161个源码文件、151份协议快照核对通过；结果为 `build/rogue-mutex-attach-verification.json`，封存JAR为 `build/rogue-mutex-attach-11444-certified.jar`，SHA `4fba1bf9e65ea705a5cf5bfb8cfdae4d1ff65151eb7744ee5db6142e96f6154c`。

Base虚槽21到此覆盖的独立注册身份、Unity对象、互斥判断、隐藏/技能隐藏/异步加载完成体、委托ABI、热修复及真实World仍待完成。完整战斗/宇宙/剧情/活动与官方客户端验收未完成。151份协议与版本基线不变，宇宙partial，不修改客户端和运营数据、资源、配置。
