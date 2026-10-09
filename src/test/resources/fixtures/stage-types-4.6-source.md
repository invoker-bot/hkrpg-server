# 4.6 新关卡类型的原始数据

六条关卡来自 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的 `StageConfig.json`，完整原始字段及表 SHA256 保留在夹具中。数值 34..39 分别对应 FateActivity、LocalLegend、ElationActivity、FateRin、ExpeditionBattle、FinalityActivity。

枚举值来自固定本机客户端元数据 `RPG.GameCore.StageType` 的显式 Int32 常量，类型索引 10419；`GameAssembly.dll` SHA256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据 SHA256 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。原始类型仍由客户端关卡表的完整读取核对，而非根据行序猜枚举。

这只验证服务器解析类别，不能证明六个玩法的输入、规则、脚本、奖励或客户端验收。没有修改生成协议或旧枚举值。
