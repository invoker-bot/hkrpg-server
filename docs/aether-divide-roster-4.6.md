本批芯片装卸已扩展旧编队存档约束为原灵/晋阶的1基类型槽，见[芯片装卸说明](aether-divide-skill-core-4.6.md)。本页0..3装备索引描述只属于前批未接装卸的历史范围，六槽编队及短数组替换规则继续保留。

# 4.6 以太战线资料与编队

Java 已接通实验 4.6 的资料查询、编队替换和当前编队切换。持有灵单独保存在玩家文档，碎片及芯片数量按本次查询锁内的实际材料背包投影。只拥有碎片不会获得灵；查询不创建持有记录、奖励凭据或存档写入。

| 请求 / 响应 | 命令 | 当前行为 |
| --- | --- | --- |
| GetAetherDivideInfo | 4848 / 4814 | 已保存灵、实际库存芯片、六个编队及当前槽 |
| SetAetherDivideLineUp | 4840 / 4808 | 保存已持有成员；0..4 项数组按本服完整替换语义补齐四位 |
| SwitchAetherDivideLineUpSlot | 4818 / 4838 | 切换 1..6 槽；相同槽不重复保存 |

新角色资料默认为空持有集合、六个四位零编队和当前槽1。trainer 与 infiniteMission 为未接通时的0；没有照搬旧参考项目的6010/6006默认授予。获取灵、升级、芯片装备、战斗、挑战、场景及奖励流程继续待完成，本页不把这些流程算入已验证范围。

## 来源和字段

完整来源夹具为 `src/test/resources/fixtures/aether-divide-roster-4.6.json`，SHA256 `39230a643489ee248812836830154a5433a093e1b3aa315de9deaf068588333d`。它保留固定 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的六个完整原表，以及现有运营资源的完整序列化字节。两种已知序列化的完整 JSON 对象相等，物理字节及 SHA 各自记录，没有把格式不同的文件写成逐字相等。运行时只接受这两种已核完整 SHA，依赖每次请求重新校验，不从 ignored references 回退。

原表共19种灵、114条晋阶、32种芯片、5条训练师、5条常量及42个挑战条目。本批只使用资料与编队相关闭包；读到全部原表不代表完成挑战。原文件没有安装或改写。

本机 GameAssembly SHA256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，metadata 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。夹具包含27个完整原生函数、8101字节和5份完整 Lua 字节码、19323字节。客户端没有启动或修改，Lua没有执行；普通 UI 是否始终构造四项请求尚未验证。

完整 GetInfo 消费者、各消息 Writer、六槽和四位构造器共同确认下列映射；没有使用参考协议注释中的错误对象内存偏移代替真实 Writer。

| 消息 | 已核字段 |
| --- | --- |
| GetInfoScRsp / GHNMFCHEGGK | retcode6；lineups4；currentSlot10；infiniteMission12；trainer13；coreList14；spiritList15 |
| Spirit / GHBICGMEFPL | promotion3；fragmentStock4；SP6；ID10；exp11；passiveSkills13 |
| Core / DMGDLEHEDFB | itemID6；dressedSpirit8；count13 |
| Lineup / OCCAABPFBLF | slot2；四位成员10 |
| SetScRsp / AAOABMHALOJ | lineup1；retcode7 |
| SwitchScRsp / OBIOEKFELPL | currentSlot12；retcode9 |

GetInfo field11尚未解释，本批没有将它当作训练师或其他已知状态。原生 SyncInventory 将 Spirit field4 映射到完整灵表中的 ExpItemID；6001对应250300。数量与持有资格分开，返回当前背包数量，不返回旧存档中的独立提示数。新获得的已知芯片物品可以出现在查询里，不借查询生成灵或奖励。已装备芯片缺实际库存、负库存或损坏持有数据返回错误，不悄悄补货或卸装。

原生 NetworkManager 方法635435完整333字节确认请求复制所给 uint 数组长度，包含短或空数组，不在发送器强制补成四位。本服选择完整编队替换并补0；普通响应 Sync 对短列表不会清除原尾位，因此始终返回四个明确位置。这个替换策略属于本服行为，没有称为已还原的官方服务端省略位规则。禁止重复非零成员和当前0..3装备索引也属于当前本服有限存档约束，芯片装备与精英规则仍待对应流程接通。

## 保存和回归

玩家锁内校验完整来源和旧状态，复制嵌套灵、芯片与六个编队后一次保存；保存失败恢复原 Aether 对象。相同编队或相同当前槽不保存。查询与拒绝请求不改奖励、钱包、库存、队伍或任何持有记录。未登录会话不能获取资料。

`AetherDivideRoster46Test`目前43项。不可变旧258b运行包首先执行32项，31个真实入口行为断言失败、1个完整来源控制通过；这31项主要暴露同一个未接入入口，不能当作31种独立缺陷。独立实现32项通过后，短数组新增4个真实失败与32个控制，通过最小修复变为36项全绿。

实际库存投影的校正失败基线为 `build/aether-divide-roster-draft/inventory-projection-red-v2/verification.json`：6个行为失败、36个控制，无编译、初始化或跳过错误。第一轮负库存夹具误猜6001碎片为250301，实际完整原行是250300；第一轮两条夹具断言失败不计行为RED，原报告和纠正说明保留在 `inventory-projection-first-run-qualification.json`。

最终43项独立编译与实际 JUnit 全绿，见 `build/aether-divide-roster-draft/delivery-green-v1/verification.json`。其中一项真实 Morphia 保存 Player，独立读取两次并经过真实 VersionedPacketHandler 查询，核对完整 GameplayState、六个编队及 BSON 不变；该项使用内存数据库，不称为物理 H2。其余验证覆盖实际背包数变化、短数组/清空/重载、保存失败及同字节重试、损坏/越界/别名请求、完整来源改变或缺失，以及未登录请求。

这轮独立测试的生产类显式来自隔离 owned 输出或冻结258b依赖，排除当前 Gradle main 输出。随后统一专项51类/1308项、全量512类/13954项均通过，2037个编译与资源输入在全量窗口内不变。实际新包为 `build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。

同一新包的实际 KCP 验收为59次请求、212项检查、2次正常停服及1次同UID进程重启；两次物理只读H2检查共独立恢复4个实际Player。完整报告为 `build/aether-divide-roster-current-native.json`，SHA256 `fa058b2720b14c5b15035a27f7bd83868d38e3d79b4eb0cc9d9ac716c5789279`。9个受监测生产CodeSource全部指向这一新包，没有owned覆盖。实测六槽/四显式位置、短数组0..3、清空、十次异常拒绝、重复请求/BSON不变以及重启恢复。旧提示碎片11/0、芯片1不改写，响应投影实际碎片7/2/9及芯片3/4；仅6010碎片不生成持有灵。

持有集合通过明确的隔离夹具种入，不能称为生产捕获验收。碎片、芯片和运营原表未由查询或编队授予。完整来源每次屏障及首末复核；客户端UI、获取灵、装备、战斗和完整活动继续待完成。可重跑的验收工具与布局验证在本批统一记录中列出，见 [本批验证](castorice-daily-chess-aether-4.6.md)。
