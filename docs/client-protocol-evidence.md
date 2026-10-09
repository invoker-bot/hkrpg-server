宇宙待选动作追加：5676在解析前校验原始uint32、重复单值、oneof和嵌套长度，异常请求保留菜单/货币/拥有记录；37项回归中22项先失败，合法祝福/奇物及省略批次重选保留。钟表小子共用验证器。完整玩法与游戏UI仍待完成，见docs/universe-pending-wire-4.6.md。

# 本机 4.6 客户端静态协议证据

核对对象为用户提供的国服 `hkrpg_cn` 4.6.0 安装目录。只读解析 PE 与 IL2CPP 元数据，未启动、加载或修改客户端 DLL；没有抓包和客户端 UI 验收记录。此文档记录可复核的字段证据，不能作为完整玩法验收。

## 固定输入

| 文件 | SHA-256 |
|---|---|
| GameAssembly.dll | `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3` |
| global-metadata.dat | `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130` |
| startup-metadata.dat | `78d090a64d8375574db2e36721e38994265c23ca8ac398ea97061523f2166034` |

`global-metadata.dat` 使用 MHY 头，不能直接交给标准明文元数据解析器。解码常量来自此 DLL 的初始化、类型名读取和方法表读取函数：静态头 RVA `48b19b0`，类型跨度 70 字节，方法跨度 26 字节；方法地址表由代码注册 RVA `43f9fa0` 的 `+0x40` 指向 RVA `4931760`。脚本交叉核对 StartRogue 的 WriteTo、NetworkManager 的开始调用、RogueBuffSelectAction 的选择调用，再导出指定类型的方法。此客户端还原出 82,342 个类型。

可复现工具为 `scripts/inspect-client-protocol.py` 和 `scripts/analyze-client-46.py`。后者依赖 Python 的 `pefile` 与 `capstone`，严格校验输入哈希；其他客户端或补丁版本必须重新分析，不得沿用常量。输出是反汇编窗口，需要结合调用和序列化函数核对；附近代码不能直接归为同一个方法。输出留在忽略的 `build/`。数组元素、父类型、方法/字符串槽及设计资源定位的复核边界见 [设计数据读取说明](client-design-data-4.6.md)。

```powershell
python scripts/analyze-client-46.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --types OFEDNNJIJFL BPLNKHGAILK INHBMHCJJCN DCFPEIMAMCN GGGBAIAPDLO KJAMNOGFAMJ LEFAKCMDGNB DLNANAEDDEE RogueBuffSelectAction RogueAdventureModule --method-names WriteTo SendSelectRequest _OnCmdSyncRogueCommonPendingActionScNotify _OnCmdHandleRogueCommonPendingActionScRsp --disassemble 4000 --output build/client-46-pending-evidence.json
```

## 祝福选择

| 对象 | 本机证据 | 与已固定描述符的对应 |
|---|---|---|
| 请求命令 | `RogueBuffSelectAction.SendSelectRequest` RVA `dee9860`，SourceType=1 调用 `d9a8f70`；该函数发送 `0x162c` | 命令 5676，类型 `OFEDNNJIJFL` |
| 批次 | `d9a8f70` 将动作队列位置写入对象 `+0x24`；WriteTo RVA `1de0e070` 写 tag `0x28` | `MPLGEEGOAMC`，字段 5 |
| 选择分支 | 调用写 oneof case `0x5c2`；WriteTo 写 varint tag 字节 `92 5c` | `DLKIKBIDJDB`，字段 **1474**，类型 `BPLNKHGAILK` |
| 祝福 ID | 内层 `+0x18`；WriteTo RVA `1e01fac0` 写 tag `0x18` | `BAOFIGFFMLN`，字段 3 |
| 待选通知 | `DLNANAEDDEE.WriteTo` RVA `1e1baeb0` 写字段 2、6；本机通知接收 RVA `dedf550` 使用模式及动作 | `SyncRogueCommonPendingActionScNotify` 5670，`DLOJFEEHLIJ`=2、`action`=6 |
| 待选内容 | 工厂 RVA `b712900` 的 case `0x101` 创建 `RogueBuffSelectAction`；它使用 `INHBMHCJJCN` 的队列位置及 detail | `queue_position`=8、`detail`=13、detail 的 `PCHGEGFBKLF`=257 |
| 候选列表 | `GGGBAIAPDLO.WriteTo` RVA `1e44c5f0`，列表字段 11；候选类型与资源祝福读取关联 | `LEOFNINDJDB`=11，`KJAMNOGFAMJ.buff_id`=1、`HAGDKKBAAKK`=4 为等级 |
| 确认回包 | `LEFAKCMDGNB.WriteTo` RVA `1e8f9990` 写字段 9、10、13；接收 RVA `dee05d0` 的成功与失败路径分别使用 `+0x24`、`+0x2c` | `HandleRogueCommonPendingActionScRsp` 5657；成功字段 10 确认已移除动作，失败字段 9 携带失败批次，retcode=13 |

本轮 Java 原生适配只处理经典宇宙的已保存祝福候选，按批次和候选 ID 校验，成功保存后确认；其他 oneof、未知字段、重复和跨局旧批次不能消耗候选。客户端命令 5676 原本因混淆名没有进入仅识别 `CsReq/ScRsp/ScNotify` 的导入表；现在由实验原生处理器显式接收。**原始 proto、描述符、命令表及生成类的固定字节均保留。**

通知的模式 101 另与 hkrpg-go `ef47e49a233133c46d70ab5a4deb4bc71ae49571` 的 `pkg/constant/command.go` 和宇宙通知代码交叉核对。该旧版本仅用于流程语义，不能用其字段号替代 4.6 证据。

## 季期、区域与当前地图查询

### 终局结果与退出

本机 `RogueModule._OnCmdSyncRogueFinishScNotify`（方法 619183，RVA `df480a0`）的消息类型槽 `93e6838` 解出 `FKBINHGJLGL`；WriteTo `1e37bbc0` 的 `0x72` 对应外层字段 14。其复制构造函数通过类型槽 `9386e18` 创建 `GKHGFCBMLBL`，不是按来源注释的内存偏移猜测字段。

| 本机读取与序列化 | 含义 | 固定描述符字段 |
|---|---|---|
| `GKHGFCBMLBL.WriteTo` `1e486a90`，布尔 `+0x44` 写 `0x58`；`RogueFinishInfo._InitBaseInfo` `def47d0` 写业务对象 `+0x58`，getter `def4f90` | 真实胜负 | `is_win`=11 |
| 业务对象 `+0x3c` 来自消息 `+0x40`，WriteTo 写 `0x60`；`get_PassRoomCount` `def4fd0` | 已完成房间数 | `GMIIMAHLIJF`=12 |
| 业务对象 `+0x40` 来自消息 `+0x3c`，WriteTo 写字节 `90 06`；`get_ReachRoomCount` `def4ff0` | 已到达房间数 | `HEOMALBNJLL`=98 |
| 消息 `+0x38` 写字节 `d8 3f`；业务初始化及区域 getter 交叉核对 | 区域 ID | `area_id`=1019 |
| 消息记录 `+0x10` 写 `0x2a`；首次奖励 `+0x18` 写 `0x52`；构造函数及 `_InitFirstRewards` `def49e0` | 本局记录和已发首次奖励 | `record_info`=5、`LHPNJLBPACA`=10 |
| `KCOEJJKGNIG.WriteTo` `1e7cea60` 与 `.cctor` `1e7cef20` 的 codec `0x0a/0x62/0x72`；`_InitRecordBuffs/Miracles/Avatars` 交叉核对 | 角色/奇物/祝福记录 | `avatar_list`=1、`MAJGMMCOCIG`=12、`buff_list`=14 |
| `ALBLEFHHJIC.WriteTo` `1e3a4f40` 写 `08/28/38/58` | 角色类型、ID、槽位、等级 | `avatar_type`=1、`id`=5、`slot`=7、`level`=11 |
| 接收函数 `df48000` 使用类型槽 `939e628` 的 `DDIHBNNOKJN`；WriteTo `1e152250` | 探索胜利 | `SyncRogueExploreWinScNotify.ILOFGCAFHAH`=14 |
| 接收函数 `df47b20` 使用类型槽 `93f96a8` 的 `NIFJKCEJFIP`；WriteTo `1eafedf0` | 当前宇宙状态 | `SyncRogueStatusScNotify.status`=13，控制标志=8 |

`_OnCmdSyncRogueFinishScNotify` 调用 `_TrySetCacheRogueFinishInfo`（`df45230`），在普通宇宙内缓存业务结果，场外则派发结果事件 `0x7a1`。判断函数 `df48230` 使用 `RogueStatic.IsInSimulatedRogueMode`（`df64cb0`）和 Endless 模式；不能一收到结果就按旧项目强制换场景。状态接收路径在 Doing=1 到 Finish=4 时派发 `0x79e`，胜负另由结果字段表达；本机 Leave 请求 `df46020`/1858 和接收 `df47630` 负责场景/编队，Quit 请求 `df46130`/1829 和接收 `df478a0` 只处理宇宙数据。来源命令表的结果/胜利/状态通知分别为 1868/1882/1843，保持固定字节。

还核对了 RoomScore、AreaFirstPassScore、TotalScore、TalentCoin 的业务 getter：来源别名 `score_id`=7 实际进入 TotalScore，其他对应字段 13、8、6；这些字段尚未接入收益结算，不填虚构积分或天赋点。现有回归验证胜负结果、真实计数/记录/奖励快照、缓存顺序和文档恢复；真实 KCP 验证原生放弃结果。完整客户端退出/结果 UI、实际资源终点战斗及积分/天赋/周奖励仍待验收。

```powershell
python scripts/analyze-client-46.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --method-indices 619183 619155 619235 619241 619245 619247 219527 219234 219186 219197 219177 219768 220267 --disassemble 1400 --output build/client46-rogue-finish-evidence.json
```

`RogueData.SyncRogueData` RVA `deef190` 读取全局/当前信息；序列化函数与对象 getter、业务 Refresh 调用交叉核对。原生 `GetRogueInfoCsReq` 1834 返回 1825，不创建探索或完成目标；查询可重试此前已经真实通关并保存、尚未预留的首次奖励计划，预留中断不自动重发。

| 对象 | 本机证据 | 固定描述符字段 |
|---|---|---|
| 全局/当前信息 | `AGEHGBKOBEF.WriteTo` RVA `1e0fcb90` 与 `RogueData.SyncRogueData` | 基础 `LFLCDNGALME`=192，当前 `CFDIKLBINHK`=1042 |
| 季期/区域目录 | `EDOIEMCFFCA.WriteTo` RVA `1e235cf0`；`CGNNMPIKBBB.WriteTo` RVA `1e089b40` | 季期 `HHFAAFOBKME`=13，区域目录 `ACLKMFCDMED`=8；季期 ID=3、begin=9、end=11 |
| 区域条目 | `RogueAreaData.Create/Refresh` RVA `dee52b0/dee5380`；`CAJEBOPPMCJ.WriteTo` RVA `1e02f7f0` | 区域=13、地图=9、区域状态=4、当前运行状态=12、已领取=10、已到达数量=1 |
| 当前运行 | `HNKAOFDGLIE.WriteTo` RVA `1e5b12d0` 与 SyncRogueData | status=12、地图=9、祝福=13、碎片=4、待选动作=7 |
| 地图 | `RogueMapData.RefreshMap` RVA `df37950` 及 get_AreaID/get_MapID/get_CurSiteID/get_CurRoomID；`DNDMLICBKEF.WriteTo` RVA `1e1c7040` | 区域=2、地图=8、站点 `ANHDBMOPGDC`=15、房间 `HPKMGKPPELC`=10、房间列表=11 |
| 房间 | `RogueSiteData.RefreshRooms` RVA `df5ff40` 及 getter；`PAIJCBOKIFO.WriteTo` RVA `1dec1450` | 站点 `MAJKFIMPOOF`=11、房间=13、状态 `CAGDNHCOCKM`=3；隐藏房间数据没有虚构 |
| 候选等级 | `KJAMNOGFAMJ.WriteTo` RVA `1e83a480` 与 `RogueBuffData.Create` RVA `dee5da0/dee5e40`、get_BuffID/get_Level RVA `dee75a0/dee75c0` | buff_id=1、等级 `HAGDKKBAAKK`=4 |

Java 选取资源中唯一有效且版本为 1 的季期，按其实际区域列表建立目录；没有有效季期时返回错误。未知解锁脚本保持锁定，首次通过/奖励状态只读实际账本。运行快照恢复保存的地图、站点、房间、清房、祝福和已下载角色；IDLE、ABANDONED 或已关闭的终局不生成当前运行信息。独立编队及基本生命周期见下文；命途和完整奇物状态仍未实现，不能把相应空集合记为完整功能。

## 房间切换与地图同步

`RogueModule.EnterRogueMapRoom` RVA `df45f40` 调用 NetworkManager RVA `d9a72b0`，发送 1879；传入站点和已分配房间。响应接收 RVA `df47bd0` 先检查 retcode，再更新当前站点并处理实际场景/编队。

| 对象 | 本机序列化证据 | 固定描述符字段 |
|---|---|---|
| 请求 GFMDKGLKCOF | WriteTo RVA `1e4441c0` | 站点 MAJKFIMPOOF=1，room_id=10 |
| 回包 NNAMFPHNGBF，命令 1841 | WriteTo RVA `1eb515a0` | 当前站点 ANHDBMOPGDC=2、retcode=6、lineup=7、旋转数据 JNKLNLKHGBB=14（FHEGNOPNICH）、scene=15 |
| 地图通知 CLPJHIIDAGN，命令 1896 | WriteTo RVA `1e0d17c0`；接收 RVA `df47eb0` 比较当前地图并更新站点 | 房间条目 HNEANMNJHDB=4、map_id=7 |

旋转数据由 RogueStatic.SetMapRotationData RVA `df61a10` 交给 `f8b0310`；后者显式处理 null，并生成未激活的旋转状态。当前普通经典房间未编造旋转信息；旋转玩法仍没有验收。

Java 仅接纳已保存的分配，前房清理、待选动作、相邻路径和当前场景/编队均需通过校验。传送失败回滚站点/房间，成功回包读取实际场景/当前编队；放弃探索或候选未处理时下一房间保持锁定。相邻切换成功仍由合成场景测试验证；基础开始/重入已通过真实 KCP 加载资源房间和专用队伍，并返回原场景。游戏 UI 仍未验收。

## 持有祝福增量

原生 `SyncRogueCommonActionResultScNotify` 5647 的接收函数 `dedfb40` 在展示处理后调用 RVA `15d779d0`；后者对 detail 调用 `15d780e0`。case `0x3d0`（976）传给 `bc70380` 添加或更新 buff ID/等级，case `0x5cb`（1483）按 ID 删除。KJAMNOGFAMJ 的 ID/等级已由 WriteTo 与 getter 核对，不沿用原始 proto 的内存 offset 注释。

Java 选择及强化后发送实际保存的祝福增量：DLOJFEEHLIJ=1 为模式 101，CLGGKLNPLCK=15 为 MAOKMPOELCO 列表；该条目 detail=3、queue_position=5；PKHKEEKIODC.IFOOEPKGGAG=976 内含 buff_id=1、等级 HAGDKKBAAKK=4。MAOKMPOELCO.WriteTo RVA 为 `1e9b1de0`，PKHKEEKIODC.WriteTo 为 `1df60e10`。display_type=0 在 RVA `15ebcc50` 跳过结果展示，但持有状态仍执行上述更新；未核对的 source/display 非零枚举没有猜测赋值。这里只支持祝福状态增量，不能计为全部结果/奇物通知。

可复现的补充命令同时导出类型和已定位索引，并核对方法的声明类型：

```powershell
python scripts/analyze-client-46.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --types GFMDKGLKCOF NNAMFPHNGBF CLPJHIIDAGN MAOKMPOELCO PKHKEEKIODC KJAMNOGFAMJ --method-indices 0x968fe 0x968ff 0x969b0 0x974bd 0x93646 --method-names WriteTo HKKFFCBJOKM HHABABNCGIO HGAOOOICFBK SetMapRotationData BPLPBPMEODA --disassemble 2200 --output build/client-46-universe-state-wire.json
```

## 基础开始、重入和返回

`RogueModule` 的发送函数 `df45cf0`/`df45e70`/`df46020`/`df46130` 对应开始 1805、重入 1860、离开 1858、关闭 1829。不要依据 proto 的内存 offset 注释推断布局；本机 WriteTo 与接收函数分别核对如下：

| 操作 | 本机类型与 WriteTo RVA | 确认字段 |
|---|---|---|
| 开始请求 | LJINAKCNBCB `1e955a90`；codec `.cctor` `1e956040` | 基础角色列表=2、特殊列表=12、故事线列表=4、命途=5、区域=10；基础角色语义参考固定 LunarCore/hkrpg-go |
| 开始响应 | HHNAMDCALND `1e55e880`，接收 `df47340` | 探索快照=4、retcode=8、编队=11、旋转=12、场景=15 |
| 重入请求/响应 | MGBFNLJFCAA `1e9efe70`；KNBOCPOOAEM `1e872320`，接收 `df47500` | 请求区域=12；响应旋转=2、retcode=5、场景=7、编队=13、探索快照=15 |
| 离开请求/响应 | CAGNJEEKBNN `1e02d5b0`；DAEOAHLMACG `1e1217e0`，接收 `df47630` | 空请求；响应旋转=1、retcode=6、编队=10、场景=11 |
| 关闭请求/响应 | MDFEOFGBJBI `1e9d43f0`；PLIDMPJNFEA `1df75180`，接收 `df478a0` | 请求区域=1；响应 retcode=8、探索快照=10 |

专用角色快照 ENOBKLGCHOD 的 WriteTo `1e2c6360`、codec `.cctor` `1e2c6850` 核对列表标签 `0x0a`（字段 1）。Java 返回实际下载角色，基础开始仅支持命途=0 且特殊/故事线列表为空；非零或未核对业务的列表不进入玩法。空旋转处理沿用上述明确的 null 分支，未编造旋转数据。

类型槽由元数据 usage kind 1 和 Il2CppType CLASS 记录定位，脚本核对指定槽的唯一类型，不依赖同名猜测：

```powershell
python scripts/analyze-client-46.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --type-slots 0x9361608 0x938f028 0x9361b68 0x93be648 0x93d70f0 0x93d2848 0x935c750 0x9374d18 --method-names WriteTo --disassemble 1800 --output build/client-46-lifecycle-wire.json
```

`smoke-4.6.py` 使用真实 Java/KCP 服务进入资源房间，返回原场景，跨重连和 H2 进程重启恢复同一站点/房间，再离开/关闭。专用生命、能量与技能点的文档恢复由 Morphia 回归单独核对；没有验证战斗中的崩溃恢复或游戏 UI。

## 剧情编队通知

4.6 Mission 100020133 的 ChangeLineup 参数为四个槽和末尾领队 ID；固定 Hyacine 提交 `dd54de89` 的 MissionHandlerChangeLineup 提供相同参数语义参考。Java 当前仅接纳已持有的正式角色，不自动授予或建立试用角色。队伍修改同步实际 Scene 角色缓存，删除通知先保存原实体 ID。Honey `fe00bdb` 的 PacketSceneGroupRefreshScNotify 对含实体删除的通知使用 Afibfmafncc；固定 4.6 描述符该枚举=3。旧 Quickbuf 构造器也写 3，但其冻结 proto2 描述符仅列 0..2，因此转换器只放行这一明确的服务端枚举并按语义映射；其他未知枚举/字段仍拒绝。回归核对原实体删除 ID、4.6 回包和非法枚举 4 的拒绝。

## 奇物持有、选择和消耗次数

本机 `RogueMiracleData.Create`（617466/617467）和 `_Refresh`（617476，RVA `df39010`）确认资源 ID 存于 +0x20、使用次数 +0x24、总次数 +0x28。对应 getter 为 617478/617485/617487。`IsBroken` 617475 明确判定总次数非零且使用次数不少于总次数；总次数零表示不会按次数损坏，不是已经损坏。

快照 `EFDAIBDBFEA.WriteTo` 218804（`1e25fbd0`）对应资源 ID=4、使用次数=10、总次数=1；公共动作 `KOFMGCNIMIN.WriteTo` 220795（`1e8830a0`）对应资源 ID=2、使用次数=1、总次数=4。未把资源 ID 编造成另一个实例编号。

工厂 616428（`b712900`）调用 `DCFPEIMAMCN` getter 224150（`1e13da70`），其 case `0x33a`=826 创建 `RogueMiracleSelectAction` 616191。候选列表为 `AMKALFJHFJP.BPDDBKGDMDD`=3；该构造器设置 SourceType=1。发送函数 `df3cd60` 调用 `d9a94a0`，5676 请求 oneof `CCCIFGFHGIL`=1316、内层 `OJPGDDFJCHB`=13。Java 仅允许已保存的候选 ID 和当前批次，拒绝重复及跨局旧请求。

结果处理 616704（`15d78250`）对 894 调用 `IRogueMiracleInfo` 第 0 个方法 AddMiracleItem，对 577 调用第 2 个方法 RefreshMiracleItem。617529/617531 的本机实现均把 KOFMGCNIMIN 传给上述 `_Refresh`。因此新增通知使用 `PKHKEEKIODC.GPJJMPCMAAA`=894，次数更新使用 `KDJBDMLDMHG`=577；未使用 1957 的替换分支冒充新增。原生字节回归包含候选、响应、状态增量及重放拒绝，Morphia 回归包含持有、损坏、候选和已处理战斗键。尚无奇物选择的实际 KCP 奖励事件或游戏 UI 验收。

效果依据固定 4.6 `RogueMiracleEffect.json` 及同提交 TextMapCHS 原文，当前只实现资源 ID 4/6/13/15/17/20 的服务端效果子集；不是所有 250 个奇物。TextMap 仅用于只读核对，保持在忽略的参考目录，没有加入运行依赖。

## 手机消息图与剧情报告

本机 MessageModule `_DoFinishItemByChoice` 606160（`d69ea90`）构造回复请求并发送命令 2760。选中资源 item ID 写入请求 +0x1c；冻结 4.6 请求 item_id=6、text_id=11。`MessageItem` 对应 PKOMDBGFMBK：类型槽 `0x93b44c0`，WriteTo 199255（`1df67c20`）将 +0x18 写为字段 4、+0x1c 写为字段 8。资源文本 Hash 不能当作 text_id，当前只支持零变体。

`SaveMessagePerformance` 606163（`d69f770`）调用网络 `d99d200`，命令 2783；内层类型槽 `0x936ef40` 对应 FPDBFGBJOFD，段落 ID=2、MessageItem 列表=8，外层 HBMLBLAJKKL=12。客户端只筛选回复或特殊消息记录，因此 Java 从资源起点重建 NPC/自动消息路径，不要求客户端上传完整 NPC 对话，也不接受跳过玩家分支的报告。

批量接收方法 606222（`d6a8420`）读取响应 +0x20，非零进入错误分支，并单独处理 3502（未接取段落）。类型槽 `0x93acda0` 对应 PELFPKFFFBO，WriteTo 199634（`1deeecb0`）将该值写为 tag `0x30`，确认冻结字段 PLDKENJLKME=6 是错误码，不能因混淆名称而遗漏它。Java 对所有段落先预检，失败不提交其余段落。

`_OnGetMissionMessageScRsp` 606223（`d6a92d0`）把响应 +0x10 中的段落 ID 集合与资源组段落列表比较。类型槽 `0x93af608` 对应 PGFLOJNIFLF；.cctor 199660（`1df01390`）为该重复字段初始化 codec tag `0x3a`，WriteTo 199672（`1df00fe0`）读取 +0x10，对应 HNIPIODEBNN=7。字段 1 的映射语义尚未确认，保持为空。其余联系人/普通段落命令号使用固定 4.6 命令表，未宣称全部请求均通过实际客户端连接。

回归覆盖实际已接取目标、资源分支、终点、未接取/取消/循环图拒绝、正常/剧情/批量完成，以及真实 Morphia 文档重载。PlayMessage/SaveMessage、跨段、Raid/Link 和完整手机 UI 未验收。

## 音律编曲、评级和音效

保存编曲请求 7580 的内层 MusicRhythmGroup 已从本机发送/序列化交叉核对：music_group_phase=15 实为歌曲 ID，music_group_id=14 为预设 ID；EOHMNCBOALC=13 按资源轨道顺序保存拍节位图，BBINFDHMGII=2 保存启用音效 ID。Java 保存实际编辑状态，查询不再把关卡组误当成歌曲编曲。使用 GridNumList 的长度限制位图；GridNum 标量不能替代实际拍节数。

ActivityMusicRhythmModule `_OnMusicRhythmDataScRsp` 569537（`c56b070`）将响应 +0x44 写为模块 +0x68。`MusicRhythmLevel.get_IsAvailable` 569709（`d9065d0`）在 difficulty=3 时读取该模块标志。响应类型槽 `0x93ab468` 对应 NANCKMNBPCB，WriteTo 205976（`1ea9b210`）把 +0x44 写为 tag `0x20`，所以冻结字段 show_hint=4 实际控制全局最高难度开放。当前 Java 按资源 UnlockMission 已到达、未取消及普通关卡配置评级进行保守门控；这不证明完整官方开放时机。

`IsFinishAllNormalLevel` 569547（`c56c760`）跳过 difficulty>2 的关卡，并检查当前评级等于全部评级；对应资源 MusicRhythmFinishAllLevel 的已实现子集为 GreaterEqual、难度上限和最低评级。`GetMinLevelStarNum` 569574（`c56f170`）遍历完整关卡集合；该集合由 569620（`b975ee0`）遍历资源阶段、组、关卡建立。`MusicRhythmSong.GetUnlocked` 569797（`d90e880`）的 UnlockType=2 比较该最低评级与资源阈值，参数零默认为一。Java 把未游玩关卡计零，缺少阶段/组资源时不开放隐藏曲目。

查询 +0x28 使用 codec `+0x5d0c0`；.cctor 205955（`1ea9bcc0`）设置 tag `0x4a`，确认 unlock_song_list=9。查询接收路径把每个 ID 对应歌曲 +0x4c 设为 true；`get_IsSFXUnlock` 569833（`d910990`）读取这个字段。因此该列表是音效已开放的歌曲，不能填所有基本可用歌曲。音效通知处理 569543（`c56c430`）对其列表执行相同操作；Java 按 SoundEffectUnlockSubMission 发送歌曲 ID，取消目标不能开放音效。回归核对音效门槛、通知只发送新变更、评级查询、预设/自定义位图/效果校验及文档恢复；输入重演和游戏 UI 均未验收。

定位工具新增类型名片段和静态调用 RVA 查找。RVA 先通过已核对的方法地址表关联到方法及所属类型；未定位的地址报错，不猜测调用意义。所有新增证据仍使用页首指定的 GameAssembly/metadata SHA-256，仅只读文件，未加载或执行 DLL。

## 场景交互与宇宙出口

NetworkManager 类型 69368 的发送方法 634437 `ENJCLCKJMHH`（RVA `d9856c0`）把实体写入 +0x1c、配置编号写入 +0x18、64 位上下文写入 +0x20，并在 `d985705` 使用命令 `0x57d`（1405）。类型槽 `0x936a3b8` 对应 OBOAHKMABIJ（33966）；WriteTo 232413（`1eb86680`）分别使用 tag `0x20`、`0x28`、`0x40`，因此字段为实体 4、配置 5、UInt64 上下文 8。冻结定义的 interact_id2 是实际配置编号，不能用旧核心 uint32 的 interact_id 截断上下文。Java 用原生入口处理，未知实体/配置/费用/状态不返回成功。

宇宙出口目标使用固定 4.6 描述符 PropExtraInfo.rogue_game_info=5、PropRogueInfo.room_id=3、MAJKFIMPOOF=12；目标来自当前保存地图，Door2 的路径及按目标房间类型的外观与固定 LunarCore development 来源交叉核对。RogueModule.EnterRogueMapRoom 619167（`df45f40`）仍使用命令 1879；GetPropExtraInfo 619150（`df42410`）读取 MapPropDef 的额外信息，未宣称已经从本机完整证明 PropRogueInfo 所有序列化字段。Java 回归核对原生目标、实际开闭、帧头和旧楼层隔离，游戏 UI 未验收。

基础地图/锚点和 EnterScene 的命令/非混淆字段使用同一固定 4.6 描述符。仅实现基础楼层，不把未识别的内容、故事线或传送分支猜测为普通场景。

## 首次对话演出

本机 NetworkManager 类型 69368 的方法 635256 LEOEPODICFE（RVA d9b2ef0）把 performance ID 写入请求 +0x18，并使用命令 0x871（2161）。类型槽 0x9370ab8 对应 CFLOLHMFICG（34541），WriteTo 238389（1e078c00）将该值写为 tag 0x68，确认 performance_id=13。

查询方法 635254 EPKMHBFKNNK（d9b2cf0）使用命令 0x85e（2142），把请求中的重复 ID 容器放在 +0x18。类型槽 0x93d4e08 对应 GNLACIGLJJN（34521），WriteTo 238190（1e4b6820）使用该容器的 codec；.cctor 238181（1e4b6b00）初始化 tag 0x22，确认 performance_id_list=4。原生响应字段采用固定 4.6 描述符，没有修改生成类；响应消费路径和游戏 UI 仍待客户端验收。

其他本机调用同时定位到普通 NPC 查询 2160 与完成 2158，尚未据此推断 npc_id 属于原始 NPC ID 还是系列 ID，不把它们混入演出 ID 状态。静态证据保存在忽略的 build/client46-firsttalk-send.json、client46-firsttalk-wire.json、client46-firsttalk-codec.json，可用现有只读脚本按上述方法索引/类型槽复现。仍使用页首的 GameAssembly 和 metadata SHA-256。

首次演出只完成当前资源、场景及已接受目标匹配的展示脚本；未知任务效果保持待完成。不能把首次演出处理器算为所有演出、分支变量或剧情脚本已实现。

## 已定位、尚未接通的操作

- 命途非零和特殊角色/故事线开始列表仍不接纳；基础开始的通过不能计为完整宇宙 UI 或全部入场组合。
- 奇物事件奖励、商店、删除/替换、剩余效果、来源/结果演出仍未接通；服务器不会自动给每个房间发奇物或开放不支持的效果。
- 编曲和部分音律字段已接通，其他展示/活动字段、官方开放时机和输入重演尚未验收；静态元数据解码本身不会补全服务端状态机。

## 辅助参考与验收边界

阅读了 [NeonTeam58/HNA-MORAX](https://github.com/NeonTeam58/HNA-MORAX) 的 MIT 源码，固定提交 `5b169a9ec74dd8e369ff20933dbb9052b1b75b30`；其特定测试版常量不适用于本机客户端。该仓库保留在忽略的 `references/`，未执行其中的程序、未编译或引入运行依赖，项目脚本由本机反汇编证据独立实现。

验收分为静态调用/字段核对、Java 状态与错误分支回归、实际回环 UDP/KCP 服务探针、游戏 UI 流程四层。本轮前三层只验证各自列出的范围，第四层未完成。OS 资源与 CN 客户端的差异、协议加密、完整 SDK 与资源更新仍待联合验收。`clientAccepted` 保持 false。

## 天赋与房间积分

`RogueModule._OnCmdGetRogueTalentInfoScRsp` 619196（`df4a140`）使用类型槽 `0x93c4080`，解析为 JOCBDCIBNMG（32776）；WriteTo 220187（`1e76d490`）将 retcode 写为 tag `0x38`（7）、列表消息写为 `0x6a`（13）。`_OnCmdEnableRogueTalentScRsp` 619197（`df4a1c0`）使用 `0x938a4a8`，对应 KLAEMDDHPNB（32780）；WriteTo 220227（`1e853810`）分别写为 `0x50`（10）和 `0x5a`（11）。前者转给 UpdateTalentSrvData 619436（`df67cd0`），后者直接更新列表；接收路径未先拒绝非零错误码，因此 Java 错误响应也附带权威状态列表。

FDBDLBAPDBE（33232）.cctor 224794（`1e323f10`）为重复节点初始化 tag `0x3a`（7）。OKPIPFKONMG（33230）WriteTo 224785（`1de74370`）将状态 +0x24 写为 `0x18`（3），节点 ID +0x20 写为 `0x38`（7）；.cctor 224772（`1de74940`）为前置列表设置 `0x4a`（9）。IsLocked/IsUnlocked/IsActivated 619448/619449/619450（`df68d00`/`df68d60`/`df68dc0`）分别比较状态 0/1/2。GKFGIMIBBHF（33228）WriteTo 224763（`1e483c40`）将前置 ID +0x1c 写为字段 1、完成 +0x18 写为字段 4、进度 +0x20 写为字段 6；GetProgress 619469（`df697d0`）读取该进度。Java 使用固定 4.6 命令 1802/1806、1890/1854，不修改原协议生成物。

查询初始化 `_RefreshRogueVirtualItems` 619146（`df44ea0`）把 MBCBDJNKFEB +0x18 传给虚拟物品 32；WriteTo 219033（`1e9b3440`）在 `1e9b3491` 用 tag `0x38`（7）写这个成员。货币更新 `_OnCmdSyncRogueVirtualItemInfoScNotify` 619173（`df471d0`）在 `df47250` 把 GAOBHBLFPEC +0x1c 传给物品 32；其 WriteTo 218866（`1e3f8230`）在 `1e3f8281` 用 tag `0x10`（2）写这个成员。它将 +0x18 用作宇宙碎片，序列化为字段 12；Java 发通知 1801 时保留实际探索碎片余额。资源中的 C# 偏移注释与本机布局不同，不能根据注释把天赋点填入字段 7。

房间分数来自固定 `RogueAreaConfig.ScoreMap`，兑换比例来自 `ConstValueRogue.Rogue_Explore_Score_To_Talent_Coin_Rate=0.1`。与前节已核对的结果字段配合：GHCBFCIGIBF=13 为房间分数、混淆 score_id=7 实际为总分、GEBGGMAGDJB=6 为本局获得天赋点。当前总分只含房间分数，未实现的首通额外积分保持零；天赋点只报告已入账数量。Java 回归包含合流前置、并发激活、余额/配置错误、点数溢出后冻结重试和真实 Morphia 结果/余额/节点恢复；并非真实游戏的天赋效果或 UI 验收。

通用周期积分已接入下节列明的原生查询、领取及容量超限子集；它不能由旧模拟宇宙固定积分快照替代。固定资源 CycleScoreReward 有 10 档、18000 最高领奖门槛，ConstValueCommon 规定 7 天刷新和 `2026-02-09 04:00:00` 起点。经典宇宙贡献采用本项目规则，官方加成、其他模式和客户端接受仍待核对。

复现新增天赋/货币证据：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 619196 619197 619436 224763 224772 224785 224794 224803 220187 220227 619146 619173 218866 219033 --disassemble 1000 --output build/client46-talent-evidence.json`。类型槽可另用 `--type-slots 0x93c4080 0x938a4a8` 核对。窗口可能跨越后续方法，解释时按已核对的方法地址及控制流限定范围；客户端文件未改动或执行。

## 通用周期积分、领取和容量超限

CycleScore（63542）Sync 585492（`cbdbc80`）将协议 +0x28 写为实际积分，遍历 +0x10 的已领取档位，最后读取 +0x20 起点、+0x30 终点。GAAMBBFPCHB（30891）WriteTo 200633（`1e3e41b0`）使用 tag 0x20/0x58/0x70 写对应时间/积分；.cctor 200618（`1e3e4720`）用 0x62 初始化列表，确认 start_time=4、end_time=11、已领=12、积分=14。

CycleScoreService.GetRewardByScoreRank 585530（`cbdde50`）把档位放入列表，转交 NetworkManager.ILAIDKCIIHB 634620（`d990fd0`）。后者将列表添加到请求 +0x10，并以 0x104a（4170）发送。类型槽 0x93b2998 对应 PICONFLHMCD（30897），.cctor 200680（`1df16570`）在 `1df16633` 设置 codec tag 0x32，确认重复档位字段 6。DestroyReward 585531（`cbddf90`）转交 NetworkManager.MGHIMECIGLC 634621（`d991090`），使用 0x1054（4180）；请求字段 3 来自固定描述符，未独立宣称已完成丢弃请求 codec 全部核对。

领取响应本机类型 FECGDOJFDIM（30899）的 WriteTo 200713（`1e332c80`）将 +0x28 用 tag 0x1a 写为物品字段 3，+0x30 用 0x28 写错误码 5，+0x20 用 0x42 写周期快照 8；.cctor 200698（`1e333280`）用 0x4a 初始化 +0x10 的档位列表 9。接收方法 585541（`cbde930`）先同步 +0x20 快照，再构造 CycleScoreRewardClaimEvent 585523（`cbdd4e0`）。其 `cbdd56c` 将 +0x28 传给 InventoryModule.GetRewardItemDisplayData 602800（`d35f070`），随后遍历 +0x10 档位，按 GetRewardIDByRank 585495（`cbdc520`）和 CheckRankRewardCanBeDestroy 585532（`cbddb10`）生成丢弃候选。后者只接纳单个物品且 ID 与资源 DestroyItemList 对应。因此字段 9 不能当作成功领取集合发送；Java 按此业务路径用于容量超限的待处理档位，正常到账由快照字段 12 与物品字段 3 表达，游戏 UI 仍待联合验收。

容量超限响应 DDOOEDOKEPF（30903）WriteTo 200752（`1e15e940`）用 0x12 写 +0x18 周期快照（字段 2），0x70 写错误码 14。接收方法 585542（`cbdea70`）同步该快照并发送领取/丢弃界面事件。当前 Java 对单独奖励 110 超限保留整笔待领，需要明确丢弃请求才关闭；腾出容量后正常发放，未知/已领取/预留中断/过期计划不丢弃。这是经过服务端回归的本项目处理规则，没有实测游戏界面。

三种货币通过同一本机消费者与序列化交叉核对：`_RefreshRogueVirtualItems` 619146（`df44ea0`）在 `df44f34` / `df44f5f` 将 MBCBDJNKFEB +0x20 / +0x1c 分别传给物品 33/34；WriteTo 219033（`1e9b3440`）分别写为字段 8/14。通知消费者 619173（`df471d0`）在 `df4726e` / `df4728c` 将 GAOBHBLFPEC +0x24 / +0x20 传给 33/34；WriteTo 218866（`1e3f8230`）对应字段 7/14。Java 分别保存实际余额，第一档奖励物品 34 不会错误计入经典天赋点 32。所有证据仍使用页首两个文件哈希，仅只读解析，未运行或修改客户端。

复现周期证据：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 585492 585523 585530 585532 585541 585542 200618 200633 200680 200698 200713 200752 634620 634621 --disassemble 1000 --output build/client46-cycle-evidence.json`。静态序列化和服务端原生测试不替代真实客户端接受。

## 祝福重置与天赋效果子集

`RogueBuffSelectAction` 使用运行时数据 `IFEFEELJELP`，不能直接把动作类的偏移当作协议偏移。转换器 617005（`15eff190`）把 GGGBAIAPDLO +0x3c/+0x34/+0x40 分别写入数据的重置开关、已用次数和上限；费用来自协议 +0x28。GGGBAIAPDLO WriteTo 220542（`1e44c5f0`）用 0x68/0x70/0x78/0x12 确认 `can_roll`=13、`PCGFIOKLIBE`=14、`EGFOLKEPNCI`=15、`DEHJDNIEKHB`=2。动作 getter 615959/615960/615961/615962 读取转换后的成员，分别为 `dee94e0`/`dee9530`/`dee9580`/`dee95d0`。

本机按钮包装函数 351969（类型 DMPKJANNHIN，`10613170`）在 `10613295` 调用 NetworkManager 635091（`d9a9030`）。后者以 0x162c 发送 OFEDNNJIJFL，将 oneof case 写为 0x33；类型槽 0x9379cb8 解析为无字段的 DEMENEAAGAA。对应请求 `KBNLGEMNDEN`=51。此调用没有写请求的 +0x24 批次字段，Java 按当前唯一待选菜单处理空请求；显式非零批次仍必须匹配。不能用 NMMLEBEEJLB 的字段 3 猜测重置：NetworkManager 635092（`d9a90e0`）实际被 RogueBuffDropAction.SendSelectRequest 615920（`dee7c70`）调用，用于删除选择。

回包消费者 616706（`b81e980`）的跳转表 RVA `b81eccc` 中 case 3 指向 `b81eaa1`，类型槽 0x93ac260 为 FKOGDINOBDF；它把内部消息 +0x10 交给 RogueBuffSelectAction.RefreshOnRollRsp 615964（`dee9790`）。FKOGDINOBDF WriteTo 220597（`1e384b00`）用 0x5a 写内部 GGGBAIAPDLO，确认父响应 `JFHKDEIDMOG`=3、内部 `PCHGEGFBKLF`=11。该分支更新现有菜单并返回已处理；Java 保持原队列位置，避免把重置当作选完并移除菜单。

天赋 11/21/32 的效果语义来自固定 AGPL LunarCore development 提交 `ec315bb1cf5cf86ae0f6ee52824e07f1fef9a5c4` 的 RogueInstance.initTalents/RogueManager.start；4.6 资源中的 32 参数为 1、基础初始能量为 0.5、重置费用为 30/50/100 碎片，普通分组 100005 递归包含普通/稀有成员。此轮没有找到其他统计天赋的已核对战斗配置，未凭图标拼接任意 buff。服务端已实现上述三节点的效果和已持有信仰债券的重置折扣，其他统计/命途效果与实际游戏接受仍待完成。

复现新增字段证据：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 351969 635091 617005 615959 615960 615961 615962 615964 220542 220597 616706 --disassemble 1600 --output build/client46-blessing-reroll-evidence.json`；另用 `--type-slots 0x9379cb8 0x93ac260` 核对请求与回调。忽略目录中的调用点扫描只读 il2cpp 可执行节的字节，未执行 DLL 或参考仓库中的二进制。`clientAccepted` 仍为 false。

## 经典宇宙复活请求与费用

只读核对同一已锁定国服 DLL/metadata 哈希。`SimulatedRogueSendPacketCmd.SendReviveRogueAvatarCsReq` 617620（`e0d6180`）写 `PDNOJMFCDDG` 的角色列表 +0x10 和装置实体 +0x20，调用 `GetCurVerifyServerEntityID` 541832（`e588a70`），发送 `0x713`=1811。类型槽 `93abb48` 指向 PDNOJMFCDDG 32718，WriteTo 219589（`1dee63a0`）按冻结定义写列表字段 10 与装置字段 13。源 proto 的偏移注释不是字段布局证据。

`RogueModule._OnCmdReviveRogueAvatarScRsp` 619192（`df495b0`）由类型槽 `93bd0f8` 核对 `AJAOJOEAGML` 32720；费用信息 +0x18 经 WriteTo 219610（`1e2569d0`）tag `0x1a` 写字段 3，结果 +0x20 由 tag `0x20` 写字段 4。费用通知接收 619193（`df49750`）的槽 `937a498` 指向 KHCBONJOLDP 32722，WriteTo 219629（`1e812a30`）tag `0x12` 写字段 2。两者费用消息 KCMFAIBMOHB 32652 的 WriteTo 218841（`1e7cd330`）以 tag `0x2a` 写 ItemCostData 字段 5；`RogueReviveAvatarInfo.RefreshReviveAvatarInfo` 616741/616742 读取该消息 +0x18 的真实物品费用。请求/响应/通知的固定命令为 1811/1817/1803。

复现：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 617620 619192 619193 616741 616742 541832 219589 219610 219629 218841 --disassemble 1800 --output build/client46-revival-evidence.json`；类型槽另用 `--type-slots 0x93abb48 0x93bd0f8 0x937a498`。静态证据只证明线协议；每名角色计价采用本项目规则，未冒充官方完整恢复规则或游戏验收。

## 战斗碎片货币通知

同一国服客户端的 `_OnCmdSyncRogueVirtualItemInfoScNotify` 619173（`df471d0`）在 `df472a6` 将协议成员 +0x18 交给 `RogueStatic.RefreshRogueCoin` 619710（`df61af0`）；后者用物品 ID `0x1f`=31 读取并更新实际碎片。GAOBHBLFPEC 32654 的 WriteTo 218866（`1e3f8230`）在 `1e3f8318` 使用 tag `0x60`，随后读取 +0x18，确认 `LKHAIEIDAJL` 为字段 12；Java 使用已冻结描述符发送真实余额。通知不能单独证明官方战斗奖励算法，基础金额采用已记录的本项目规则。

复现：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 619173 218866 619710 --disassemble 1000 --output build/client46-fragment-currency.json`。只读解析，不运行或修改客户端。

## 经典祝福强化与同名棋盘协议隔离

本机 `SimulatedRogueSendPacketCmd.SendGetRogueBuffEnhanceInfoScReq` 617621（`e0d6530`）发送空请求 1804；617622（`e0d65d0`）将传入祝福编号写入对象 +0x18，在 `e0d661b` 发送 0x75d=1885。类型槽 `0x937ef30` 对应 CINMCBDCBMJ 32728，其 WriteTo 219686（`1e0a8fb0`）使用 tag 0x68，确认唯一编号为字段 13，而非冻结 Common.proto 的字段 9。

查询回调 619189（`df48870`）使用槽 `0x93c10a0`，对应 AJNDMBCDEOL 32726；WriteTo 219667（`1e397500`）确认结果码字段 6、HFGBCEKGFHK 字段 11。HFGBCEKGFHK 的重复项通过 `.cctor` tag 0x7a 确认字段 15；AEFFOCFJNBF WriteTo 218761（`1ddbda10`）确认祝福编号字段 11、ItemCostData 字段 15。消费方法 616896（`cc8df00`）读取列表 +0x18、编号 +0x20 和费用 +0x10，重建实际客户端价格表。

强化回调 619190（`df48930`）使用槽 `0x93c3f18`，对应 NAHANHPHGGF 32730；WriteTo 219709（`1ea95d90`）确认 KJAMNOGFAMJ 为字段 3、结果码字段 6、未确认语义的布尔字段 13。祝福消费方法 616879（`bc701c0`）用 +0x1c 查找祝福、将 +0x18 写为等级；KJAMNOGFAMJ WriteTo 220487（`1e83a480`）将这两个成员分别编码为字段 1 和 4。这条调用链确认编号/等级语义，不能只按参考文件的偏移注释推定。

参考命令表中 `EnhanceRogueBuffCsReq/ScRsp` 的简名指向棋盘模式 5484/5469；经典 1885/1807 被带后缀命名排除在原始名称导入之外。新 `RogueEnhancement46` 使用独立严格解析及原生命令发送，不修改既有 proto、描述符、命令表或生成类，不把这两种模式互相转换。布尔字段 13 保持默认 false，尚无完整语义或真实客户端验收。

复现：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 617621 617622 619189 619190 218761 218779 219667 219686 219709 220487 --type-slots 0x937ef30 0x93c10a0 0x93c3f18 --disassemble 1800 --output build/client46-enhancement-evidence.json`；消费方法另用 `--method-rvas 0xbc701c0 0xcc8df00`。静态协议核对和合成探针不等于游戏 UI 验收。


## 经典宇宙角色下载字段

同一国服客户端的 SimulatedRogueSendPacketCmd.SendPickRogueAvatarCsReq 617619（`e0d5fd0`）写装置 +0x28、第一列表 +0x20、可选第二列表 +0x18，并发送 `0x748`=1864。类型槽 `0x93d5398` 为 EKFCDGNOAIN 32714；WriteTo 219548（`1e2930c0`）与 .cctor 219536（`1e293480`）交叉确认装置字段 6、第一列表字段 14、额外列表字段 11。额外列表不能仅按偏移注释推断为试用角色。

响应消费者 619191（`df48a30`）使用槽 `0x93c4138`，对应 BHLEHMNFHPJ 32716。WriteTo 219569（`1ddc9b10`）和 .cctor 219557（`1ddc9ee0`）确认正式编号列表字段 15、额外列表字段 6、结果码字段 13。GetRoguePickAvatarMaxCount 619121（`df42390`）的默认值为 1，特定额外类型有覆盖逻辑；Java 只实现一次一名正式角色的本项目规则，不宣称特殊上限已经实现。

复现：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 617619 619121 619191 619144 219536 219548 219557 219569 --type-slots 0x93d5398 0x93c4138 --disassemble 1800 --output build/client46-download-evidence.json`。客户端只读解析，原协议定义/描述符/命令表不变。合成正向与实际 KCP 拒绝验证不代表游戏 UI 验收。


## 宇宙编队替换与错误响应

TeamModule.SetTeamMember 625092（`e1d7ee0`）调用 NetworkManager.PEMMDBENMBO 635271（`d9b36f0`）。后者用槽 `0x93e90f8` 构造 BLIOAJGEJNN 30406，并在 `d9b39a8` 发送 `0x2c0`=704；WriteTo 195688（`1df36600`）和 .cctor 195661（`1df37110`）核对编号1、额外类型4、槽位列表7、领队9、场景11及额外变体字段。DBMILNKFCJP 30404 的 WriteTo 195652（`1e1340b0`）确认角色5、助战7、槽位9、类型10。

TeamModule._OnCmdReplaceLineupScRsp 625119（`e1ddb00`）的槽 `0x936e978` 对应 JHICJLAIPLD 30408；回调先检查 +0x1c 错误码，普通编队分支再消费 +0x18 编队编号。WriteTo 195709（`1e7191a0`）将这两个成员编码为字段 9 和 7。719 命令号来自已固定 Honey 原始 dumper 的 ReplaceLineupScRsp 记录，与同一本机消费者/类型核对；参考整理后的简名 JoinLineupScRsp 字段6不能用于该回包。Java 新增独立原生处理，不修改冻结定义或命令表。

复现：`python scripts/analyze-client-46.py --client-dir <本机客户端目录> --method-indices 625092 625119 635271 195652 195661 195688 195709 --type-slots 0x93e90f8 0x936e978 --disassemble 2200 --output build/client46-lineup-evidence.json`。实际 KCP 只重交既有宇宙单人队伍，虚拟/试用/故事线、普通编队及多角色游戏操作仍未验收。


## 跑酷原生数据与比赛消息

仍使用本文同一SHA限定的CN4.6本机文件，只读取元数据/指令，不加载DLL。固定AGPL Honey fe00bdb 的`Proto/4.6/CmdParkourType.proto`提供命令及定义，本地新增Java不导入C#实现。

- `ActivityParkourModule`类型61828：569876 StartLevel(C57A050)调用`EHFGOPCDBHP`570219 OMGAPBGFKPJ(15498900)，在15498A4C发送0x20C0=8384。槽93B4360对应31485 HJELNANIFIE，206669 WriteTo(1E579550)把+1C关卡写为字段1(+08)，+18球车写为字段4(+20)。8384/8393的回复FLAIJDHDEBI(31487)由槽93ACA90和569888接收者确认；206692 WriteTo(1E386F90)把+20结果码/+18关卡/+1C球车写为6/10/14，与冻结定义一致。
- 结束路径：569877 EndLevel调用570220 OPEFLHHAHNK(15498BA0)，转发到NetworkManager634923 JBFOMOJECEK(D99FE20)，D99FF21发送0x20D0=8400。MFMOKFNLNNB类型的get_ElapsedMs(570100 B906480)、get_Reason(570104 B9064C0)及其他命名访问器确认+4C为毫秒用时、+40为原因、+34为名次、+38为总用时。发送者分别复制至BLFMKDAGCFO(31497、槽93E8630)的+48/+34/+44/+3C；其WriteTo验证字段3/14/15/6。+44关卡→请求+40字段1，+30球车→字段12。主体统计对象不据此解释成碰撞效果。
- `LOADPICBEPE`674537 CHAHBFGGIDL(B7E9AA0)在B7E9B8C将构造参数reason置2，经MFMOKFNLNNB .ctor570095(B906590)保存并传入SettleGame570021(DA77E80)。这是客户端普通结算构造路径的静态证据；Java对reason1采用仅终止、不计成绩的保守规则，不声称所有异常场景语义已验收。
- 回复8385：569889接收者使用槽93ACAA0对应31499 EJNFAIOPNEO，206863 WriteTo(1E290680)核对结果码2、纪录4、关卡10、达标11、原因12、时间13、首次15。ParkourSettlement570243 Create(DA7DDF0)复制回复+21至IsFirstPass(+30)，+20至IsSuccess(+31)，是否存在记录对象至HasBrokenRecord(+32)，明确三个字段不同。
- 数据8390/8396：569887接收路径C57ACC0核对槽9388350→31475 KKKCJLHIGKO，206566 WriteTo(1E84C5D0)将+20结果码发送字段14；+18为纪录列表，.cctor的重复消息标签使用字段15。每关记录字段4/6/8沿用固定协议定义；服务端只填实际记录。正常成绩/存档恢复为Java回归，实际KCP覆盖未玩查询/未开放开始/未开始结束，客户端UI未验收。

复核命令使用`scripts/analyze-client-46.py`，方法索引569876、569877、569887～569889、570100、570104、570219、570220、570243、634923、674537，以及类型槽93B4360/93ACA90/93ACAA0/93E8630/9388350；通过`--method-names WriteTo .cctor --disassemble 1900`核对序列化。不同文件SHA不能复用结论。忽略目录报告为`build/client46-parkour-*.json`，不包含客户端游戏验收。


## 跑酷Quest判定资源证据

固定TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b中的ActivityPanel(50039)关联6051100..6051117；QuestData将这18项目标标记为QuestType=3/AutoUnlock。FinishWay的12项ParkourFinishLevel为ListContain，5项ParkourReachRankInLevel为Equal且以ParamInt1/2指定关卡/名次，汇总6051117为FinishQuest/ListContain、目标17；全部IsBackTrack=true。TextMapCHS中6051102/6051105/6051108/6051112的标题说明取得前二，6051116说明取得第一，名次比较因而采用不大于阈值。标题只用于只读核对，文本/资源不重新打包。

固定AGPL Honey fe00bdb 的GameServer/Game/Mission/FinishType/Handler/MissionHandlerFinishQuest.cs统计QuestFinish或QuestClose；本项目Java独立实现相同计量，并将自身CLAIMING阶段仍视为目标已完成。没有把该参考当作完整跑酷实现，没有改动冻结协议或生成类；没有客户端UI验收。


## 跑酷排行字段与NPC合并

同一SHA限定本机CN4.6：569878 AsyncGetRankingWithPromise(C57A120)调用570221 CJCBDOEBBJC(15498D20)，15498DE2发送0x20CD=8397。569890 _OnParkourGetRankingInfoScRsp(C57B0E0)以槽937ECB0确认31483 KIAGIOBGGNK；206648 WriteTo(1E823D80)把+28结果码写为字段3、+18汇总消息为12，206635 .cctor(1E8243D0)以0x72配置+20逐关列表为14。成功路径会无条件把汇总对象传给_CreateParkourRanks，空玩家列表仍必须提供该消息对象。

COIMBCBMNIE31479的206609 WriteTo(1E106FE0)确认+20关卡→字段2；206598 .cctor(1E107480)以0x42配置+18列表→字段8。HANMCLKOCBO31477的206589 WriteTo(1E4DCD60)确认+18用时→2、+20玩家UID→3、+1C球车→14。570212 ParkourRank.Create(DA7D390)将+20与本地玩家UID比较或用于好友查找，将+1C用于球车资源查找；字段id不是球车编号。冻结定义的字段编号匹配，原注释的内存偏移不作为本机证据。

569895 _CreateParkourRanks(C57B510)先转换网络列表，再遍历本地ParkourRankingList并调用570215 Create(DA7D990)；后者按指定关卡取资源时间，关卡0对资源时间求和。NPC合并由该客户端路径完成。静态报告为build/client46-parkour-ranking-consumer.json、ranking-fields.json、rank-entry.json；文件只读，未启动客户端或进行UI验收。

570210 ParkourRank.get_IsWithdraw(DA7D2F0)在DA7D301读取+2C Record并与0比较；资源的1/4号NPC缺省用时为0，因此不能仅因资源用时不是正数就拒绝整个排行查询。个人网络纪录仍需要正用时和已保存球车证据。


## 跑酷Raid入场与离场

同一SHA限定本机CN4.6：613797 _SendStartRaidReq(DE40850)普通分支跳至635469 PLGHPBGFGIG(D9C55F0)，D9C5801发送0x8BA=2234。该构造器设置新的AvatarIdentifier列表；216154 GDCGMBJAEGN.WriteTo(1E416770)核对world_level字段2、is_save3、prop_entity_id6、raid_id9，216136 .cctor(1E416F10)的0x22/0x6A分别是标识列表4和旧编号列表13。客户端内存偏移与冻结注释不同，不据此改字段号。

613783 _OnCmdStartRaidScRsp(DE43C90)使用槽9361C10确认32374 KPJCDAOHOAA，216175 WriteTo(1E8903B0)核对scene字段5和retcode12；成功交给613784 _DoEnterRaid(DE43D30)，无条件读取嵌套场景、队伍及Raid身份。216178 MergeFrom的槽936F868确认32370 GEGLJDELBHA；216127 WriteTo(1E42B530)核对MOPHKIGKDIK场景1、lineup9、raid_id12和world_level15。成功入场不能只发空响应。

613761 LeaveRaid(DE40C40)调用635470 LFCPCKABAFM(D9C58E0)，D9C594A发送0x8AC=2220；216196 KIKOJGLGJFE.WriteTo(1E835B20)核对is_save6/raid_id14。613743 OnLeaveRaidScRsp(DE3F240)以槽93C2070确认32378 FPHIKGOGJIP；216219 WriteTo(1E3DBEB0)核对retcode3/world_level5/raid_id12。613737 OnCmdRaidInfoNotify以槽9388E00确认32382 GKNMMJHBFEM，216270 WriteTo(1E48E220)核对world_level4/status5/raid_id8，216251 .cctor的0x62对应目标列表12。GetSave/GetAllSave/GetRaidInfo目录字段仍依据冻结的同一AGPL来源，本机完整目录/UI消费尚未验收。

固定数值资源的parkour_raid_id、入口、楼层都是44306001，Plane44306为Raid。RaidConfig为TreasureChallenge/Player/SkipUI，空主任务、试用、目标和奖励列表，SkipRewardOnFinish=true；不存在可保存副本记录。Java只为这一资源形状实现进入/返回，不从空MainMissionIDList猜主任务或全Raid支持。静态报告位于build/client46-raid-*.json；未执行或修改客户端，仍无游戏UI验收。

## 消耗品558/556的本机证据

沿用本报告前述CN4.6 GameAssembly/metadata SHA限定的只读解析，未运行或修改客户端。InventoryModule（类型65684）的UseFoodItem602744（D358B00）调用NetworkManager的OMMFDKJIECF634796（D998F60），D998FB3发送0x22E=558；其对象写入物品+1C、数量+24、角色+20、角色类型+2C、布尔分支+28。请求类型槽93DB120解析到BDHDBGECGJA（30118），WriteTo192642（1E8E9230）逐项输出字段1/2/8/9/12/14。这里本机offset与冻结定义注释存在排列差异，采用已核对的序列化标签，不从offset注释猜字段。

_OnCmdUseItemScRsp602856（D367FE0）核对类型槽93716F8，解析为FKOIIHNOICH（30120）。WriteTo192671（1E385500）核对字段3（uint64月卡）、5（实际数量）、8（物品）、10（错误码）、11（ItemList）、15（配方）。556命令与命名对应沿用固定AGPL4.6来源表；本轮没有单独重建NetworkManager响应注册表，完整客户端接受仍未验证。布尔分支、可选奖励、试用/其他角色类型不按名称推测，当前拒绝非默认请求。

证据输出在忽略目录build/client46-use-item-{types,calls,request-build,wire-types,codec}.json。Java原生消息覆盖成功/失败/未知字段/整数别名；KCP探针只检查未持有401006时拒绝使用并返回零扣除、空奖励，正向食物/礼包当前为Java合成回归，不能称为真实游戏UI验收。


## 选择任务物品1261的本机证据

沿用前述GameAssembly/metadata SHA限定的只读解析。MissionModule606956 AddFinishMissionInfo_SelectConsumeItem(D6D10B0)保存独立分支1和两个选择参数，606955普通ConsumeItem分支为0；类型槽938ADF0解析为ClientFinishMissionInfo(66142)，而不是直接完成任务。606949 ClientFinishMission(D6D05B0)创建槽93DFA30对应的流程类型FKHJLDPKMPG(66133)。606932 IACOKNFJNPE(157EE480)取选择参数+18和子任务+44，调用634889 PKLHAJKLBEI(D99E300)，存在所选项目时转入634888同名重载(D99E1A0)。后者D99E28F发送0x4ED=1261，写请求子任务+20及ItemList+18；无选择分支也发送1261，服务端不能代替单选任务选物品。

请求槽93EC7E0解析为BMGLOGMBKLF(30954)，201233 WriteTo(1DFF9040)核对sub_mission_id字段2与ItemList字段4。选中项列表槽935CEA8为ItemList(27784)，条目槽93AC990为Item(27781)，请求只接纳item_id/num，不从额外物品属性构造费用。固定同源Common.proto的ItemList字段2和Item字段15/6继续使用，未修改描述符。1208响应命令及5/9字段仍依据固定AGPL4.6协议源；本轮没有独立重建其响应注册与实际UI接受。

固定4.6资源16个UseSelectedItem定义均为ListContainList、ParamInt1=1、Progress=1、每备选ItemNum=1；301160501的SelectMissionItem明确SlotNum=1并列401004/401012。服务端支持这一已核对的子集，信用点2按资源中的实际一单位扣除，不推测更大金额。静态证据在build/client46-mission-selection-*.json，未运行或修改游戏。Java验证正常选择和持久化；KCP新增检查仅验证未接取434014905的交付拒绝，正向客户端交付和全剧情仍待验收。

## 任务区间条件的本机静态证据

继续使用已锁定SHA的GameAssembly.dll与global-metadata.dat，未执行或修改游戏。ConditionCheckItemFactory方法550985（RVA CBB0030）的条件12分支通过类型槽9382488定位PPOOKJPJIHB（59873）；其_DoCheckImpl方法550878（C3918A0至C391A90前）分别查询两端子任务，起点状态比较1/2，终点拒绝状态2。SubMissionData的607572/607574分别验证2为IsFinish、1为IsStart；607573另将3识别为已接取未开始，不能把它等同本条件的进行中。MissionModule.GetSubMissionData方法607199（D6C8760）查实际模块字典，未找到返回null。服务端以可信任务定义及实际状态判断，查询不制造完成记录。

输出：`build/client46-condition-factory.json`、`build/client46-between-submission-evaluator.json`、`build/client46-between-submission-check.json`、`build/client46-submission-phase-values.json`。区间表达式解析及服务器档案语义仍由Java回归验证；静态代码不能证明完整剧情UI验收。SceneInfo维度字段1225使用冻结4.6参考定义并有原生字节/包帧回归，本轮未新增该字段客户端序列化或注册证明，未伪造剧情编号2045。

## 2026-10-02 剧情通知的本机语义

仍只读核对同一固定SHA的国服4.6客户端，不加载/执行DLL、不修改客户端。`StoryLineModule._OnStoryLineInfoScNotify`（623710，E13A770…E13A800）经类型槽93EBCB8定位到`JEEOPLLKEEH`（34287）。接收路径把对象+18列表交给`_RefreshStoryLineStatus`，把+2C当前ID和+10列表交给`_OnStoryLineUpdate`；后者调用`StoryLineData.SetTrialAvatarIDs`（623662，E137D90），再切换实际分支。

`JEEOPLLKEEH.WriteTo`（235845，1E6E9EB0…1E6EA030）使用+18对应的5C498编码器和+10对应的5C4A0编码器；静态初始化235831（1E6EA390…1E6EA4D0）分别以1A和5A构造UInt32列表编码器，即字段3和11。当前ID从+2C以48写出，即字段9；+28未知值以字段8写出，不猜测用途。因此，6205通知的字段3是未结束分支、11是试用ID、9是当前ID。冻结Honey定义的两个列表名称相反，运行适配层按字段号修正语义，不修改原始定义/描述符/生成类。

服务端新增字节回归与整份玩家的Morphia保存/重载。SceneInfo字段2045来自冻结定义，在实际活动分支场景中发送并有原生字节回归；尚未独立定位本机SceneInfo该字段的消费/编码。6234/6225查询仍以固定协议来源为基础；6216切换结束消息另有下面的本机编码核对，不能据此宣称完整游戏调用路径已验收。

只读定位工具新增`--method-name-contains`，要求至少8个字符、最多64项，用于寻找编译器回调。`<SendStoryLineData>b__7_0`（623731）指向`StoryLineData.PrepareData`（623660），这不构成GetStoryLineInfoCsReq网络调用证据。忽略目录中的报告用于复核：`client46-storyline-info-wiretype.json`、`client46-storyline-info-write.json`、`client46-storyline-info-codecs.json`和`client46-storyline-trial-list-consumer.json`。

## 2026-10-02 剧情队伍身份的本机静态证据

同一本机固定SHA只读分析：TeamModule._OnCmdSyncLineupNotify（625114，E1DD6F0…E1DD7B0）的类型槽936E8A0对应EOBONCLPPLM；其MergeFrom（195366，1E2CAEB0…1E2CB090）在字段11创建槽935D1F0对应的PKPICFPDHEO（LineupInfo）。后者WriteTo（195155，1DF6BBF0…1DF6C210）核对+48为extra字段15、+4C为story字段9、+54为plane字段13、+40为index字段14。静态初始化195121（1DF6CC80…1DF6CE40）与WriteTo共同核对列表：+38/编码器1D728是字段5，+18/1D718是字段8。

RefreshLineup（625144）的E1DCD2E…E1DCD44把virtual/extra/index/story传入_GetTeamData（625153，E1D6460…E1D6520）；后者先用非零extra调用GetExtraTeam，只有extra=0、story非零才调用GetStoryLineTeam（625062）。因此故事线必须发送extra=0及字段9；内部13仅用于Java兼容。_UpdateTeamData（625152）把LineupInfo+18复制到TeamData+48，get_AvailableTrialAvatarIDs（625019，E1C5980…E1C5990）直接读取+48，确认字段8为可选试用列表。字段5的队员语义同时参考固定Honey Common/Database/Lineup/LineupData.cs，只发送本局实际试用队员。

ChangeStoryLineFinish的消费类型槽93DA088定位ELGBFAEBOCN（34289），WriteTo（235870，1E2A1B40…1E2A1D20）核对action从+18以58写出（字段11），cur从+1C以78写出（字段15）。其余bool4/uint8含义仍未知，不赋值。命令号使用固定来源；本轮未独立重建这些命令的完整CN注册。

StoryParty46由本项目独立Java实现。704请求字段13及槽位来自固定4.6来源并经严格原生Java回归；719响应7/9沿用已有本机JHICJLAIPLD证据。真实剧情UI、正向剧情KCP、SkipJoinLineup和动态试用加入/移除仍待验收/实现。报告位于忽略目录build/client46-lineup-*.json、client46-storyline-finish-and-team-identity.json和client46-team-data-methods.json，未运行客户端或参考二进制。


## 2026-10-02 普通试用开始、退出和完成通知

仍只读前文固定SHA的本机4.6文件。PE异常目录独立核对方法边界：RoleTrialModule.StartRoleTrial（622811，E05EE10…E05EEDB）把阶段参数写到DBJDNDEIKDM+18，并发送0xA66（2662）；类型槽9396408及其WriteTo（149317，1E130D30…1E130DAF）确认字段1。_OnStartTrialActivity（622806，E05EA10…E05EC68）从OIMNAIDOGJI+18读取错误码，+1C读取阶段；WriteTo（149338，1DE47D50…1DE47E25）分别写字段11和5，与冻结定义的字段号一致，但内存偏移顺序不同。

LeaveRoleTrial（622812，E05EEE0…E05F026）读取当前实例阶段到PIGBCIDLGCA+18，发送0xA4C（2636）；类型槽93B2D38及WriteTo（149357，1DF18060…1DF180E0）确认tag60，即字段12。JDDFIOBBHOF.WriteTo（149378，1E6DB0F0…1E6DB1D0）确认响应+1C阶段为字段5、+18错误码为字段9。这两个编码器范围以同类型下一个CalculateSize入口截断；开始/通知编码器范围另由PE异常目录核对。

_OnTrialActivityNotify（622809，E05ED10…E05EE03）类型槽93E7368指向BKHKMFEDPBE，状态+18值1调用_OnLeaveAndClearInstance（E05E8C0）；值0且实例为空时从+1C阶段构造实例。WriteTo（149399，1DF2AA40…1DF2AB2C）把+1C以08写出（字段1）、+18以50写出（字段10）。因此主动放弃不发送状态1，也不发送可能重建实例的状态0。_OnLeaveTrialActivity（622807，E05ECB0…E05ED05）自身调用清理流程。

继承核心CurTrialActivityScNotify命令为-1，不能穿过4.6转换层。TrialActivity46使用已固定4.6命令/描述符直接发送2646及阶段1/状态10，保持CmdId、原始协议和生成类不变。本轮未独立证明2646/2614/2677的完整命令注册；其命令号仍取固定Honey来源，消费类型和字段已由本机交叉核对。报告build/client46-role-trial-commands.json、wire-types.json、encoders.json、leave-send.json、leave-types.json和leave-encoders.json均为忽略的本地静态证据；没有运行客户端，clientAccepted=false。

## 2026-10-02 剧情试用变更6258静态边界

继续使用前文同一SHA限定的只读DLL/元数据分析，未执行客户端。StoryLineModule._OnCmdStoryLineTrialAvatarChangeScNotify（623713，E13ADD0至E13B630）类型槽93F5B80解析为NGBNCHOLHFE（34291）。WriteTo（235891，1EADDC50至1EADDDB0）与cctor（235879，1EADDFD0至1EADE0E4）核对对象+18列表使用静态codec63830/tag12（字段2），+20列表使用codec63840/tag7A（字段15），+28布尔使用tag70（字段14）。消费+18时逐个构造类型槽936EF88的TeamGetTrialAvatarParam并复制该布尔，通过事件53C发送；消费+20时逐个通过事件53D发送另一通知。布尔对实际队伍操作的含义、53D消费者及完整动态任务触发仍未证明，运行时不猜测6258字段。证据文件build/client46-storyline-trial-change-type.json、trial-change-wire.json、trial-events.json为本地忽略的静态报告，不属于客户端验收。


## 2026-10-02 普通试用取奖和已通关查询

只读核对前文固定SHA的同一本机CN客户端，未加载DLL、未启动或修改客户端；以下均为静态证据，clientAccepted=false。冻结Honey的2613/2623及2653/2644命令不由修改CmdId或描述符产生。

RoleTrialModule.TakeStageReward（622815，RVA E05F3B0至E05F4E5）将实际阶段参数写入93EC5A0槽的NAGFBDNFCEP（25972）偏移18，发送0xA5D即2653。WriteTo（149275，1EA93AF0至1EA93B6F）从偏移18编码tag40，即字段8。取奖响应消费（622838，E060CD0至E060F12）核对93B2D70槽的BGEFEAPPGKN，使用偏移24的阶段和偏移18的奖励；WriteTo（149298，1EA453D0至1EA45501）分别编码偏移20返回码tag08字段1、偏移24阶段tag18字段3及偏移18奖励tag62字段12。响应命令2644继续由固定协议表提供，未在本机完整注册链中独立确认。

查询消费（622836，E060850至E060AC6）核对9361478槽的GAPLJKIPAOF（25964），遍历偏移20集合逐项交给_AddPassedStage（622835，E0607C0至E060848）；后者把记录偏移1C编号加入已通关集合，并在偏移18布尔为真时加入已领奖集合。GAPLJKIPAOF.WriteTo（149196，1E3F8D20至1E3F8EB4）编码偏移28阶段tag20字段4、偏移2C返回码tag38字段7。其偏移20集合使用静态B7A0 codec；cctor（149182，1E3F92B0至1E3F9484）初始化tag42字段8；偏移18另一集合用B7A8/tag5A字段11。记录内部字段14/1继续依据固定Honey TrialActivityInfo描述符与消费语义，尚未独立定位该记录WriteTo；增强角色集合字段11未完整实现。查询请求/响应2613/2623命令来源为固定Honey表，不能将字段消费证据当作本机完整命令注册验收。

新增适配只按持久化真实通关记录查询及一次账本领奖；并发/非法字段/未通关/重复/部分发放有Java对照。窗口目录、增强角色集合与游戏客户端UI仍待完成/验收。


## 2026-10-02 试用技能自定义预设消费者

同DLL SHA-256 8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3及元数据SHA-256 09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130。方法541587 GamePlayStatic.GetSkillTreePointLevelsByTemplate，PE范围E6AD970..E6ADFDB：E6ADA65..E6ADA7A按模板1/2/3分支；模板3在E6ADBC7..E6ADBCF查BattleLineupJsonTable.GetSkillTreePresetConfig，逐预设锚点后对未列节点只补DefaultUnlock一级（E6ADE8E..E6ADEAA）。E6ADE74..E6ADEF3等级政策：1取技能MaxLevel；2调用E6AE0A0；0取预设固定值与技能MaxLevel较小者。

541588 GetSkillTreePointSuitLevel，E6AE0A0..E6AE1C6：一级DefaultUnlock可直接一级，否则比较技能行+94角色等级与+88晋阶；逐更高级真实行取满足限制的最高等级。522137 GetSkillTreePresetConfig，E5BF6A0..E5BF832返回命名预设；522135 LoadSkillTreePresetConfig从配置解析并按Name入表。规则标签Fixed（缺省）、MaxLevel、MaxWithCharacterLevel由固定724b139d资源11份JSON交叉核对，全部当前9组自定义键可关联。DBDC配置池及DesignData共56文件/372410386字节搜索无明文预设键，只证明明文搜索未找到，未解析该二进制池；资源预设来自固定OS快照，不能据此宣称CN入口验收。


## 2026-10-02 试用活动目录消费者

同DLL及元数据固定SHA，客户端只读静态分析。568266 RoleTrialActivityData.get_TypeParam（E05D250..E05D2CC）经568699 ActivityPanelData.get_RelatedActivityID（C5711D0）查ActivityAvatarDemo表77468，再取+10关卡列表；622844 RoleTrialUtils.GetActivityAvatarDemoStages（E061640..E0616BD）直接查相同活动表。资源ActivityConfig提供ActivityID/ActivityPanelID/ActivityModuleIDList，不能按面板编号猜活动编号。

568499 ActivityData.get_ScheduleData（C3DE620..C3DE6F4）调用568498 get_ActivityModuleID（C3DE540）并以该编号查ScheduleModule。622958 _OnCmdGetActivityScheduleConfigScRsp（E097DD0..E0982B4）在E098013读取网络对象+18编号，调用E098300查已有记录或E09807E创建；对应冻结协议ActivityScheduleData.activity_id=11、内存偏移24，为模块编号。服务器保留panel_id=1与模块activity_id=11；不修改生成协议。合成KCP只验证服务器字段与返回，不证明实际游戏UI可用。


## 2026-10-02 祝福商店原生字段与奇物购买入口

继续只读前文同SHA本机4.6 DLL/metadata，未执行客户端或参考二进制。SendGetRogueShopBuffInfoCsReq（617644，DEEAC50…DEEADCA）调用DialogueManager.GetCurTalkEntityServerEntityID（626645，CBFF220），向MJCPFFDLGGC+18写实际交互实体，以0x15F0发送5616；WriteTo（221948，1EA184B0…1EA185B7）核验实体字段15、刷新bool字段6。SendBuyRogueShopBuffCsReq（617645）调用NetworkManager.PPLGFGAICJM（635126，D9AB640…D9AB70B），向LECIGPPCMAB+20写实体，以0x15EF发送5615；WriteTo（222108，1E8F7740…1E8F7812）核验实体字段2。其cctor（222097，1E8F7B00…1E8F7C54）用tag32初始化重复字段，FieldCodec.ForUInt32（39696，1B835590）确认字段6为packed uint32商品编号列表，不是单件祝福消息。冻结命令NMMLGOOGHBD对应5615，BuyRogueShopBuffCsReq仅本项目清单语义名，不改冻结协议。

查询APCHMPGJGGC.WriteTo（221975，1E645D70…1E645F91）核验商品1、费用4、整数7/11和结果码8；购买DCAEIHGJFDE.WriteTo（222170，1E139190…1E139267）核验结果码5、商品7。RogueAdventureModule消费者617966/617967的DEDE49B/DEDE5A8将对应商品传给RogueBuffShopData.SyncItem（617642，DEE9FF0…DEEA5EB）；后者把KJEOBMBMNIN+2C/+28作祝福ID/等级，+31反转作Available、+30作AlreadyHave。KJEOBMBMNIN.WriteTo（221846，1E83CBB0…1E83CE5E）对应字段13/15、3为已售出/不可用标记、7为已持有、费用1/原价11。DAENHKENJHK的cctor221900用tag12确认列表字段2。字段号与冻结定义相符，内存布局差异不作为修改生成类的理由。RogueBuffShop46直接使用现有响应描述符，严格读取原生请求并拒绝刷新true，未核对的刷新次数/费用留默认值。

奇物Get发送617704（DF3DDE0…DF3DF56）确认5675与字段12实体/13刷新；Buy发送617705（DF3DF60…DF3E0E5）确认5656而非同名5672别名，MKNBFMJGKBM.WriteTo（222088，1EA2EC80…1EA2ED55）确认商品字段6和实体12。这里只记录证据；奇物商店未实现，不因类型存在标为可用。报告位于忽略目录build/client46-*-shop-*.json。固定资源RogueRoom与实际MapEntrance/LevelGroup交叉只找到6个休息房NPC1007商人，不能推断完整经典/脑内宇宙房间生成已完成。正向交易为Java域/原生字节及Morphia回归，完整游戏UI和正向商店KCP未验收。
## 2026-10-02 奇物商店原生消息与商品可购标记

继续只读前文固定SHA的本机CN4.6客户端，未运行或修改DLL/客户端；clientAccepted=false。RogueMiracleShopData.SendGet（617704，DF3DDE0至DF3DF56）通过DialogueManager.GetCurTalkEntityServerEntityID（626645，CBFF220）取得当前交谈实体，向CICKIFGMKPF写入+18并发送0x162B即5675；WriteTo（221996，1E0A1680至1E0A178B）以tag60编码该字段12、tag68编码+1C刷新字段13。SendBuy（617705，DF3DF60至DF3E0E5）向MKNBFMJGKBM写入+18商品/+1C实体，发送0x1618即5656；WriteTo（222088，1EA2EC80至1EA2ED55）分别使用tag30字段6、tag60字段12。使用实际5656，不选择冻结表中另有的同名5672别名；未修改CmdId、原始协议或生成类。

查询消费（617964，DEDE320至DEDE398）的类型槽939E300对应DDHJFDPBPAA，读取+20商店；WriteTo（222019，1E150F60至1E151093）核对费用字段2、错误字段6、商店字段8。购买消费（617965，DEDE3A0至DEDE418）的槽93B4320对应DLJLBCGBOCB，读取+18商店；WriteTo（222149，1E1BA360至1E1BA437）核对商店字段1、错误字段5。响应5660/5683命令号仍使用固定Honey来源，未独立重建全部本机命令注册。

容器AGKONMPOJKP的cctor（221882，1E101340）将tag2A用于ELGOBKCNOHH消息列表，即字段5。商品WriteTo（221817，1E2A4090）核对+28编号/tag10字段2、+10费用/tag1A字段3、+20原价/tag32字段6、+2C标记/tag38字段7、+2D拥有/tag70字段14。RogueMiracleShopData._SyncItems（617707，DF3D8D0至DF3DD9D）在DF3DAE1读取商品+28至edi、两个费用至rsi/rbx、两个标记至r14b/r13b，DF3DB03至DF3DB1B将第一个标记取反为构造参数Available、第二个原样为AlreadyHave，并调用RogueMiracleShopItemData构造函数（617710，DF3E190至DF3E5D6）。因此GCIHGKCJOGB表达不可购，不能仅当售出位：祝福商店亦据此前同型消费证据修复资源失效时仍报Available的错误，失败回归后通过；保留原报价和未售记录。

本地报告为build/client46-miracle-shop-item-types.json、client46-miracle-shop-row-codecs.json、client46-shop-response-codecs.json和miracle-consumer-bounded.txt，均不进入发布包。奇物商店当前正向回归使用隔离Java资源夹具；三个MiracleShop脚本关联的59个真实场景组主要属于扩展宇宙，不在当前基础RogueRoom分配中。字段静态证据不代表完整UI、扩展宇宙或客户端验收。


## 2026-10-02 列车世界切换字段

继续只读同固定SHA客户端。AdventureModule._OnTrainWorldIdChangeScNotify（571991，C5DB0C0至C5DB23B）类型槽936F1B8对应KEJANPDMOIL，读取+18至模块+1B8并清除当前OptionBlockKey；KEJANPDMOIL.WriteTo（234374，1E7DECB0至1E7DED2F）以tag08写该世界编号，字段1与冻结协议一致。服务端声明动作经保存后使用现有1459通知，不修改生成协议。

SendSetTrainWorldIdCsReqWithPromise（572003，C5DC1C0至C5DC32F）调用AdventureStatic.GetAdventureMapRuntimeID（541826，E588120至E5881F9），向AEBLDMIMFIA的+18写返回UInt64、+20写目标世界UInt32，发送0x5D1即1489；WriteTo（234395，1DDB8A70至1DDB8B47）确认字段5/9。该helper最终读取当前地图对象+1D0，而非交谈实体ID，冻结字段名interact_id不能用于推断NPC权限。_OnSetTrainWorldIdScRsp（571992，C5DB340至C5DB418）槽93714D8对应GFCAHBOAJIB；WriteTo（234416，1E432CD0至1E432DA5）以tag38写+1C错误码字段7、tag48写+18世界字段9。1401响应命令仍使用固定来源，未独立还原全部注册。

地图运行编号与SceneInfo的完整生产链尚在核对，因此当前不按猜测关联接受主动切换。原生静态报告位于忽略目录build/client46-train-*.json，Java通知与存档回归不是游戏客户端验收。


## 2026-10-02 场景世界与地图运行编号生产链

同固定SHA只读。AdventureModule._OnCmdGetCurSceneInfoScRsp（571986，C5DA740至C5DA7BD）槽93B54E0对应AGAAHIBNOBP；该类WriteTo（232787，1E0F8290至1E0F8367）编码+20错误字段1、+10场景字段10。MergeFrom（232790，1E0F84C0至1E0F8659）字段10分支经槽935D1E8构造KHNBFDNKLHE（232232，1E81C140）。消费者将+10直接交给C5DA7C0，无转成中间场景数据；C5DA8F8调用_EnterMap_SetupCurrentWorldAndOptionBlockKey（571999），读取该对象+78。KHNBFDNKLHE.WriteTo（232279，1E81CED0至1E81D57A）在1E81CFC1以tag18写+78，确认world_id字段3。

同一消费者C5DA8E5调用_EnterMap构造辅助入口C5DB610，C5DB629调用D539F20；后者D539F48将同一场景对象传给FEHLIMILEMA第二构造（573043，156E42B0至156E44B0）。156E430F取场景+98至r14，经156E43B3传MapDef.Create（572756，D525DB0至D525EF5），再经D525E24传MapDef构造（572735，D525F00至D526A9D）；D5266A9把该UInt64存至MapDef+1D0，get_MapRuntimeID（572958，D5398E0至D5398E8叶方法）和前述GetAdventureMapRuntimeID读取相同字段。SceneInfo.WriteTo在1E81D34B/1E81D34D写tag字节B8/40并序列化+98，解码tag8248对应字段1031。因此请求1489字段5关联SceneInfo字段1031，非NPC实体。

直接调用候选由PE异常目录函数边界和完整函数起始解码核对；仅搜索到字节形状的候选不作为证据。D5398E0叶方法没有PE异常条目，按实际mov/ret及后续方法地址限定范围。尚未证明官方编号生成策略或游戏UI接受本项目编号，clientAccepted=false。报告位于build/client46-mapdef-*.json、client46-train-scene-*.json及只读调用定位报告。


### 2026-10-02 剧情计数器的静态边界

本机固定SHA的4.6元数据中，AdventureModule._OnCounterDownScRsp为方法572034/RVA C5DD930，_OnCounterUpdateScNotify为572035/C5DD980。后者正常本机函数体直接返回，另一分支调用热更新解释器；方法名不能证明字段语义。冻结定义仅能确认1452 CounterDownCsReq的两个uint32字段8/9、1494响应retcode字段3以及1435通知字段3/5/7。资源202060212声明ActivateGameplayCounter[280001,99,1]，但99/1的规则和倒计数请求各字段意义尚未确认，仍不接入该动作，也不修改生成协议。独立静态报告client46-counter-named-methods.json和counter-task-bounded-bodies.json位于忽略的build下；没有启动/修改客户端。


### 2026-10-02 花萼波次映射

固定Honey fe00bdbda3c28ca33d01f171566aa35b5ab95083 CmdSceneType定义普通StartCocoonStageCsReq 1432/world3/wave4/cocoon7，响应1484/wave8；CmdAdventureType快速请求1360/world1/wave3/cocoon7。该固定提交的两个接收处理器读取req.Wave传入StartCocoonStage，快速响应写Wave。内部冻结4.2另有wave和wave_count，但Java两个处理器实际读取getWaveCount。CocoonWave46Test先复现缺少wave_count映射时ProtocolException，显式映射后通过；4.2字节不变，生成类未修改。没有据此认定HECCOBFBJFI、CBJCKEIGGNL等附加字段已支持或客户端已验收。


## 2026-10-02 花萼普通与快速原生入口

`Cocoon46` 接管已冻结的普通1432/1484与快速原生命令，调用原有真实 `BattleService.startCocoon`，返回实际花萼ID、挑战波数及转换后的 `SceneBattleInfo`。旧4.2普通响应命令为-1，`GameSession.send(BasePacket)`会丢弃；新增入口不修改CmdId、生成协议或VERSION。波次桥接4条回归含3条先失败，原生入口5条回归含2条先失败；认证门槛、重复/截断/宽整数/未知与附加字段拒绝及4.2旁路通过。波数要求正数且不超过资源MaxChallengeCnt；HECCOBFBJFI/CBJCKEIGGNL非默认请求拒绝，普通prop_entity_id尚未关联场景物件身份，其他助战/强化/体力变体及游戏UI待完成/验收。

`scripts/smoke-farming-mission.py` 在隔离目录使用真实4.6花萼1001/世界等级0及本项目两步CocoonFinish任务19996923；普通入口三波、快速入口再一波共扣40体力，第一胜不消耗新解锁第二目标，错误战斗ID与重复结果拒绝。同账号重新登录及H2服务进程重启恢复完成目标与余额260，原始MainMission资源校验不变。此次运行 `build/smoke/farming-mission-vkw0uyhg` 五项通过；任务目录仅含这个合成任务，其他资源连接只读源目录。这不是官方主线/战斗演出，也不是游戏客户端验收；clientGameplayVerified=false。H2重启等待延迟落盘，不证明写入中断事务恢复。


## 2026-10-02 显式开局角色选择与剧情目标

`Profile46` 处理原生41/30首次SetPlayerInfo及80/67基础查询。只接受is_modify=true、非空且不含控制字符的32个UTF-16单位内昵称和性别1/2；资源必须声明8001基角色及匹配性别，实际已有的8001/8002命途和正式角色须属于玩家。战斗、试用/实例、宇宙/跑酷及未完成剧情切换期间拒绝。首次姓名、性别、实际角色命途和 `profile:character-path` 确认标记在同一玩家文档保存后刷新任务及通知；重复同选幂等，冲突不覆盖。该首次选择与不可重选规则是本项目保守实现，不代表完整官方资料变更政策。

`CreateCharacter` 仅支持Equal、Progress=1、其他整数/列表/字符串为空并匹配任务平面/楼层的目标；只根据明确保存的选择记录推进，不把默认男主或查询当作完成，不硬编码完成100010134。旧无确认标记不会推断为已创建，原有昵称和性别不会自动修改。不支持/取消目标与未知完成动作保持待完成。9条Java回归含2条先失败，覆盖真实角色路径、参数/身份拒绝、6次并发、同选防重、原生响应字段与实际Morphia文档重载。

本机SHA核对后的PlayerModule612901 `_OnCmdSetPlayerInfoScRsp` (0xDBEE410..0xDBEE6A2) 消费 JNAOCJBLPPC 的retcode+0x18、is_modify+0x1C和cur_avatar_path+0x28；类型槽0x9361A88。对应WriteTo213181 (0x1E765AB0..0x1E765C8D) 写出tag80/48/88，即字段10/6/11，set_time+0x20写tag104字段13。与冻结定义相符；没有执行/修改游戏客户端。set_time与改名限制尚未实现。旧基础查询的空PlayerSettingInfo.unk1/unk2没有4.6意义映射，原生查询不发送它们；cur_day=1和week_cocoon_finished_count=0仍为继承值，不能当作完整实际统计或设置功能。

`scripts/smoke-profile-creation.py` 使用真实已有正式角色及命途资源，只有任务19996926为隔离合成CreateCharacter目标。此次 `build/smoke/profile-creation-1t8tc0mb` 五项通过：默认角色不完成、显式选择/任务完成、同选幂等/冲突拒绝、重连及H2进程重启后中文名/性别2/实际命途8002和目标保存。原始MainMission资源哈希未变；等待H2延迟落盘，不证明写入中断恢复。不是官方完整开局、剧情演出或客户端UI验收；clientGameplayVerified=false。


## 2026-10-02 俱乐部券战斗碎片收益

固定数据提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle 12关联展示10与效果12，RogueMiracleEffect唯一参数0.75，TextMapCHS对应说明战斗胜利碎片收益增加75%。在已有6项效果子集上新增12，当前为4/6/12/13/15/17/20七项；其他奇物仍未开放。只读Git对象核对中文文本，没有运行参考二进制或引入TextMap运行依赖。

获得时保存参数，实际战斗准备时按该快照计算基础碎片加成，结算继续沿用已有一次性RogueCombatGrant。基础每个实际联战NPC的20至40碎片继承项目规则；加成后的合计整数向下取整采用项目规则，未声称官方舍入验收。老板零收益、战败、退出、失配战斗与重复回调不能制造额外碎片。旧无参数/非法寿命/未知额外参数的拥有记录不借用当前资源。

8条新增Java回归中6条先失败，验证实际Battle创建/胜利回调、联战合计、开战后持有/资源变更、获得后资源删除、旧/坏记录、非法比例/额外参数、败退/零收益以及真实Morphia溢出待发/六路并发/再次重载防重。钱包容量不足保留已确认加成金额，腾出容量后仅补发一次，不因资源更新改价。不代表跨文档写入中断或客户端战斗重演验收；俱乐部券未通过正向合成KCP获得/购买、实际游戏UI或完整宇宙事件验收。


## 2026-10-02 混沌云芝免费祝福重置

固定数据提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect 2与TextMapCHS明确战后选择祝福增加一次免费重置，单一参数1。获得时冻结计数，新战后菜单把免费次数加入原有付费计划之前；无付费天赋仍有这一免费机会，原有付费单价及其次数不被替换。当前支持2/4/6/12/13/15/17/20八项服务端效果子集。免费先用、最多20的参数上限及有限不重复候选池采用项目规则，未声称官方次数叠加顺序或掉落池验收。

生成后按保存费用/已用次数处理，后来获得奇物不会改写当前菜单；进入下一已清房间时生成新的免费计划。开局天赋额外菜单不加入这一战后效果。未知额外参数、旧无获得快照、非法计数/寿命记录不制造免费机会；无替代候选时不能消费机会，重复查询不补次数。

8条新增原生Java回归含7条先失败，核对5676响应、菜单零费用/总次数/已用次数、无付费天赋、免费与付费并存、资源更改/移除、下个房间、旧/坏记录、单一免费次数六路并发、无替代池及真实Morphia两次重载。冻结菜单不会补发已用免费次数，付费扣除沿用保存价格。原生空请求仍不能辨别跨菜单延迟点击；并存多个可用机会时不能把并发请求认作同一点击。正向合成KCP取得/重置、游戏UI、完整事件与全部奇物未验收/完成。


## 2026-10-02 奇物商店实际连接与正常重启

`scripts/smoke-rogue-shop.py --miracle 2`与`--miracle 12`分别通过5项合成KCP检查，临时目录为build/smoke/rogue-miracle-2-2zl_viw3与rogue-miracle-12-i2g77emc；普通祝福模式5项回归仍通过（rogue-shop-u23ec5fu）。使用真实入口8020101、平面80201/楼层80201001、组35的NPC1013/实例400001及Act0110003.json中的MiracleShop页面；没有新增伪造NPC或改写源场景组。请求实际命令5675/5656、响应字段、奇物拥有/不可购和余额通知通过，2/12总次数与已用次数均0。每个账号从300扣至100，重连/H2正常进程重启后报价、库存、持有与余额保留，重复购买拒绝。

只在隔离目录复制并修改ConstValueRogue、RogueRoom首房映射及RogueMiracle目录：初始300、首房1000012指向真实入口8020101并加载组1/35、目录仅保留实际2/12行。原三个资源SHA未变，其他资源只读连接；临时配置/数据库与运营数据分离。200碎片单价与均匀最多3件库存为项目规则，这两次只有2件。实际源场景属于扩展宇宙，此检查不证明经典/扩展宇宙官方房间生成、官方库存/定价、完整对话触发或实际游戏UI。

网络验证证明两件奇物可获得且保存；俱乐部券战斗收益与混沌云芝免费重置仍由Java实际Battle/原生菜单及Morphia回归验证，未连成取得后战斗/重置的正向KCP全流程。进程重启等待H2正常落盘，不代表写入中断自动恢复。完整事件、其他奇物、休息房出口、扩展模式及UI仍待完成/验收。


## 2026-10-02 跃迁复眼祝福等级快照

新增奇物3跃迁复眼：固定4.6文本与参数1对应战后Common祝福一级升二级；全部候选池等级随菜单保存，原生5676展示/选择、付费刷新、战斗绑定、旧菜单兼容、并发及实际Morphia重载有10条回归，9条先复现失败。二级RogueBuff/MazeBuff缺失不生成不可构造选项，损坏等级计划不扣费/发放；已生成菜单不因后来获得/资源变更改级，初始额外菜单不强化。Common与一星映射、菜单快照为项目采用的资源语义；取得/战后选择/刷新的正向KCP及游戏UI仍待验收。

来源为TurnBasedGameData OS4.6固定提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect编号3及TextMapCHS；RogueBuff 600000的Common一级/二级行与MazeBuff对应行可交叉核对。没有修改冻结协议、命令号或版本声明；初始菜单和无参数旧奇物不借用当前资源自动强化。损坏计划保留待选记录以供核对，不丢弃或自动授予。


## 2026-10-02 福灵胶有限效果与菜单保存

新增奇物7福灵胶：4.6固定文本/参数1对应下次战后菜单仅Legendary候选，生成非空菜单时消耗一次并损毁；整池/等级/次数随当前局保存，付费或混沌云芝免费刷新不再消耗且继续使用原三星池。12条原生Java回归中10条先失败，覆盖晚取得、初始/败退不触发、旧/坏寿命、资源移除、缺少三星、并发、Morphia重载和损毁通知在保存后发送；三星品质与强化等级分开，与跃迁复眼/免费重置并存。无候选保留奇物不触发；触发时机、Legendary/三星映射采用项目资源规则，战后菜单效果KCP及游戏UI待验收。

来源为TurnBasedGameData OS4.6固定提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect编号7及TextMapCHS；只接受匹配关联与唯一参数1，寿命/使用次数必须与获得快照匹配。有限奇物在商店取得时应为total_count=1/use_count=0；原生字段沿用已有核对结构，不修改协议快照。损毁更新先保存同一Player文档，再发送已用次数；空池不回退普通品质。


## 2026-10-02 降维骰子双选与半完成菜单

新增奇物1降维骰子：固定向量[1,1,2]生成两项候选、连续选择两次，非空且至少两候选时触发一次，第二个战后菜单触发后损毁。第一次选择保留剩余候选/等级/刷新计划并更换批次，第二次完成才清待选；先保存第一项再通知下一菜单。12条原生Java回归含初始9条先失败，覆盖两次选择、旧批次并发、半完成Morphia重载、免费/付费次数共用、晚取得、旧/坏寿命、计数损坏、批次溢出、资源/拥有变化及仅一候选不触发。原生总/当前次数字段6/9由本机消费和编码交叉核对；连续菜单、序号1起算、预算共用和无足够候选不触发为项目规则。取得及战后双选KCP、真实UI仍待验收，空重置请求无法辨认跨菜单延迟。

资源为TurnBasedGameData OS4.6固定提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect编号1及TextMapCHS。客户端只读SHA沿用前述固定值；RogueBuffSelectAction.get_SelectBuffSourceCurCount（DEE93F0）读取内部对象+2C，get_SelectBuffSourceTotalCount（DEE9440）读取+28，普通构造DEE9670调用复制15EFF190，分别来自输入+38/+30。GGGBAIAPDLO.WriteTo（1E44C5F0）将+30写tag30即字段6 NKGGAOJLCMD，将+38写tag48即字段9 EIALMEADLGF。仅双选菜单填总2/当前1或2，未修改冻结协议/命令号。官方来源提示类型、强制选项和实际UI展示尚未确认；不依据过时offset注释改协议。第一项、余项、候选池/等级、批次和序号同Player文档保存；终局取消清掉剩余菜单。


## 2026-10-02 测不准匣立即祝福与交易保存

新增奇物5测不准匣：固定向量[1,2]在取得时立即抽取当前未持有、已解锁、RogueVersion=1且有MazeBuff一级资源的Common/Rare/Legendary祝福，不重复或降级已有祝福。奖励编号/等级、奇物、收藏和购买扣费同Player保存后通知，空池不出现新可购项，旧报价保留并关闭可购；并发/重复/旧拥有无奖励历史不再抽。10条原生Java回归中9条先失败，覆盖5656购买与5676选择、仅一候选、空池/无效/已有/锁定、通知顺序及实际Morphia两次重载。数量均匀1～2、按可用数量截断及选池均匀为项目规则；取得/立即奖励KCP与游戏UI待验收。

来源为TurnBasedGameData OS4.6固定提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect编号5及TextMapCHS。效果/资源关联必须匹配，仅解释已知1/2向量；不发客户端指定的奖励、不使用旧无历史记录自动补发，不放宽奇物选择或商人身份/非法原生字段检查。永久奇物total_count/use_count均0；奖励为已有核对的祝福更新字段与实际持有列表。完整官方权重、事件取得、游戏UI仍待验收。


## 经典休整Duck入口资源证据

固定TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b的Config/Level/Maze/MazeRogue/Rogue140/Maze_Rogue140_Portal.json（SHA256 7c90de123cf657bf3672dd52ad3452da9a09ed063ab4a63f4bf98adf991858d9）声明Open状态的UI触发、TriggerRogueEnterNextRoom和RogueDoorSetGotoInfo。实际1000012房间主组45/实例300001的Prop1000名为Duck，不能套用Door1/Door2的分支索引。服务器只在类型5/主组/完整路径匹配时保留地图入口，并继续由原生相邻房请求验证选择；不写入任意一个PropRogueInfo目的地。入口可离开语义为项目规则，真实客户端分支呈现与RogueDoorSetGotoInfo执行尚未验收。只读元数据22266/22268只确认配置任务存在，125058/125059/125064/125065为通用执行包装，不能作为地图界面或分支选择消费证明。来源资源未写入运营目录或分发包。


## 2026-10-02 七种火漆的立即奖励与冻结命途偏好

固定TurnBasedGameData提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle/Effect 22至28以唯一参数[0]说明立即取得对应命途的一项祝福，战后提高该命途概率；RogueBuffType与TextMapCHS交叉核对映射为22存护120、23欢愉126、24巡猎124、25毁灭125、26记忆121、27虚无122、28丰饶123。效果关联/参数必须匹配，缺对应的解锁且可构造一级候选不能报价或扣费。取得祝福、奇物、收藏、购买与余额同Player保存后通知，不重复取得，也不借当前资源为旧拥有记录补发。

原资源没有提供提高概率的数值。这里使用明确项目规则：对应命途权重2、其他1；按权重无放回抽样，不代表官方倍率/权重验收。只有新战后菜单采用已有合法火漆效果；候选池、等级和全池权重与预算同文档保存，重置沿用快照，后获奇物、删除资源不改旧计划。旧无权重计划保持均匀，缺项或非1/2权重拒绝选择与扣费并保留菜单，双选移除已选条目的权重，完成/关闭清空。现为19项服务端效果子集，其他奇物及事件仍待完成。

12项新增Java回归含初始8项中的6项失败，保存build/firewax740-red.xml。覆盖七命途立即祝福、5656购买/5676选择、防重/并发/空池/错关联/旧拥有与终局、通知先保存、固定500组种子偏好比较、损坏快照拒绝及真实Morphia两次重载。当前仅Java与静态资源证据，实际火漆KCP及游戏UI另行验收；这些检查不代表完整剧情/活动或跨文档写入中断。

## 经典折纸小鸟原生流程与保存边界

已接入版本1/活动50014的7411查询、7420结算和7432位置；按关卡＋模式保存，普通模式完成解锁鸟，鸟窝0移除/1至资源上限保存。首次有效报告冻结奖励/计数/统计，首奖每模式一次，UUID防重/冲突拒绝；CLAIMING保留不自动重发，已GRANTED阶段经真实Morphia加载恢复。客户端数值仅作已验证报告，未提供棋盘重演或服务器开局凭证；5/10统计来自本机_CreateStatReportData，不制造成就。棋盘/AI/PvP、鸟窝剧情参数、完整成就、V2/V3及游戏UI待完成。

参考hkrpg-go ef47e49a233133c46d70ab5a4deb4bc71ae49571（AGPL-3.0）player_activity_match_three.go/db_activity_match_three.go，仅参考流程，未导入外部代码；查询保留实际位置和已有鸟记录。资源为TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b。只读D99C850→7411空类型PBDKDFCOAPB，D99C660→7420鸟2/数值4/统计6/模式8/关卡14/uuid15；D99C7D0→7432，POGMMMGBNCH.WriteTo标签0x20/0x50确认鸟4/位置10。MLHLIMIJHDD.WriteTo确认count8/pos9/数值13/鸟14，ALNGMGOJDOA.cctor确认鸟6、完成列表7、统计map2/9。证据build/client46-match-three-*.json，DLL/元数据SHA与前述分析一致；不启动/修改客户端，静态匹配不等于UI验收。


## 公司咕咕钟费用与保存（2026-10-03）

固定TurnBasedGameData提交724b139d8c9c32d12552eb95745a4fee72bfe48b的RogueMiracle 70关联展示51/效果70，唯一参数0.25；中文说明将强化和重置祝福费用提高25%。9关联展示55，名称为破碎咕咕钟，参数[499,999]。本次只实现70的费用与累计额外消耗记录；9的获取/替换关系及累计边界还没有可靠证据，不直接开放购买或发999碎片。当前20项服务端效果子集，全部奇物及完整剧情/活动仍未完成。

明确项目规则：先沿用裂界对象13的折扣，再向下取整计算70的额外25%；例如普通祝福100变125，稀有130变162，已有30%折扣的100变87。免费机会保持0。报价将总价、额外费用和奇物取得标识冻结，旧报价不追溯涨价；资源更改/移除不重算。实际扣款后只给产生该报价且仍有效的取得记录增加额外消耗；商店/复活等普通碎片消耗不计入。同一Player文档保存钱包、祝福或菜单和额外计数后通知；重复/失败不增加，六路并发只扣一次，损坏计数或溢出拒绝交易。未知旧记录不借当前表补效果。

UniverseCompanyClock46Test新增13项回归；最初5项全部失败，后补错展示关联与损坏计数各一项先失败，共7项有效失败回归。中途重置测试误叠加两份合成赛季，已修正测试隔离，该失败不算功能缺失。记录build/company-clock771-red.xml、company-clock777-guards-red.xml、company-clock779-counter-red.xml。真实Morphia两次重载保存价格、取得标识、余额175和额外计数25。scripts/build.ps1完成779项测试及Shadow jar，失败/错误/跳过均零。

scripts/smoke-rogue-shop.py --miracle 70通过5项真实UDP/KCP检查，build/company-clock779-merchant.json记录真实资源NPC1013/35/400001、入口8020101及商人页面。隔离奇物目录[2,70,12]、首房与初始300碎片为测试设置；200定价/库存是项目规则。实际扣至100，通知、重复拒绝、重连与正常H2重启后价格/库存/持有/余额保持；来源三表SHA不变。这不证明取得后强化/重置的KCP流程、计数网络展示、写入中断恢复、官方生成/费用叠加或游戏客户端UI。Dispatch两次冒烟也通过，报告build/company-clock779-dispatch.json。


## 卜筮咕咕钟选项数量（2026-10-03）

固定数据RogueMiracle 65关联展示46/效果65，唯一参数[1]；TextMapCHS说明战斗胜利后的祝福选项减少一个。服务端新增该永久奇物的严格取得校验，并在准备战后菜单时把普通三个选项减为两个，不改变开局赠送菜单。显示选项数与两次选取次数分开保存；新菜单冻结选项上限，删除拥有/资源、后来取得奇物均不改变旧菜单。重置沿用原上限；降维骰子仍依次选择两个不同祝福，重置不补充其使用次数。候选不足时只提供可构造的实际候选，单候选仍可选，坏上限拒绝选择/重置并保留菜单与钱包。旧无上限字段保持既有普通三选/双选行为，不借当前奇物重写。

UniverseDivinationClock46Test新增10项，其中最初4项有3项先失败，证据build/divination-clock783-red.xml。覆盖原生5676菜单/重置/实际选取、冻结上限、错误参数/展示/拥有次数、单候选、双选组合、六路并发、损坏记录及真实Morphia两次重载；全量scripts/build.ps1通过789项、失败/错误/跳过均零并打包。当前21项服务端效果子集；完整宇宙、剧情、活动和国服游戏UI仍未完成/验收。

scripts/smoke-rogue-shop.py --miracle 65通过5项UDP/KCP，报告build/divination-clock789-merchant.json，目录build/smoke/rogue-miracle-65-i7g551_i。真实NPC1013/组35/实例400001与入口8020101；隔离奇物目录[2,65,12]、首房及300碎片测试设置未修改来源资源。200定价/均匀库存为项目规则；实际余额100，通知、重复购买拒绝、重连及正常H2重启后库存/报价/持有/钱包保持，来源三表SHA不变。该网络检查只证明取得及保存；减少选项、与双选组合等由Java/Morphia验证，未连成取得后战斗的KCP全流程或实际客户端演出。Dispatch两次通过build/divination-clock789-dispatch.json；正常重启不证明写入中断恢复。

上一公司咕咕钟779快照的完整网络47项通过build/company-clock779-network.json，目录build/smoke/full-46-j0_tzgs0；独立源码包build/source-export-3938b82e包含1606文件，独立779项回归、8项Python检查、151份冻结协议SHA均通过，jarSHA为68f73d9c8ed6ce035efd58ecdd480f96b61a1555930e99ac4bb39fd87e93632c。这些是各自快照的验证，后续变更须重新核对。


## 信标着色剂候选强化（2026-10-03）

固定资源RogueMiracle 69关联展示50/效果69，参数[1]，中文说明战斗胜利后随机强化其中一个祝福。本次严格校验关联/参数，菜单从当前显示、且基础一级并有真实二级RogueBuff及MazeBuff资源的候选中选一个变二级；不把未显示候选发为二级，不制造三级。基础等级、可强化池与效果随菜单冻结；重置重选新的显示目标，后来取得或删除奇物/定义不改变旧计划。原有跃迁复眼已为二级的候选保持二级；卜筮钟的两选项与降维骰子的两次选取分别保存，选中的等级与收藏实际保存。缺二级资源不能选择已冻结的二级奖励，保留菜单/钱包，不降级代发；无可构造二级候选时不虚造强化。损坏计划中额外/未显示强化、缺基础字段均拒绝选择/扣费。

UniverseBeaconColoring46Test新增13项，最初4项全部先失败，build/beacon-coloring793-red.xml记录。核对原生5676的候选/实际等级选择、付费重置、旧菜单、删除当前定义、缺资源、复眼/卜筮钟/双选组合、坏快照、六路并发与真实Morphia两次重载。scripts/build.ps1通过802项，失败/错误/跳过均零，并打包；当前22项服务端奇物效果子集，完整剧情/活动/其他奇物继续待完成。

scripts/smoke-rogue-shop.py --miracle 69通过5项真实UDP/KCP检查，build/beacon-coloring802-merchant.json记录真实商人NPC1013/35/400001及8020101入口。隔离首房、300碎片和目录[2,69,12]；200定价与均匀库存是项目规则。实际余额100，取得通知、重复拒绝、重连及正常H2进程重启后的报价/库存/持有/钱包保持，来源三表SHA未变。该检查证明取得与保存；候选强化、重置和多奇物组合由Java/Morphia覆盖，还未连成取得后战斗的KCP流程、官方随机分布或实际游戏UI。Dispatch两次通过build/beacon-coloring802-dispatch.json；正常H2重启不代表写入中断恢复。

上一789快照的完整网络47项通过build/divination-clock789-network.json，目录build/smoke/full-46-uf8t34o4；独立源码包build/source-export-4c93d68b的1607文件、789项回归、8项Python与151份冻结协议SHA通过，jarSHA为127c8dfa4e58aaed54a0504b9356bc2b9107a8cceba51fea3e534fd1694a3f42。每份记录只适用于对应快照。


## 骰子角色真实升级与特殊骰替换（1355项快照）

骰子角色升级与特殊骰替换已接入原生9292/9267、9286/9264。按来源Rare及本机DesignData恢复的真实数值映射，1升2扣264998×60，仅三星可再扣180升3，一/二星上限2；不会改骰组数量、取得时间或冻结供奉取得记录。扣费前保存独立收据，库存或完成保存中断保留待核对状态，重试/其他交易不再扣费；完成保存失败不发布等级。换骰以150093实际持有解锁，只允许所持Colorful骰或该角色来源默认骰，替换第四稀有度全部副本；不消耗权限物品/骰子，重复同选不保存，失败保存恢复内存旧骰组。17项升级和15项换骰回归，最初11+10项全部先失败；真实Morphia两次恢复、库存身份/不足/上限/窗口/战斗/并发/坏字段与坏收据已有检查。1355项全量通过；新增22项合成UDP/KCP、同UID四份快照验证供奉角色264021按60+180实际扣费、3级、库存1000→760、特殊骰264403/默认264518互换、普通重连及两次正常H2重启（末次关闭活动）保持。14份来源资源SHA不变，原生稀有度列表[264101,264201,264301,264403]。费用映射只对固定常量表SHA开放，未使用丢失重复键JSON的最后180值作统一价格；运行时不依赖游戏安装目录或参考项目二进制。PVE/PVP/V2、滚骰/技能演算、其他活动、全部剧情/宇宙及真实游戏UI仍待补齐；正常重启不证明断电原子性。

费用依据为本机SHA锁定的DesignV条目65b358d2cf06e668（7804字节，SHA256 af7e32d969cf8e78aae526af528aa458b573d7b8d999590d1295f4aff6fb5bad），完整消费141条DynamicValue常量；LevelRarityDustMap为{1:{1:60,2:60,3:60},2:{3:180}}，MaxLevelMap为{1:2,2:2,3:3}。源JSON具有重复IntValue键，不能从解析后的最后值推断全部等级成本。资源来源提交724b139d8c9c32d12552eb95745a4fee72bfe48b，常量表SHA256 cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95；不符则拒绝升级/换骰，避免错用恢复常量。测试原样常量夹具的来源另记在src/test/resources/gameplay/activity-dice-const-4.6-source.md。

只读本机GetUpgradeAvatarDustCost(0xcc361a0)与Lua升级页的Rare/Level参数次序独立核对该层次。修改发送0xcc36950把avatar第一个参数写+.1c、die第二个参数写+.18；PENHEEONFHJ writer 0x1def13b0对应tag15与tag8，响应NALJOPFBJDK tag13/avatar与7/retcode、LCACCGHOGDB tag10/avatar与11/retcode、嵌套角色tag1/2/3/4均保持现有冻结协议。升级响应消费0xccb2b40只更新等级，换骰消费0xccb2970只替换DiceIDPerRare；不把来源DiceCombatDiceUpgradeSeq误当作升级时服务器应发送的新骰组。默认骰恢复使用同一换骰入口；恢复时无需单独持有默认骰物品。

网络夹具沿用供奉12真实25级与全部奖励，角色和150093来自真实奖励，骰264403来自来源商品1201；仅隔离活动/音律门槛并将音律101首次奖励改为资金264996×10、积分264999×2050、尘晶264998×1000。保护14份来源SHA不修改运营资源。报告build/dice-progression-native1355.json及所指目录保留请求、响应、四份同UID快照和H2；没有运行实际客户端，没有验收官方剧情门槛、滚骰战斗/PVP/V2或断电中断自动对账。未付款收据需人工核对真实库存/玩家两份文档；不能自动补扣或把保留状态当成完成。32条新增Java回归中21条先复现入口缺失，余11条覆盖进一步中断/文档恢复与装备保护；不将回归数量当作完整玩法数量。


## DiceCombatFinishPveStage 9293/9271任务授权子集

本机同一SHA锁定的4.6 PE/IL2CPP只读静态核对：NetworkManager.LANGMNNABDL在D991850向ELOCPGOBJFM填入tag10 stage、tag1胜利、tag2布尔和tag4 uint32并发送9293；写出函数1E2AA0F0依次使用wire8/10/20/50。响应DFLIOADBEDK写出1E178D90确认tag5 retcode、tag8 ItemList、tag9胜利和tag14 stage；消费者CCB3FA0仅在retcode=0时处理奖励，胜利时将stage加入模块FinishedStages，系统数据使用OHFIIBEIPJE字段3返回实际首胜集合。对应冻结定义与命令号匹配，未改生成类或描述符。

CCB9070的原生调用者B54FF20传入胜负、从本地字典取得的布尔和整数；目前不能为该布尔或整数赋予可靠玩法含义。只读参数表恢复也显示UInt32/Boolean/Boolean/UInt32，名称已剥离为空。Java因此不把tag4解释为角色、回合或伤害，保存原始UInt32；tag2=true继续拒绝，不推测退出语义。StageInfo的CC2FD30读取预设列表用于跳过判断，不能把该列表当成解锁前置。

有界函数与字段输出见build/client46-dice-pve-fields-and-report1378.json、build/client46-dice-pve-stage-info1378.json和build/client46-dice-pve-parameter-names1378.json。1400项快照的原生合成KCP验证只覆盖允许的任务首胜流程；尚无官方客户端实际战斗/调用链动态验收，全部自由PVE、PVP、V2和未识别字段继续待实现或待核对。


## BattleStatistics自定义值读取边界（1424项快照）

固定Honey fe00bdb协议定义CmdBattleType.proto明确BattleStatistics.custom_values为tag13、map<string,float>；原生map entry使用key tag1字符串和value tag2 fixed32 float。继承4.2生成结构也为同名float，不更改描述符或生成类。新增字节与桥接回归验证默认0、1、分数和负数的类型保留，拒绝重复键/子字段、错误线类型及非有限值；两条条件从实际事件表映射而非字符串前缀。参考Honey/Hyacine同名处理器提供CustomValues与ParamStr1匹配线索，源码不直接导入Java。

13项合成UDP/KCP及两个UID各三份恢复快照验证原生统计到剧情分支；这不是本机IL2CPP动态验收、正式副本流程或战斗输入校验。源任务IsInRaid门槛和导航只在隔离测试复制中放开，具体记录见build/battle-custom1424-native.json与测试来源说明。


## 正式角色、头像同步和副本边界（1479项快照）

固定Honey fe00bdb CmdPlayerSyncType定义PlayerSyncScNotify.update_player_board_module_list=781，其PlayerBoardModuleSync使用unlocked_head_icon_list=6、signature=13、head_frame_info=14；Common中HeadIconData.id=11与HeadFrameInfo.head_frame_item_id=1。PlayerBoardSync.cs使用同名容器和签名/框；内部BoardDataSync的具名字段仅按这些语义转换，未知框解锁列表和时效不推断。两项源失败回归验证实际同步不能断开角色获取；永久框字段保留身份，未证明完整头像框产品功能。

固定Common的AvatarType_AvatarFormalType=3、AvatarType_AvatarTrialType=1，继承内部正式值为1。新增和修正的副本/普通及宇宙编队用具名枚举，杜绝旧数值直接透传。704请求slots字段7、719响应索引7/retcode9沿用既有本机CN替换证据；原始proto枚举标签和别名未改。真实40235副本的合成网络保留IsInRaid及原场景，父任务/正式角色获取隔离；不涉及IL2CPP动态执行或真实游戏UI验收。
