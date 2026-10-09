# 4.6 异步效果加载完成

Java 21 RogueAsyncEffectFinish46 补入TurnBasedModifierInstance._OnAsyncLoadEffectFinish普通控制。共享ModifierDestroy状态增加已核对的effectUnload布尔，共享MutexAttach效果增加实例结构填充字、MonoEffect.TriggerParams和参数AliveState；现有字段类型和包名保持。

传入MonoEffect为NULL、Unity缓存指针为零、TriggerParams为NULL或当前AliveState按有符号Int32大于5时，在要求self/effect/World前返回；负状态不被补判失效。合格实例要求self，effectUnload为真或当前活动列表为NULL时先调用SetFinish(true,false,false)，随后继续隐藏设置及技能隐藏(false)。此分支effect可NULL且不发布实例字段，列表非NULL并不校验其数组或数量。

活动分支要求effect，依次写入当前传入实例的原始UInt32 ID位、零填充字、同一实例指针；然后才要求隐藏绑定。绑定缺失/隐藏失败保留已经发布字段；隐藏回调修改不重新执行状态门。结束效果失败停止后续隐藏并保留回调修改，不增加/减少复用次数、列表数量或版本。

实际CompleteCallback字段为Action<MonoEffect,TriggerEffectCallbackParams>（Action`2）；保持第二参数边界，已核对闭包普通体没有读取它。新增投影重载直接忽略此值，既有单参数普通调用保留。实际互斥闭包 → 实际异步完成 → 共享效果对象更新联合检查通过，委托ABI/原生参数传值并未安装。

23项新增Java、2,049项暂存联合检查（53类）、8项来源检查通过。覆盖门限两侧及负值、NULL/零指针、卸载/列表清理、原始ID位/填充字/指针顺序、NULLeffect、结束/隐藏错误、当前回调变化及实际闭包；既有生产行为先失败为0。1个完整方法263字节、9个辅助3,818字节、10个字段类型、3个泛型字段（两种List及双参数委托）、明确SetFinish/两条隐藏身份、34处RIP与闭包MethodInfo留证。

全量 `scripts/build.ps1`、11,467项Java（437类）通过；既有717项Python全量与后续四十一批共328项独立检查通过（未重跑完整Python集合，既有脚本文件哈希未变）。3,167个源码文件、151份协议快照核对通过；结果为 `build/rogue-async-effect-finish-verification.json`，封存JAR为 `build/rogue-async-effect-finish-11467-certified.jar`，SHA `0418de029814df992d8abf61db1dbac0625eadadd8204fcb4ecc4989de8ec47a`。

Unity对象、MonoEffect.SetFinish体、隐藏/技能隐藏体、委托ABI、热修复及真实World仍待完成。完整战斗/宇宙/剧情/活动与官方客户端验收未完成。151份协议快照、版本基线与运营数据、资源、配置不变，宇宙partial。
