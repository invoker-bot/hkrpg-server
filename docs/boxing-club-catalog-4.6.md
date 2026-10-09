# 4.6 第一季拳击俱乐部目录

`GetBoxingClubInfoCsReq` 4234 / `GetBoxingClubInfoScRsp` 4225 接入第一季原始五关（ChallengeID 1–5）。来源固定为 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的 `BoxingClubChallenge`、`BoxingClubStageGroup`、`BoxingClubStage` 三份完整 Git blob，共152条完整记录。只归一化Windows CRLF换行，保留原始内容，不覆盖资源或运营配置。目录读取不执行战斗，也不推进剧情或领取奖励。

原始 First 关卡各四轮，前三轮可获得增益，末轮没有奖励增益。第二季存在选项列表 `[0]` 的占位记录：读取器保留原件，但不据此宣称选择玩法可用。第一季目录只写真实关卡编号，当前没有拳击战斗历史，所以不生成角色列表、已获增益、已领奖励、轮次或最佳回合数。缺少任一原件、修改内容或空查询携带任何字段时，拒绝并保持玩家状态。

七条原生处理回归在旧实现全部因请求未接通而失败；本批同时回归既有44条钟表小子目录规则。最初候选读取器误拒绝末轮零增益与第二季零占位，属于新实现调试；修正后不将这两次候选失败计为旧功能行为失败。三份完整原件、SHA-256和换行边界保存在 `src/test/resources/fixtures/boxing-club-catalog-4.6.json`。

本机客户端只读证据（GameAssembly SHA-256 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，metadata `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`）核对了状态类CIEEKOEECHB的写出方法156703，以及BoxingClubLevelInstance.Sync / BoxingClubBattleRecord.UpdateDataByNetPacket。字段4对应关卡编号；字段2、9、13、15分别由当前轮次、累计回合数、最佳回合记录和关卡组读取；字段6对应已领取奖励。这里只核对静态消费关系，没有执行客户端或修改DLL，未取得游戏UI验收。

匹配、四轮实战、增益领取、失败/退出、首次奖励、活动任务和原始剧情仍待接通；第二季共鸣模式未开放。本批是后续流程的基础目录，不能算作完整活动玩法。

最终单次全量11,894项Java（452类）零失败；相关51项单独通过。累计1,083项Python沿用上批，测试文件未改，本批没有重跑完整Python套件。3,245个源码文件与151份协议快照通过。最终同一产物经14次原生KCP请求、两次正常关服、同一UID一次正常H2进程重启核对五关目录、重复读取及非法查询；2,254份来源文件未修改，没有预写拳击匹配/通关/奖励。认证与H2实际运行，但没有修改认证、存储、启动或HTTP，通用启动冒烟沿用上批。报告build/boxing-catalog-verification.json，产物build/boxing-catalog-certified.jar，SHA-256 7e6d18e68ad52570048e9b40fe5625a49f7733914d025431353c058533e1b3a2。本批只验证目录，仍未接通拳击实战或取得客户端UI验收。
