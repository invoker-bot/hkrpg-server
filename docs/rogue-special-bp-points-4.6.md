# 4.6 普通战技点尾部撤回与特殊点释放

本批补入 Java 21 `RogueSpecialBpPoints46`，扩展已有特殊战技点 Point/Points 投影的 previous、version 和原始点值成员，复用同一个模块 State、登记总量、点数计数和 Cleanup.Candidate 生命周期。没有复制第二份状态值。

普通点撤回从当前 head.previous 开始；includeSpecial=true 直接移除尾点，否则由后向前寻找 special & 1 为零的首项。特殊尾点 previous 为空直接结束，不读 owner；previous 存在时读取当前点 owner，当前点等于该 owner 当前 head 时停止。节点移除先确认 owner，依次写 next.previous、校验/写 previous.next、必要时换 head；单节点直接清 head。之后清 previous/next/owner，再 size-- 与 version++。原生计数向量原件为 (-1,1,0,0)，保留 Int32 溢出；失败可能已改 next.previous，不回滚。

特殊点释放先检查特殊点数为非零，再读取当前 size 减第二次特殊计数。读取 Nullable 最大值或兜底值，并取当前有效登记总量，差额大于零时从当前 head 向前释放。转换次数先捕获；跳过普通点，特殊点先将整个标志字节写零、清关联修饰器，再对 Alive=1 的原修饰器报告 OnCostAdditionalTeamBp(registrationId)。关联对象使用已存在的 Cleanup/Candidate 状态，Alive 枚举及 BaseModifierInstance 的继承字段 144 独立核对。其余点值成员、节点链接和列表 size/version 在转换中保持原样。

每次转换后仍核对当前 next 与当前节点 owner.head，之后才检查捕获的释放次数，最后一项转换后 owner 缺失也会失败。回调修改 next、owner、最大值和生命周期保留已完成转换；回调失败后本点已清，不会重试旧回调，也不会继续后续通知。有限边界为 255 个节点；通用闭包链表运行时与原生对象身份没有安装。

41 项新增 Java 与 1,387 项暂存联合回归（35 类）、8 项 Python 来源检查通过，包括反向尾点筛选、双向链接半完成失败、外来 owner、整数计数溢出、正向释放、活动/非活动共享状态、先清后回调、回调换 next/owner、满足最后释放次数后的 owner 错误、失败重试及 255 节点边界。实际特殊模块变化函数调用本批普通点移除和刷新，登记已移除、点转换、消费回调、整数通知前后顺序联合验证。新增能力不计作已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-special-bp-points-4.6.json` 包含 2 个完整 PE 边界主函数 710 字节（移除 171、刷新 539）、1 个完整节点移除辅助体 219 字节、1 个完整消费辅助来源 312 字节、4 类字段、1 个方法身份与 1 份 16 字节计数原件。节点辅助体未匹配注册方法身份；消费函数的原件完整不代表其内部已实现，报告分别保存 false。只读脚本还复核此前特殊模块和整合撤回的来源。

全量 `scripts/build.ps1`、10,805 项 Java（419 类）通过；既有 717 项 Python 全量与后续二十三批共 184 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,059 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-special-bp-points-verification.json`，封存 JAR 为 `build/rogue-special-bp-points-10805-certified.jar`，SHA `203a851d5a76506e7bee2aa695269d81272a34dbfaaa8ac5b31e654c57dea4e9`。

OnCostAdditionalTeamBp 内部、全局事件分发、原生点工厂、实际 World 注册、完整属性撤回和完整 UnStack/AddModifier 仍待完成，全部宇宙/剧情/活动与游戏客户端验收继续待完成。本批没有新增网络或游戏 UI 验收，151 份协议快照和版本基线不变，宇宙继续 partial；不启动客户端/参考二进制，不覆盖运营数据、资源或配置。
