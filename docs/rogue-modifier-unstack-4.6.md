# 4.6 普通修饰器撤回整合入口

本批补入 Java 21 `RogueModifierUnstack46`。State 把现有叠层撤回、名称/绑定/AI/上层登记和拥有参数引用到同一修饰器；构造时要求登记与拥有参数共享 Cleanup，且叠层与 Cleanup 共享同一 replacement 对象。没有重建两份层数/寿命状态。

完整原始 UnStack 510262/0xe8146a0 的普通顺序为：属性、弱点、附加队伍点数、增益回调、特殊战技点登记、名称、绑定值、AI、上层分组、角色变化（immediate=true）、离场、不可选中。Java 调用已安装的真实叠层、登记和拥有参数函数体；属性内部与特殊战技点模块内部仍要求明确 World 绑定。任何步骤抛错都会停止后续步骤，没有 finally 清理或回滚已完成的前缀。

_StackBpDirty 是嵌入值结构 HPHEBLCCOOK，三成员为 TeamDataComponent、TurnBasedModifierInstance 和 Int32；元数据 16/24/32 是装箱投影，嵌入修饰器字段 280 后为有效 0/8/16。普通原件读取第一成员：团队为空时跳过整个清空，保留其他两个成员；团队存在则读取其 BoostPointModule（字段 32），模块存在时传当前修饰器实例，而非脏值里的旧修饰器成员；模块为空或模块移除正常完成后，将整组 24 字节清零。回调替换当前值也被正常尾部清空，失败时保留回调写入，没有回滚。

37 项新增 Java 与 1,293 项暂存联合回归（33 类）、8 项 Python 来源检查通过。覆盖十二个阶段各自抛错，列表/绑定载荷/AI 缓存/已回收参数/角色变化通知的真实前缀保持；实际 BindingPayload.Clear 被调用；实际 Cleanup.endTurnSequence 成功时才继续特效清理/延后释放，中途撤回失败不清效果、不释放后续参数。上层 ID 源列表按原件没有清空，因此重复成功入口仍遍历其 IDs，不能宣称入口全部幂等。新增能力不计作生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-modifier-unstack-4.6.json` 包含 1 个完整 PE 边界主入口 252 字节、2 个已取完整原件的辅助体共 3,155 字节（属性 2,832、特殊模块移除 323）、3 类字段和 12 个额外方法身份；只读脚本还独立复核此前叠层、登记和拥有参数来源。两个辅助原件的完整字节已核对，函数体尚未实现，报告分别保存 false，不把辅助函数来源完整当作玩法完成。

全量 `scripts/build.ps1`、10,711 项 Java（417 类）通过；既有 717 项 Python 全量与后续二十一批共 168 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,047 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-modifier-unstack-verification.json`，封存 JAR 为 `build/rogue-modifier-unstack-10711-certified.jar`，SHA `270e97bf038f0d57a404f5078b3ebab025eac29c86bcd9b517abb4ee34460c74`。

完整属性撤回、特殊战技点模块内部、实际 World 注册、完整 UnStack/AddModifier 与全部宇宙/剧情/活动仍待完成。热补丁分支不在普通入口范围。本批没有新增网络或游戏 UI 验收，151 份协议快照和版本基线不变，不执行客户端或参考仓库二进制，不覆盖运营资源、配置或数据库。
