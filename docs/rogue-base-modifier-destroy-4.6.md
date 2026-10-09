# 4.6 基类销毁与两个事件监听撤回

Java 21 `RogueBaseModifierDestroy46` 使用已有 Destroy.State 与共享 DeathSource；首先写入入口原始 reason，再通过明确的虚调用绑定取得 owner ability component、实体和世界，捕获世界 EventManager。基类函数没有独立 state 防重条件，普通 Destroy 的 state>1 条件由此前实际函数处理。

读取捕获实体当前 UInt32 ID，分配第一份 OnEventCallBack 投影并读取 _OnOtherModifierDestroy 方法信息。依源顺序写 method_ptr、method、m_target、invoke_impl、method_code、extra_arg，target 与 method_code 均引用同一个修饰器 State，extra_arg 的 RIP 目标为 0x3d10。全部写入之后才要求捕获的 EventManager 非空，并撤回事件 207。成功后重新读取同一实体当前 ID、分配第二份委托，绑定 _OnOtherModifierAttachEffectFadeOutFinish，沿同一个捕获 EventManager 撤回事件 208。

两个事件的身份和枚举值独立核对；调用实际为 UnRegisterEventListener，不是事件注册或异步任务排队。第二次不重查 owner、实体、世界和 manager，第一次回调改变这些成员时仍使用捕获引用；ID 的全部 UInt32 位值通过 Java int 原样传递。分配发生在读取方法信息之前；投影方法身份不符或方法信息为空时拒绝，不安装原生委托对象、方法指针或 0x3d10 桥接体。

失败不回滚 DeathSource、已初始化的委托或第一份监听撤回；第一次撤回失败不会读取第二次 ID。实际普通 Destroy 调用本批基类体，验证已清理的效果、子列表头部与事件撤回；基类再次写原始 reason，覆盖先前回调留下的 reason。31 项新增 Java 与 1,504 项暂存联合回归（38 类）、8 项 Python 来源检查通过，新增能力不计为已安装生产行为先失败。

冻结证据 `src/test/resources/fixtures/rogue-base-modifier-destroy-4.6.json` 包含完整主函数 421 字节、3 个完整辅助来源 712 字节、6 类字段、4 个方法身份、2 个回调 METHOD 槽、1 个委托 CLASS 槽、5 份 RIP 地址与 6 份原始方法参数记录。委托继承字段偏移 16/24/32/40/56/64、GameWorld._EventManager=88、实体 ID=192 独立匹配；UInt32 参数是 kind 9，不错误标作 CLASS 类型。

全量 `scripts/build.ps1`、10,922 项 Java（422 类）通过；既有 717 项 Python 全量与后续二十六批共 208 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,077 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-base-modifier-destroy-verification.json`，封存 JAR 为 `build/rogue-base-modifier-destroy-10922-certified.jar`，SHA `0fb8d95d455fa749c5a4b59cc1028ab59761e9a1b4ed51b1abcf4660c17eaf42`。

EventManager.UnRegisterEventListener 内部、回调分发、原生委托工厂、虚 owner getter、真实 World 与官方客户端验收仍待完成；辅助来源完整不代表内部已实现。销毁事件完整内部、属性撤回与完整宇宙/剧情/活动继续待完成。151 份协议快照和版本基线不变，宇宙仍 partial。没有启动客户端/参考二进制，未改运营数据、资源或配置。
