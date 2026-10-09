# 4.6 附加战技点登记消费与耗尽请求

本批 Java 21 `RogueAdditionalBpCost46` 复用已有 StackEvent.stackedAdditionalTeamBp 和 Cleanup/Replacement 生命周期。消费参数通过原始方法行、参数行和类型记录独立核对为 Int32；Destroy 参数为 ModifierDeathSource，Exhausted 枚举独立核对为 4。消费时不读取队伍，不建立第二份登记数据。

入口列表为空或第一次 count 为零时直接结束，不请求销毁。之后重读列表及 count，正数从末尾向前检查所有项；每次读取、移除和最终 count 均使用当前列表。登记 ID 相同才删除，保留其他 ID 的相对顺序。最终列表为空或 count 为零时请求 Destroy(sharedCleanup,4)。负数 count 跳过循环，保留原生条件；有限正数上限 255。显式值结构 AdditionalBp(null,0) 是 ID=0 的记录；Java 引用 null 或其他对象不是已核对的值结构，因此拒绝投影，不补默认记录。

消费函数本身没有生命周期防重条件；销毁请求是否结束由销毁函数处理。删除失败保留此前逆序删除，销毁失败保留已清空记录；重试遇到入口空列表直接返回。回调中修改列表、列表返回负数计数及整数 ID 边界保留源顺序。没有 finally 或回滚。

30 项新增 Java 与 1,417 项暂存联合测试（36 类）、8 项 Python 来源检查通过。实际特殊点释放调用本批消费，特殊标志和关联先清、登记移除、耗尽请求以及销毁失败后停止通知联合验证；其余登记存在时不请求销毁。新增能力不计为已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-additional-bp-cost-4.6.json` 包含完整消费函数 312 字节、完整销毁辅助来源 964 字节、3 类字段、1 个销毁方法身份、1 份 PoolablePooledList<DGOAGABJAPC> 泛型参数记录与 2 份原始方法参数记录。来源检查还复核此前点数撤回和释放，源字段 504 与已有登记值结构独立对应。

全量 `scripts/build.ps1`、10,835 项 Java（420 类）通过；既有 717 项 Python 全量与后续二十四批共 192 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,065 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-additional-bp-cost-verification.json`，封存 JAR 为 `build/rogue-additional-bp-cost-10835-certified.jar`，SHA `1a2fac3aaf17108c0f9647e87bbb03992353e4fc789ff4cadcf1108911e0f422`。

完整 Destroy、属性撤回、实际 World 注册和全局通知仍待完成；完整宇宙、剧情、活动与官方客户端验收待完成。964 字节来源完整不代表销毁内部已经实现。未安装原生热更新/泛型容器运行时，未新增网络验收，151 份协议快照和版本基线不变；宇宙仍 partial。未启动客户端或参考二进制，未修改运营数据、配置或资源。
