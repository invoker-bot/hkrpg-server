# 4.6 单监听数组查询与默认比较器转发

Java 21 `RogueEventSingleArraySearch46` 补入已经匹配的普通 Array.LastIndexOf 校验及默认比较器查询转发。复用现有 Callback 数组与实际多播撤回；不复制监听数据，不改变委托类型或字段。

数组 NULL 先失败。空数组只接受起点 -1 或 0，随后只接受 count=0，直接返回 -1，不取比较器、不要求 World。非空数组先核对 0<=start<length，再核对 count>=0 且 start-count>=-1；参数损坏时不执行比较器。非空 count=0 仍取得明确默认比较器并调用明确虚 LastIndexOf，不擅自提前返回。

调用保留同一数组引用、value（包括空）、start、count；默认比较器取回后为空才失败，默认取值期间修改捕获数组元素会被后续查询观察。虚查询的原始返回值直接返回，不统一规范为 -1。失败保留数组改动并停止后续。此边界本身没有遍历，因此不把多播有限遍历的 255 限制加到合法数组/区间参数上；实际比较器仍须自己限定执行范围。

35 项新增 Java 与 1,682 项暂存联合回归（43 类）、8 项 Python 来源检查通过：空数组 -1/0 与非法值、起点先于数量、完整/部分区间、非空零次转发、默认比较器为空或失败、数组变更与失败前缀、任意返回哨兵、4,096 数组参数、实际多播撤回 → 新查询边界 → 已有实际委托相等。比较器适配在测试中执行实际相等，未安装原生比较器。既有生产行为先失败为 0。

冻结证据 `src/test/resources/fixtures/rogue-event-single-array-search-4.6.json` 保留 2 个完整普通辅助体共 435 字节（校验 177、分发 258），4 个完整多播来源调用函数 2,023 字节，1 个完整默认比较器取值来源 389 字节（尚未实现），1 个明确 Array 方法声明与 1 个 MethodSpec。RIP 0x1c2524a3 → METHOD 槽 0x9498300；实际 kind 6 用法原件、MethodSpec 与实例数组参数核对到 Array.LastIndexOf / Delegate。classInst=121、methodInst=-1 按原件保存，不因 Array 通常并非泛型类就改写元数据角色；同数字目的索引的 TYPE/STRING 原件不混作 METHOD 用法。两个普通辅助体仍没有独立注册方法身份，未用原件槽位冒充独立函数地址。

全量 `scripts/build.ps1`、11,100 项 Java（427 类）通过；既有 717 项 Python 全量与后续三十一批共 248 项独立检查通过（未重跑完整 Python 集合，既有脚本文件哈希未变）。3,107 个源码文件、151 份协议快照核对通过；结果为 `build/rogue-event-single-array-search-verification.json`，封存 JAR 为 `build/rogue-event-single-array-search-11100-certified.jar`，SHA `6ab92b473e6207fcef26a31b7cdb763aae6199e512db131bea9eaf248f89f053`。

原生默认比较器工厂/静态缓存/类型初始化、比较器虚 LastIndexOf 的处理体、完整 CLR 泛型数组、实际世界注册与客户端验收仍待完成；完整销毁事件、属性撤回、宇宙/剧情/活动继续待完成。151 份协议快照与版本基线不变，宇宙 partial。不启动客户端/参考二进制，不修改运营数据、资源与配置。
