# 4.6 其他修饰器销毁与效果淡出回调

Java 21 `RogueBaseOtherModifierEvents46` 补入两个已经匹配的普通基类回调，复用当前 RogueModifierDestroy46.State 与共享 candidate.state。销毁载荷 LIMAAJAJPKJ 和淡出载荷 GEHAPKHIMNE 为独立投影层次，允许其明确子类；不把两个载荷当作通用事件或互换。

回调先判断已知载荷类型，空载荷/其他类型直接返回，不要求接收者或 World。载荷 modifier（字段24）与接收者身份相同直接返回，包括 NULL==NULL；其后才要求实际接收者，并确认共享状态精确等于1。其他状态跳过，既不取 effects 也不要求 World。

匹配并处于活动状态后，从载荷32捕获 List<ModifierEffectData> 的同一明确对象，传给虚槽21：销毁回调 FALSE，淡出回调 TRUE。另一修饰器或 effects 为空仍允许转发；字段40 sourceEntity 不当作效果或接收者身份。内部不改变生命周期、不制造效果列表、不清事件；虚处理失败保留已完成的修改并传播错误。

32 项新增 Java 与 1,714 项暂存联合回归（44 类）、8 项 Python 来源检查通过。覆盖空/未知/对方载荷、自身与空身份过滤、NULL接收者的顺序、各种非活动状态、同一对象与差异标志、空字段、子类、回调变更共享状态后跳过第二次、失败前缀；实际基类生成两种回调 → 已知载荷分发 → 实际委托相等/撤回匹配另一次工厂生成的新回调。夹具按已匹配回调身份明确分发，没有安装全局事件调度。既有生产行为先失败为0。

冻结证据 `src/test/resources/fixtures/rogue-base-other-modifier-events-4.6.json` 保留2个完整回调共530字节、3个辅助来源共1,226字节（基类销毁与两种实际相等）、4类字段/声明、1个抽象方法声明、2个载荷CLASS槽与RIP地址、2份 List<ModifierEffectData> 字段泛型及List模板声明。槽 0x93de338/0x93de348 分别核对到56598/56599，字段24为BaseModifierInstance，32为已匹配泛型列表，40为GameEntity；同一泛型类型记录保留每个字段的来源，不把模板偏移当作闭包物理布局。

全量 `scripts/build.ps1`、11,132 项 Java（428 类）通过；既有 717 项 Python 全量与后续三十二批共 256 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,113 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-base-other-modifier-events-verification.json`，封存 JAR 为 `build/rogue-base-other-modifier-events-11132-certified.jar`，SHA `510dc47b6e84eb26866b3c45add6c14cb03fbf1a54d251a4399cb78cc14b1c21`。

虚槽21的具体方法绑定与效果处理体仍待独立核对。记录了参数形状相符的抽象 _TryAttachMutexEffects 声明，尚未证明它就是该槽，未把无独立地址的抽象声明当作函数体。原生载荷工厂、全局事件调度、真实类型层次/世界登记、完整销毁事件与属性撤回、全宇宙/剧情/活动及官方客户端验收继续待完成。151份协议快照和版本基线不变，宇宙partial；不启动客户端/参考二进制，不改运营数据、资源与配置。
