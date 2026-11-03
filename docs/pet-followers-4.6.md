# 背包跟随宠物召唤

本轮处理背包中的普通跟随宠物，不包括角色秘技召唤物、盲盒宠物和盲盒编队。功能状态保留为 `partial`，真实游戏画面仍需用户验收。

## 问题与修复

- 继承核心的 `SummonPetCsReq`、`RecallPetCsReq` 和 `CurPetChangedScNotify` 命令号均为未分配的 `-1`。4.6 请求无法到达旧处理器，不能通过只改命令常量接通。
- 4.6 将当前宠物从单个编号改为带类型及 oneof 的状态结构；旧转换器仅处理未选中状态，已选中宠物的查询会被拒绝。旧召唤处理器还会对未拥有的宠物回复成功。
- `Pet46` 接入原生查询 `7625/7617`、召唤 `7616/7608`、召回 `7604/7621`；普通宠物请求要求编号字段 1、类型字段 13 为 1，回复状态使用类型字段 9 和编号 oneof 字段 13。
- `PetSelection` 在玩家锁内校验实际解锁及 `PetConfig`，保存成功后才发布 `7612` 变更通知。保存失败恢复原选择，重复召唤或重复召回仅确认，不重复保存或通知。
- 已选中的宠物通过同一状态结构返回查询，沿用 `Player.petId` 和独立的宠物解锁记录，不新增存档格式，也不创建角色秘技召唤实体。

## 协议来源

协议文件沿用 `March7thHoney-OpenSource@fe00bdbda3c28ca33d01f171566aa35b5ab95083` 的 `Proto/4.6/CmdPetType.proto`，许可证为 AGPL-3.0；未导入其他参考仓库的实现。

固定本机客户端的只读证据见 [client46-pet-evidence.json](client46-pet-evidence.json)：普通发送器写入类型 1 和 uint32 oneof 1，盲盒发送器使用类型 2 和 uint64 oneof 14；`PetModule._SyncSummonState` 读取类型 1 与状态 oneof 13，召唤和召回回调均接到该状态恢复函数。`PetDataChangeScNotify` 的状态变更为列表 6 中的 oneof 11，空状态也必须明确保留该变更。

普通资源包含宠物 `1001..1005`、对应物品 `251001..251005`；使用已有物品获得逻辑和正式解锁记录。不会因为查询、非法请求或召唤而授予宠物。

## 验证

修复前新增的 11 项回归全部失败，失败记录保留在 `build/pet-red.log` 和 `build/pet-red.xml`。专项测试覆盖五种普通宠物、独立固定字节、切换/重复点击/召回、未拥有及未知编号、物品编号误用、类型/oneof 混用、截断/重复/溢出请求、未登录隔离、保存失败回退，以及实际 Morphia 的 Player 和解锁文档重复读取。

运行方式：

```powershell
./scripts/build.ps1 -TestForks 8 -RerunTests
python scripts/smoke-server.py
python scripts/smoke-pet-followers.py
```

完整构建、隔离 KCP/H2 与正常重启的最终结果记录于本轮 `build/pet-followers-verification.json`。自动化通信与存档验证不能代替客户端中宠物模型出现、跟随、切图和战斗隐藏/恢复的画面验收。未启动游戏或使用 computer use。

本次专项共 18 项通过，其中宠物回归 12 项、既有外观查询回归 6 项。独立候选 JAR 的 140 次原生请求包含 18 次宠物请求；正式物品发放、请求头关联、通知空请求头、同账号重连、三次正常停服及两次 H2 进程重启均通过。未拥有、未知类型和重复请求保持预期状态；真实 Morphia 文档读取及保存失败由单元回归补充验证。

共享工作区的首次完整构建同时读入另一批尚未提交的 `WorldDestructible46Test`，出现非宠物失败后终止，原日志保留在 `build/pet-full-build.log`。正式门禁使用 `b18e77954d1d192d731dd902ebdca8b98ba15ed9` 加本轮 14 个文件的独立快照，未排除该基线的任何测试。其他并行改动保留在原工作区，不计入本次提交。

首次源码验证后尚未更新常驻 Compose 服务，后续实际补发见下节。该次独立产物位于 `build/pet-followers-release/hkrpg-server.jar`，摘要与门禁和通信记录一起保存在 `build/pet-followers-verification.json`。

最终独立门禁：682 个测试类、16,697 项测试，失败、错误和跳过均为 0，Java 21 JAR 构建通过。候选 JAR SHA-256 为 8a9c3ef7fd9548cc89ee58c72bd9ebb3522eac0f622398349a48b9d1b2d38172，两轮 Dispatch 冒烟和 140 次原生请求均核对同一产物。

## 2026-11-03 常驻服务补发

用户反馈仍然转圈后，实际容器日志确认召唤命令 `7616` 仍为未处理；容器及宿主发布目录的 JAR 摘要同为 `e906158f13aeacd4ed4451a57c26345518277ca9f07a97c43ed29e65cf158882`，其中没有 `Pet46.class`。此前只完成了源码修复和独立验证，没有将该修复加入正在运行的服务。同一旧 JAR 在独立账号/H2 探针中也复现了召唤等待超时，记录为 `build/pet-live-release-20261103/baseline-red.log`。

本次以实际运行 JAR 为基础，仅替换 `ProtocolBridge`、`VersionedPacketHandler`，加入 `Pet46`、`PetSelection`，并向原功能目录追加宠物条目。替换前两个旧类与 `b18e779` 原始源码独立编译结果逐字节一致；其余既有 ZIP 条目、其他功能目录项和已经发布的地图掉落修复逐项保持一致。实际发布包 SHA-256 为 `83f635ae46b9ff784ce0b5e21549fa726a83bfe243f1aa2968bd78cb229b0374`，对应 `build/pet-live-release-20261103/server.jar`。

将测试运行时的主类路径换为这个合成发布包，宠物、初始外观、普通掉落、金矛和回复物合计 **2,421 项专项全部通过**，失败、错误和跳过均为 0；同一发布包的 **140 次原生请求、18 次宠物操作、三次正常停服和两次同 UID H2 进程重启**再次通过。上节 16,697 项全量结果对应独立源码快照，没有将其冒称为这个合成包的完整测试结果。

本地 Compose 已正常停服、备份并更新到该包，容器中程序摘要一致，健康、CN/OS 派发、网关与只读原生心跳检查通过。镜像为 `sha256:dc3a3edb5ad4e0e3686de3c7bf3326750a48194018c73c25d3ae2d836b4ab4d8`。正常停服后逐文件验证备份，路径为 `.local/backups/pet-followers-20261103-050830-83f635a/`。对备份数据库的隔离副本读取与新进程的存档比较确认，玩家、宠物解锁、角色、物品和其他非区域文档完全一致；区域文档仅有正常启动的 `up=false` 到 `true` 变化。配置和 87,472 份资源的既有清单不变，没有恢复覆盖数据库或代替用户选择宠物。

补发后需要退出游戏重新登录，清除旧会话已经失去回复的等待状态，再召唤宠物。真实客户端模型及跟随画面仍待用户验收；本轮没有启动或操作客户端，没有使用 computer use。发布核验见忽略目录 `build/pet-live-release-20261103/` 的 `composition.json`、`packaged-summary.json`、`native-report.json`、`deployment.json`、`backup-proof.json`、`data-preservation.json` 和 `compose-verification.json`。
