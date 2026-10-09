# 4.6 效果列表范围插入与迭代

Java 21 RogueEffectListRange46 补入范围插入的泛型集合、非泛型集合、自身复制和迭代分支，并补入单项Insert与AddRange普通控制。复用References及实际RogueFadeListCapacity46容量设置，不改协议。

范围来源NULL在接收者/索引/绑定前失败，索引用当前数量作UInt32比较。泛型集合优先，非泛型只在泛型未匹配时查询。捕获来源Count后，正数量才读取当前目标数量/存储、按Int32加法决定扩容；回调返回后重新读取当前数量与数组。先移动后段，再捕获目标数组供CopyTo；自身插入用两个复制，第二次重新读取当前数组/数量。完成后在当前数量加捕获来源数量，最后递增当前版本。非正来源数量只递增版本，不要求存储。

枚举中间插入逐项调用实际Insert，每项成功后才移动插入位置；释放成功后再加范围操作版本。Append分支调用实际AddRange，AddRange先加版本、再要求来源并取得迭代器，逐项追加不逐项加版本，返回后范围操作再加一次。Insert在数组写成功后才一起递增数量与版本；AddRange在数组边界检查前就发布数量增加，失败保留这一前缀。容量/复制/Current等回调变化保留，复制不以初始快照代替当前状态。

核对MSVC FuncInfo、try/handler/IP状态表、两处catch辅助完整边界和Il2CppExceptionWrapper RTTI。取得迭代器在捕获区域前，捕获区域内失败先保存异常，再释放非NULL迭代器，最后重新抛出原异常；释放失败替换原异常，不执行最后版本写。ProjectedException明确表达原生wrapper中可能为NULL的存储异常，范围分支捕获NULL后释放并落入追加分支，AddRange捕获NULL后释放并返回。Java RuntimeException作为普通游戏异常的有限投影；Java Error不在此投影捕获范围。没有安装C++异常ABI/personality、CLR虚调用或类型初始化。

51项新增Java与1,985项暂存联合检查（51类）、8项来源检查通过。覆盖集合优先、非正数量、回调修改、扩容、三个自身复制、部分复制失败、版本溢出、逐项插入/追加不同版本、取得/迭代/释放异常、NULL迭代器、原生wrapper NULL存储异常以及失败后的状态；实际双载荷初始化 → 实际范围插入 → 实际容量设置联合验证。既有生产行为先失败为0，分配/复制/迭代4096限制为明确有限投影政策。

证据保存5个完整共享辅助5,311字节、14个已核对流程辅助2,586字节、3个独立ICollection/IEnumerator/IDisposable类槽、56处RIP绑定、14份原生异常状态/RTTI记录及效果泛型和List模板声明。原始MethodSpec定义12710为AddRange，保留该名称及原始classInst/methodInst角色；实际共享辅助控制根据完整体核对，没有把携带的MethodInfo或模板声明当成完整共享辅助注册证明。

全量 `scripts/build.ps1`、11,403项Java（435类）通过；既有717项Python全量与后续三十九批共312项独立检查通过（未重跑完整Python集合，既有脚本文件哈希未变）。3,155个源码文件、151份协议快照核对通过；结果为 `build/rogue-effect-list-range-verification.json`，封存JAR为 `build/rogue-effect-list-range-11403-certified.jar`，SHA `a620b0cd372c23238904852263de164c1eb51b0b3620135c78a8b6af3f02e529`。

共享辅助注册/rgctx、泛型集合与枚举虚调用、类型数组工厂、Array.Copy完整体、共享空数组和CLR类初始化、真实World仍待完成。完整战斗/宇宙/剧情/活动及官方客户端验收未完成。151份协议快照与版本基线不变，宇宙partial，不修改客户端或运营数据、资源、配置。
