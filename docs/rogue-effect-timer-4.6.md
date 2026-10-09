# 4.6 淡出调度

Java 21 RogueEffectTimer46补入CBPBNFJLEMP.EFBDDJNDKDP普通体。共享TimerComponent增加已核对的_IsInit、StopFollowOnFadeOut、FadeOutLerp、FadeOutAudioEffectName和_OwnerEffect投影；State对应此行为的owner、驱动、Action与两字节标志。保留现有字段类型与包名，混淆名称不推定成完整计时算法。

先要求self，参数低字节写偏移73，偏移72清零，再读取/转换当前owner。owner未初始化或驱动NULL时要求传入回调并立即执行，不替换当前保存回调。其他分支先执行捕获旧回调，正常返回后保存传入值（可NULL），再检查fadeCheck；旧回调错误保留其修改且不写新回调。旧回调NULL时新回调发布先于要求检查绑定。fadeCheck为false尾调用fallback，之后不继续处理owner或音效。

检查通过则重新读取owner，StopFollowOnFadeOut开启时查询MonoEffectPluginFollow，非NULL且缓存非零才写StopFollow=true。重新读取owner的原始FadeOutLerp：1调用一号插值，0调用零号插值并传布尔参数，其他值不插值；原始float保留，包含负一、负零、无穷和NaN，不推定单位或补默认时长。

插值后读取当前owner；音效名非NULL/非空且OwnerEffect、其behavior非NULL才捕获名字和behavior、取此owner的GameObject并PostAudioEvent。GameObject可NULL，取对象回调改变owner/behavior不改变此次已捕获音效参数。音效返回后重新读取owner，最后要求OwnerEffect及behavior并OnFadeOut。回调、查询、插值、音效或通知失败保留此前回调、标志和StopFollow变化，不回滚、不重试。

35项新增Java、2,153项暂存联合（56类）、8项新来源检查通过。实际等待技能隐藏 → 实际淡出控制保存同一Action → 实际延迟隐藏联合验证；驱动/初始化门、回调替换、状态重读、原始插值、音效捕获和失败前缀留证。既有生产行为先失败为0。1个完整方法931字节、5个辅助2,009字节、6个字段类型、7个明确绑定、34处RIP、1个类型槽和1个GetComponent<MonoEffectPluginFollow> MethodSpec核对；StopFollow字段偏移158独立匹配。

全量 `scripts/build.ps1`、11,571项Java（440类）通过；既有717项Python全量与后续四十四批共352项独立检查通过（未重跑完整Python集合，既有脚本哈希未变）。3,185个源码文件、151份协议快照核对通过；结果为 `build/rogue-effect-timer-verification.json`，封存JAR为 `build/rogue-effect-timer-11571-certified.jar`，SHA `80f68ed6115fa07ca6e96b79b0a109cf76a6ae0814333ba0d13407a70895b9e4`。

淡出检查、fallback、两插值、PostAudioEvent、OnFadeOut体、Unity查询、运行时转换/Action ABI、热修复及实际World仍待完成。完整战斗/宇宙/剧情/活动及客户端验收未完成；151协议快照、版本基线、运营数据与客户端不变，宇宙partial。
