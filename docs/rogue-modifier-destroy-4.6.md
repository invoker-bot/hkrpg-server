# 4.6 修饰器销毁顺序与共享效果清理

Java 21 `RogueModifierDestroy46` 复用 Cleanup.Candidate.state/name、Cleanup.ownerAbilityComponent 和 Replacement.executingSequenceCount，新增独立匹配的 DeathSource、CreatePreshowEntity 与 SubInstanceData 投影。字段 232 是预展示 GameEntity，不是任务上下文；EntityType.Group=27 独立核对。

普通路径按有符号 state>1 直接返回，之前的负数状态仍进入。先捕获当前 owner 的实体及行为 2 原始返回字节，再写 DeathSource 和 state=2。按当前 owner 的 MuteModifierGlobalListen 与行为 86 重算静音字节；行为回调换 owner 时重新读取。捕获的 previous state=1 才递增共享执行计数、调用 _OnModifierDestroy，正常返回后递减当前计数；事件失败不执行 finally，不回滚 state 或计数。

之后直接调用已有 `RogueModifierCleanup46.cleanupAfterDestroy`，不增加空清理替代：先分离效果、清引用，再撤回命中特效、刷新优先级和行为显示。再查当前 owner 行为 2，原始字节 XOR 等于 1 时沿捕获实体的 World/Battle/Mode 可选链调用操作码 798。读取当前共享 name、捕获实体和入口原始 reason 记录销毁。实体为空时先记录再失败；存在 CharacterHudComponent 才发全局事件 878，载荷使用同一 Cleanup 状态，immediate=true。

读取当前 CreatePreshowEntity，沿捕获实体的当前世界按 Group=27 调用 _MakeGroupEntityDie，其余调用 _MakeLevelEntityDie(world,entity,null)。没有将这一成员误作 task context。清理不主动清该字段。随后要求当前 SubInstanceData；初始 children 为空或 count<=0 时直接进入基类销毁且不改头部。正数循环每次读取当前 holder/list/count/slot，对显式子 State 递归使用当前父级 DeathSource。子调用后重读 holder，正常末尾先 version++/size=0 再清当前存储；失败保留已销毁的前缀和半完成头部。入口原始 reason 留给基类调用，子回调修改父级 reason 不改变基类参数。

有限列表与递归深度上限各为 255，Int32 计数和版本保留溢出；循环子引用因共享 state=2 停止。56 项新增 Java 与 1,473 项暂存联合回归（37 类）、8 项 Python 来源检查通过，包含生命周期有符号边界、原始字节 XOR、共享清理失败、正常与失败计数、通知先后、组/关卡实体、递归原因变化、换/清空 holder、清头部后存储失败、255/256 节点深度、循环引用和实际点释放/消费/销毁链。新增能力不计为已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-modifier-destroy-4.6.json` 包含完整主函数 964 字节、2 个完整辅助来源 3,751 字节（基类 421、销毁事件 3,330）、7 类字段、12 个方法身份、1 份 List<TurnBasedModifierInstance> 泛型成员、1 份 GetComponent<CharacterHudComponent> 类实例 MethodSpec 与 2 份原始参数记录。闭包 Getter 的源方法无直接原生体，通用辅助函数仍保持明确边界。

全量 `scripts/build.ps1`、10,891 项 Java（421 类）通过；既有 717 项 Python 全量与后续二十五批共 200 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,071 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-modifier-destroy-verification.json`，封存 JAR 为 `build/rogue-modifier-destroy-10891-certified.jar`，SHA `3e4992e286b0c3a864ffafe2321bd963b0bf1c93ced9d50764aef2f16ae92109`。

本批实现普通销毁控制顺序与已有效果清理；_OnModifierDestroy 完整内部、BaseModifierInstance.Destroy 内部、真实实体/World 注册与全局事件分发仍待完成。辅助函数原件完整不等于内部已实现。完整属性撤回、宇宙/剧情/活动及客户端验收继续待完成。未安装原生热更新或泛型容器运行时，151 份协议快照和版本基线不变，宇宙 partial；未启动客户端/参考二进制，未修改运营数据、资源或配置。
