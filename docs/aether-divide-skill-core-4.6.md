# Aether 技能核心有限装卸

本批只接已有持有灵与真实材料库存的空槽装备、卸下及查询恢复。它不授予持有灵或芯片，不消耗库存，不接战斗能力执行，也不认定真实客户端界面已经验收。

原始来源是 TurnBasedGameData 固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的六张完整表，与此前 roster 使用同一组运营 SHA/原 Git SHA；完整 JSON 对象相等。`aether-divide-core-4.6.json` 包含 52 个完整原生函数、11,257 字节及五份整 Lua 原件、34,095 字节，SHA256 `ffe44ab7c1a87209b07543a5d622f6074884851cc6d9da4a715b0a00b1c7e208`。本机 DLL/metadata 沿用已经独立核对的固定 SHA；没有运行客户端、Lua 或引用仓库二进制，没有把这些文件作为运行依赖或安装到运营目录。

实际请求 builder 635433 `NetworkManager.EKCHEDPCDLD` 的完整 137 字节发送 4847；实际类型槽 `0x9393fa0` 对应 DAKIMICINOH，完整 Writer 150645 匹配 avatar3、slot12、item14。635434 `IBCGAECALGA` 完整 122 字节发送 4823，类型槽 `0x93bc998` 对应 PLAFIBPGBKK，Writer 150689 匹配 slot1、avatar7。装备的 4825 回包是 EquipSkillCoreScRsp，不能从请求名称字符串替换成不存在的回包名。

两份成功回包 Writer 各 307 字节，字段分别是 ret/core/spirit 1/9/15 与 14/4/8。两份完整 301 字节消费者通过真实类型槽确定回包类型，要求非空 core 与 spirit；随后调用 SyncAetherMonsterAvatarData、slot.Sync 及 UpdateSkillCoreBelongStatus，装备写目标灵 ID，卸下写 0。成功回包因此包含完整 spirit/SP/passive entries 与按真实库存投影的 core；保存失败或拒绝只给错误码，没有伪造成功子结构。消费者局部没有独立的 retcode 分支，本批不推断整个客户端错误分发链已经验收。

SlotData 构造 574567 按原 PassiveSkillSlotList 创建 **1 起始**的槽。574572 `_GetUnlockPromotion` 扫描该灵原 Promotion.Slot；574568 Sync 仅开放已达到晋升的显式槽。6001 的原列表为 Trick/Assist，槽 1/2 在晋升 2/4 开放；6010 的第四槽是 Defend，在晋升 5 开放。其他灵使用各自整表声明，不能套统一晋升规则或 0..3 范围。枚举 Defend=0、Assist=1、Storm=2、Trick=3 已从原 metadata 常量读出；原 PassiveSkillRow 构造是完整 RET，省略类型保留原零值 Defend。

存档校验同步拒绝 0、未声明槽、尚未开放槽以及不匹配类型；合法原第四槽可恢复。不会自动迁移旧 0 索引，因为把它重写为 1 可能绑定错误核心。材料数量取本次 Player 锁内的实际 Inventory，旧 hint count 不冒充所有权或回包库存。一个核心只能有一个存档归属；同绑定重复装备不保存，空槽重复卸下明确拒绝。已占用槽覆盖、直接跨灵转移暂时拒绝；先显式卸下再装到原型兼容且开放的空槽可行。Lua 原件包含准备 detach/equip 列表与确认流程，但 opcode 和真正界面流程没有执行，不能声称覆盖/转移 UI 闭环。

候选把 skills 与 dressedSpirit 放在同一个 Aether 玩家子文档候选中；只调用一次 Player.save。抛出保存错误恢复旧对象引用及全部旧值；在保存成功以后才返回完整成功回包。普通钱包、真实材料、普通队伍和其他玩法状态不参与这次修改。它没有证明进程崩溃时的跨集合原子性。

## 已执行验证

唯一基线产物是 `build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。

- `immutable-f586-red-v5`：31 项新回归，28 个真实 AssertionFailedError 与 3 个有效来源/原槽 Query 对照；0 errors/skips/aborts。九个生产 CodeSource 均来自该 jar，无 mutable main 输出。
- `owned-green-v3`：四个 owned Aether 类显式 javac overlay，31 项新用例加完整旧 43 项，共 **74 项通过**。五个相邻生产类仍来自同一个 f586，四个测试 helper 有独立封存 SHA。输入前后相等。
- 覆盖真实 Versioned 入口、1 起始/类型/晋升槽、真实库存归属、完整成功回包、错误不发成功子结构、保存失败旧对象准确回退与同请求重试、重复 Query/装备、显式卸下后跨灵再装、来源破坏与空请求拒绝。包含保存后 memory Mongo/Morphia 两次独立实际 Player 读取及相同 UID 的服务查询；此项不是 H2 或真实网络验收。
- 旧 43 项全部保留。仅把一个 missing-stock 夹具从无源 0/Storm 槽调整为原 6001 晋升 2 的 Trick 槽 1/250202；先成功 Query，移除真实库存，再保留原错误码/零保存/零通知断言。

早期证据保留：v1 有 15 个匿名 Player 的 Gson 快照夹具异常与 12 个断言，不能作为完整 RED；v2 的第四槽误配 Assist，单条无效来源预期已明确剔除并用原 Defend 核心重跑；第一次实际 Player 读取比较了 JSON 对象键顺序，完整结构语义实际上全等，已改为不丢字段的结构比较。上述错误没有靠放松生产来源验证修正。

根代理已完成正式全量构建：516 suites / 14,106 tests，failure/error/skip 均为 0；2,573 个编译/生成类/资源/构建输入稳定。最终 `build/out-followup-core-material-runtime.jar` SHA256 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab` 与装卸 Native 使用的 candidate 逐字相同。此前 roster 的 59/212 和 f586 的 512/13,954 仍属于前一批，不并作新装卸验收。

## 运行依赖与接入

运行代码只读 Java 已安装 operator 六张表，并沿用固定 SHA。没有 references、客户端目录或原生 proof 的运行回退。现有 VersionedPacketHandler 已调用 AetherDivide46；四个 owned 类的修改足够，不需要 GameplayState、Player、Scene、默认安装或其他中央挂钩。

## 同产物实际 KCP/H2

`build/aether-divide-skill-core-current-native-v2.json` SHA256 `720f00d6b35a653468270e3253bbb10888a9ad7b746365cba0460bcee6ce9533`，独占目录 `build/smoke/aether-divide-core-2lnltv9l`。两进程累计 **48 个实际请求、319 项检查、13 个拒绝请求、2 次正常 stop、1 次同 UID 重启、2 次物理只读 H2 检查及 4 次独立实际 Player 读取**。第一进程 31/176，重启进程 17/143。九个生产 CodeSource 都属于上述最终同 SHA 包，没有 runtime overlay；2,253 个运营 Excel 文件的 SHA 在开始/各屏障/结束核对。

隔离夹具显式持有 6001 晋升 2 与 6005 晋升 3，真实材料 250300=7、250304=2、250309=9、250201=3、250202=4、250204=2；授予前后完整实际 Player、物品、Gameplay delta 独立保留，不能解释为生产灵捕获或核心获取。实际路径为普通登录和空 Query→固定夹具→原 Trick 6001 槽 1 装 250202→同绑定重复/占用替换与直接跨灵拒绝→正常停服/H2→同 UID 恢复→4823 卸下→重复空卸下拒绝→另一原 Trick 6005 槽 2 显式装备→正常停服/H2。成功回包完整包含 core/spirit/SP/passive entries；十个 Query 和全部拒绝/幂等窗口的完整 live/BSON、钱包、真实材料、普通队伍/角色及其他 Gameplay 都不变。装卸窗口只允许实际绑定及首次核心 hint 增量。

重启登录有独立的 `checkpoint-restored` 夹具：正常调用一次真实 Player.save，把原登录末尾仅在 live 更新的 `lastActiveTime` 从已存 1791763856 刷新为 live 1791763868。全部 before/after/delta 原件保留并精确比较，仅这一个已知待写字段改变；它不是只读屏障、核心行为或 KCP 保存故障，查询零写/零钱包/零材料结论从 checkpoint 后的新基线计算。未知字段、库存、队伍、核心及其他 Gameplay 差异仍拒绝，没有 broad ignoredFields。

第一次失败 attempt `build/smoke/aether-divide-core-75plspb3` 及全部回包/日志/快照保留：初程 31/176、1 次正常 stop/1 次 H2 通过，重启 Query 与真实卸下帧成功，但旧观察器把待写登录时间一起落盘误认成核心污染；异常传播到旧 Console 循环，导致 stop 未被消费，最后由成熟工具安全 fallback 终止。该重启不是正常恢复验收，也不计入 v2 通过数。v2 工具修正为先写实际完整快照再断言，并写独立 ERROR marker 后返回 Console；wrapper 快速结束独占 probe 并正常 stop，不把错误当成功 ACK。29 个离线工具 guard 与纯 javac/15 schemas 预检通过；这些工具修复没有修改正式生产源码。

正式工具为 `scripts/smoke-aether-divide-skill-core.py`，四个 helper 位于 `scripts/fixtures/java/`，守卫为 `scripts/tests/test_aether_divide_skill_core_native_guard.py`。实际执行命令：

```powershell
python scripts/smoke-aether-divide-skill-core.py --jar build/out-followup-core-material-runtime.jar --output build/aether-divide-skill-core-new-report.json
python scripts/tests/test_aether_divide_skill_core_native_guard.py
```

报告名必须全新；这里的 new-report 只是未来独立运行示例，不是上述已执行 v2 报告。`nativeSaveFailureInjected=false`；保存抛错的准确回退/重试由单元回归覆盖，未向真实服务器注入未有安全方案的故障。覆盖/直接转移 UI、完整客户端菜单、战斗效果、生产灵/芯片获得以及崩溃原子性仍未验收。Lua opcode 没有执行，完整 Writer/consumer 静态对应与 KCP API 不等于 GUI 接受。
