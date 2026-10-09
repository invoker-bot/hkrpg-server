# 4.6 池化回调列表构造与处理器转发

Java 21 RogueEventPooledCallbacks46 补入PooledList的普通构造控制及AddRange、Count、Item、Release转发控制，复用现有PooledCallbacks.collection，不改变字段或协议。已匹配五个完整通用辅助、明确的方法声明及调用方MethodSpec，不把无独立登记的辅助地址冒充方法登记身份。

构造先要求接收列表，通过明确绑定选择一次分支，再分配对应处理器、调用其构造，最后才写collection。分配返回NULL仍传入构造绑定；失败保留绑定的已完成修改，成功才发布所捕获处理器。分配期间选择条件发生变化不切换已选分支。真实静态IsClass槽、两种具体处理器类型及类初始化仍待独立核对，当前不凭字段名字补猜。

四个转发方法先从接收列表捕获字段16的非空处理器，再调用明确接口边界；处理器在回调期间更换时不改变本次捕获对象。AddRange原样传递源PooledCallbacks（允许NULL），不提前读源collection。Count不裁剪负数或超大返回值，Item不提前限制索引且保留NULL结果，Release原样传递TRUE/FALSE，不清除包装列表字段。接口原生槽与具体IPooledList函数体尚未接入。

37项新增Java与1,873项暂存联合检查（48类）、8项来源检查通过。覆盖两种构造分支、构造失败前缀与最后发布、NULL分配、静态选择捕获、四个转发的接收者/处理器过滤、同一处理器和替换/异常前缀、空源、原始索引/数量/标志；实际FireEvent → 实际PooledList包装控制 → 有限处理器夹具 → 实际队列出入队联合执行并重复复用同一列表，回调失败保持副本且不归还。有限处理器是明确夹具，不计为IPooledList原生实现。既有生产行为先失败为0。

冻结证据保存5个完整普通辅助1,955字节、6个事件分发与队列辅助1,918字节、管理器字段、PooledList/IPooledList两份模板、5份方法声明/MethodSpec/RIP绑定、两份泛型来源。_processor模板为IPooledList<T>，参数是VAR19/1578；事件管理器闭包PooledList<OnEventCallBack>的56692参数来自另一份原件，不把模板VAR反写成闭包参数，也不将模板0偏移当作物理字段16。

全量 `scripts/build.ps1`、11,291项Java（432类）通过；既有717项Python全量与后续三十六批共288项独立检查通过（未重跑完整Python集合，既有脚本文件哈希未变）。3,137个源码文件、151份协议快照核对通过；结果为 `build/rogue-event-pooled-callbacks-verification.json`，封存JAR为 `build/rogue-event-pooled-callbacks-11291-certified.jar`，SHA `cdf94470ac1334ff393504661bbd5dca05fbabddfc6cc2077dcc5b02c2971643`。

内层具体处理器的增删、枚举、释放和池生命周期、IsClass静态绑定、两种处理器类、IPooledList虚槽、泛型初始化与真实世界登记仍待完成；Release(TRUE)完整含义未匹配。完整战斗/宇宙/剧情/活动及官方客户端验收未完成。151份协议快照不变，宇宙partial，不改客户端、运营数据、资源或配置。
