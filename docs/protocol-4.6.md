# 4.6 正式版设计与验收边界

设计目标是本机已确认的国服 **4.6.0 / hkrpg_cn**。Java 21 是唯一服务运行核心。既有 LunarCore 4.2.0 消息作为内部 DTO 保留；4.6 线协议通过独立描述符转换，未修改生成类或仅调整版本常量。

## 版本交付单元

| 对象 | 当前固定证据 | 边界 |
|---|---|---|
| 客户端 | 用户给定目录的 config.ini 声明 4.6.0、hkrpg_cn、channel 1 | 只读检查；未启动或改动客户端 |
| 协议 | March7thHoney `fe00bdbda3c28ca33d01f171566aa35b5ab95083` | 144 份定义、2,380 个命名命令，保留别名来源 |
| Java 内部消息 | LunarCore lite `04b5de871c62895091c4d65243b2e03997e66f23` | 4.2.0 Quickbuf 不手改 |
| 数据 | TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b` | OSPRODWin4.6.0_D16707949_A16704710_L16700845；国服差异未验收 |
| 运行配置 | protocol.wireVersion=4.6.0，resourceDir=./resources/4.6 | 显式实验配置；旧配置保留 4.2.0 |

Pearl 的 4.6.51/4.7 测试版使用另一套命令号与字段；不能合并为正式版协议。HTTP 版本检查拒绝不同数字版本及显式 BETA/DEV/PRE 标记，即便其数字写成 4.6.0；游戏会话按所选协议解释，不按旧命令号猜测。

## 已实现的协议边界

`ProtocolSchema` 加载打包的 FileDescriptorSet；`ProtocolBridge` 重新编码语义字段、嵌套消息、重复字段、整数编码和已匹配枚举。保留帧头；缓存包和原始帧发送不会绕过转换。只有同名非混淆字段和记录过的明确别名可映射；已填充的未知响应字段会拒绝，核心请求字段无映射时不会默认为零执行动作。

旧核心无生成类、4.6 定义确认无字段的请求可转为核心空请求，任务查询 934 已有回归；出现未知字段仍拒绝。两个消费空请求的旧登录处理器及返回全量快照的角色查询处理器另行明确登记。角色查询不实施请求过滤，响应 is_get_all=true；未将其他有字段的请求一概当作空消息。

示例：4.6 的 PlayerGetToken 请求命令为 60，账号字段 6、令牌字段 3；响应命令 16，retcode 字段 3。心跳使用 44/62；PlayerLogin 使用 34/25。测试包含直接写出的固定字节，而非仅由同一个描述符编解码后自证。

网关以 4.6 GateServer 定义构造，UDP 端口字段 13、地址字段 14，use_tcp 保持关闭。Dispatch 和国服 SDK 的登录、验令牌与 combo 交换在本地完整服务中验证。新 SDK 固定账号、密码未验证及其完整配置接口仍是已知缺口。

本机回环 KCP 探针通过真实 UDP/KCP 连接发送心跳 44，接收响应 62 并核对时间戳。新增会话探针沿 SDK combo 令牌链创建玩家，再发送登录、登录完成、角色、背包、编队、场景、内容包和 Quest 查询。中文昵称经 SetNickname 修改后在重连与同一 H2 数据库的进程重启中恢复。凭据只通过标准输入交给探针，不放入命令参数或日志。它是合成探针，不等同于游戏客户端登录或实际场景可玩。

4.6 登录序列发送 PlayerLoginScRsp 25 和 ContentPackageSyncDataScNotify 7520；登录完成只应答自身请求，不推送旧固定全解锁图鉴或固定满级付费战令。内容包查询 7534/7506 独立接入新协议，按配置报告 Init，cur_content_id 保持未选择；真实剧情条件、月卡、活跃及内容包状态机尚未实现，初始状态不是完成状态。

角色路径映射 AvatarPathInfo → AvatarPathData，保留技能点、装备槽、皮肤、强化、晋阶奖励与首次获得时间；光锥/遗器的 equip_avatar_id → dress_avatar_id。玩家外观 PlayerOutfitInfo → CJLCPMDGIBO 及其混淆字段有同一固定源码中的明确构造证据。AvatarPathInfo.first_met_timestamp 等无充分语义证据的字段仍不映射。

4.6 `Avatar.equipment_unique_id`（字段 7）还需要独立填充：`AvatarEquipment46` 从同一完整快照或角色增量中匹配当前命途的 `AvatarPathData.path_equipment_id`（字段 2），不借用其他命途或临时查询在线对象。完整原因、命途通知和验证边界见[装备显示说明](avatar-equipment-display-4.6.md)。

场景的组属性和楼层属性按语义重新编码，负数保留。旧 group_state_list 合并到 entity_group_list.state，保留没有实体的状态组，冲突状态拒绝；旧 is_default 标记没有单独对应字段。SceneIdentifierInfo → SceneIdentifier 保留已确认的 floor_id；未猜测 game_id 的新含义。源路径与固定提交见 [NOTICE](../NOTICE.md)，生成类和原始协议定义未改动。

4.6 的 FinishQuest 完成目标与 TakeQuestReward 领取奖励分开。领取命令 905 的原始类型 EMHALMBFHKG、字段 2 的目标列表，以及完成响应 942 的 BICDPPOGHND、字段 11 的 retcode，均有同一参考提交的显式来源，不根据旧字段号推断。

4.6 战斗动作采用原生请求处理，cast_entity_id 是施法回包标识，不能直接当作旧 attacked_group_id 的请求语义；原生处理器明确传递回包值。事件关卡的 event_id、波次/阶段、buff 所有者及强化字段有固定来源记录。PVE 的旧 is_ai_consider_ultra_skill 字段没有映射且处理器不读取，单独允许缺失，不给混淆字段虚构含义。原生任务、活跃和登录活动边界见 [玩法说明](gameplay-4.6.md)。

## 结构审计与剩余工作

当前从 Java 生成源码提取的 510 个消息类型中，200 个通过递归结构匹配，310 个有字段、嵌套类型、枚举或消息缺口。**结构匹配数量不是已完成玩法数量，也不证明客户端接受。** 审计保持结构口径，仍将经运行时迁移的 group_state_list 计为直接字段映射缺口。具体记录可用以下命令重新生成：

```powershell
java -cp build/libs/hkrpg-server.jar org.hkrpg.server.protocol.ProtocolAudit build/protocol-audit.json
python scripts/verify-protocols.py
python scripts/audit-resources.py
python scripts/smoke-4.6.py
```

资源清单核验 69,957 个 JSON、698 个 RuntimeFloor；包括剧情、宇宙对话和活动/小游戏配置，仍缺少 169 个未删除地图组引用。Java 加载日志另记录部分秘技缺口。文件存在、资源预检和健康就绪均不能代表全地图已完成。

本轮已接入部分普攻/秘技、战斗结算、剧情/日常、实验宇宙状态和登录活动。后续继续补齐角色成长/配装、编队变更、复杂场景/能力动作、全部剧情脚本、宇宙 UI 及活动小游戏，范围见 [gameplay-4.6.md](gameplay-4.6.md)。每项遵循 [459 项清单](feature-backlog.md) 的来源记录，增加正常/失败/重连/重启/领奖测试和对应客户端验收。

当前尚未配置客户端接入本地服务，未做客户端 KCP 登录、进入场景或战斗。协议加密、资源热更新、国服 SDK 全流程也需要客户端联合验收。

新增原生 GetMainMissionCustomValue、固定物品提交、手机消息图/回复/剧情批量完成和 MusicRhythm 查询/开始/结束/编曲保存，按冻结描述符与已核对的本机语义构造。音律 show_hint=4 实际是全局高难度标志，unlock_song_list=9 实际是音效开放的歌曲列表；名称本身不能证明业务含义。楼层保存值及场景组属性/状态通知按固定来源字段构造，不修改生成类。只读解析本机客户端后，`Universe46` 显式处理原导入表未识别的混淆命令 5676，使用已固定的 OFEDNNJIJFL 描述符接收祝福及奇物选择；原始命令表保持不变。区域/季期、真实地图/房间查询、房间切换/地图同步和持有增量已接入，基础开始/重入/返回/关闭与独立队伍通过实际 KCP 和 H2 重启验证。解锁图、6 项奇物效果子集、手机消息和音律编曲/门槛/通知另有 Java 与文档恢复回归，证据见 [客户端静态证据](client-protocol-evidence.md)。命途/特殊角色、剩余解锁/奇物效果、事件/商店获取、完整消息脚本、音律官方开放时机及其他小游戏仍待完成；固定快照缺 2 个剧情脚本引用。未进行游戏 UI 验收。

原生场景交互区分配置字段 5 和 UInt64 上下文字段 8；基础地图/锚点查询及 EnterScene 采用独立处理器，校验真实解锁/前置和错误回包。宇宙场景快照补充当前保存的出口目标并随清房/待选状态更新开闭，旧楼层消息不借用当前实体 ID。仍不支持完整机关费用、故事线/内容变体、完整探索地图、终点退出和游戏 UI，详见玩法说明及静态证据。

原生天赋查询 1802/1806、激活 1890/1854 已接入独立 `RogueTalent46`；列表、锁定/开放/激活状态和资源前置进度由持久化玩家状态构造。查询货币 32/33/34 使用 MBCBDJNKFEB 字段 7/8/14，更新通知 1801 内 GAOBHBLFPEC 使用字段 2/7/14，不能沿用偏移注释猜测。终局房间积分与已到账点数分别使用已核对的字段 13/7 与字段 6。`CycleScore46` 接入通用周期查询 4196/4147、领奖 4170/4126、容量超限明确丢弃 4180/4167 和更新 4110；周期快照为 start_time=4、end_time=11、已领取列表=12、实际积分=14。领奖回包物品=3、快照=8、容量超限档位=9；普通成功领取不填字段 9。经典宇宙贡献与整笔超限保留为本项目规则，完整官方计分、其他模式、其余天赋效果和客户端 UI 仍待验收。原生 5676 的空重置分支 51、回调 3/11、开关 13、已用次数 14、上限 15 和费用 2 已通过本机消费者与 codec 核对；11/21/32 效果及折扣仅有服务端回归。
原生复活 1811/1817 和费用通知 1803 由 `RogueRevival46` 处理；角色列表字段 10、装置实体字段 13、响应费用字段 3/结果字段 4、通知字段 2 与 KCMFAIBMOHB 费用字段 5 已通过本机消费者、类型槽及 WriteTo 核对。4.2 生成类和冻结协议文件保持原值。当前支持真实装置 1002、倒地角色整批扣费/恢复、冻结费用与文档恢复，其他恢复计数/效果、天赋折扣、正向实际 KCP 和完整游戏 UI 尚未验收。


钟表把戏新增独立HeartDial46：查询6334/6325、情绪6305/6375、演出6358/6356。物理字段经本机调用者、消费者和WriteTo核对：情绪请求script1/NPC2/emotion12，响应retcode1/script7/emotion9；演出请求script3/dialogue4/NPC10，响应reward4/retcode6/dialogue9/script14。GetHeartDialInfo响应retcode1、dialogue_list3、script_list4、trace_list9、unlock14；ScriptInfo脚本8、step3、emotion7，bool1/13业务未证实保持默认；DialogueInfo脚本对话9、performed13，只有本服已应用报告设完成。正常步骤枚举4、情绪Peace0/Anger1/Happy2/Sad3。参考proto偏移注释过时，字段含义以实际写出标签为准；未手改冻结协议或生成类。只接受严格UInt32、唯一正确标签/线型及实际当前NPC，复杂钟表步骤和游戏UI仍待实现/验收。19项Java回归、七项实际资源KCP、重连和正常H2重启已通过，边界见玩法/验证文档。


钟表新增6383解锁及脚本步骤通知：unlock11、changed_script_list12、changed_dialogue_list8、trace7经本机602253/188933/188920核对。新增6360/6316单件情绪物品，script15/item_list13，ItemList项2/Item.id15/num6；响应retcode6/script12。本机634791与168050/168059/188842/188863核对物理字段。当前仅严格Missing→Normal图，待扣记录不自动重扣，正常重启后查询恢复实际步骤/历史对话/库存。对应11项Java和11项实际资源KCP已通过；Lock/Full/Control/Raid、其他条件、跟踪和游戏UI仍待完成，冻结协议和生成类未修改。


钟表增加严格Missing→Full→Normal：使用已冻结6360/6316及6383字段，无生成类/命令变更。Full1为物品已付而指定任务未完成；实际完成后Normal4，历史Full对话保留，保存释放及通知标记后发布一次。11项新增回归（五项先失败）及真实13113物品/对话KCP14项、重连和正常重启通过。任务接受/完成/奖励和替代NPC加载均为明确隔离夹具，官方任务/UI仍待完成；单件、Full/Normal步骤目标子集之外的复杂步骤没有因此获得实现。


地图条件新增只读字段和枚举常量核对：scripts/analyze-client-46.py --fields按本机固定SHA读取选中类型字段、原生类型及Int32字面常量。AtmosphereSet实际LogicOperationType.And=1与构造默认相符，CompareType.Equal=4；两种条件Value实际Int16。未知枚举/常量编码明确保留，不把参考项目中的同名枚举或字段声明顺序代作数值；冻结协议、命令号和生成类未修改。


剧情副本沿用已核对2234/2206、2220/2231和2229字段，增加实际完成目录2248/2214。本机GetRaidInfoScRsp=32390，216339 cctor和216352 WriteTo核对finished_list12/retcode2；cctor在1dfcb527读取槽937a4d0，对应CHKKOLCPBOH=32386，216313 WriteTo以物理world20写标签38/字段7、raid24写标签60/字段12。RaidModule 613788从记录24读实际副本ID，613731以world20/raid24组合键读取副本记录；证据build/client46-mission-raid951-query.json、client46-mission-raid951-finished-slot.json、client46-mission-raid951-finished-codec.json、client46-mission-raid952-finished-consumer.json。GetRaidInfo只返回本服真实完成的Player/Mission子集，无目标列表时保持未识别字段为空。冻结协议和生成类均未改，客户端仍未验收。


977项快照新增钟表Player副本图的原生步骤0/2/3/4；继续使用此前本机验证的6334/6305/6358/6360及6383字段，不修改生成类、命令号或VERSION。19项阶段回归及`smoke-mission-raid.py --clock`的29项KCP检查分别验证两次单件提交、真实库存1上限、特殊阶段历史、指定主任务完成后释放和正常重连/H2重启。父任务/剧情尾部与替代NPC加载为明确隔离夹具；不宣称完整官方剧情或客户端验收。
