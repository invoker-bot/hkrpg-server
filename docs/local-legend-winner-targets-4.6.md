# 4.6 传奇挑战基础目标胜利叶

普通与无畏的原始基础目标在 OnLeaveBattle/ByLocalWin 下执行 SetBattleTargetProgress，增加1。服务端现执行这10个明确的胜利叶，合法胜利无需额外目标统计即可完成对应基础目标。困难的基础目标由另外的动态变量条件驱动，胜利成就与该目标分开处理；不会仅因困难胜利而补基础目标，也不会自动完成任何附加目标。

|组别|普通目标/能力|无畏目标/能力|
|---|---|---|
|1|5001804 / BattleTarget_LocalLegend_S1_4|5001805 / BattleTarget_LocalLegend_S1_5|
|2|5001809 / BattleTarget_LocalLegend_S2_4|5001810 / BattleTarget_LocalLegend_S2_5|
|3|5001814 / BattleTarget_LocalLegend_S3_4|5001815 / BattleTarget_LocalLegend_S3_5|
|4|5001819 / BattleTarget_LocalLegend_S4_4|5001820 / BattleTarget_LocalLegend_S4_5|
|5|5001824 / BattleTarget_LocalLegend_S5_4|5001825 / BattleTarget_LocalLegend_S5_5|

完整15份能力对象（五组困难、普通、无畏）保存在测试夹具 local-legend-winner-target-abilities-4.6.json，来源为 TurnBasedGameData 提交724b139d8c9c32d12552eb95745a4fee72bfe48b 的 Config/ConfigAbility/Level/Level_LocalLegend_Ability.json，完整源文件SHA256为1d03573599903693f0d4099e73fb4dcaabd5460021dd2e0327100203868288d3。OnCreate 按 AbilityName 将本关目标绑定到 _BattleTargetKey；胜利叶读取唯一动态哈希416319683，并明确声明 ModifyValue=1。困难的胜利分支没有目标修改叶，不能把同样的成就分支当作目标条件。

固定客户端只读工具 verify-client-local-legend-achievement-default.py 追加核对 SetBattleTargetProgress 的成员偏移、Add枚举值及构造函数默认值。其构造叶跨度按相邻已核对声明方法起点限定16字节，检查3个指令和返回位置；该跨度不是PE展开表函数边界。实际客户端没有启动，不能据此声称完整舞台执行或游戏UI验收。

LocalLegendWinnerTargets 逐项绑定目标ID、组别、难度、能力名和原始阈值1，目录加载即拒绝别名搬移、阈值改变、缺失源基础目标及复用到附加目标。当前战斗须仍绑定同一目录、舞台、队伍和有效存档，并通过完整报告校验，胜利后才合并原始叶与已校验类别5报告。零上报不能撤销胜利叶；外关、重复和未知报告仍拒绝整次结算。战败、退出不执行胜利叶。目标存档保存失败恢复旧内存，不提前刷新任务或发奖。

52项新增检查覆盖五组普通/无畏无统计胜利及零统计、五组困难不会自动满足动态条件、20项战败/退出、六项源归属变更及完整原始对象核对；修复前26项发生行为断言失败。已有困难/无畏成就检查按实际原始目标同步核对基础目标完成位，保存失败检查改用无统计胜利，未将原有断言改为跳过。

网络检查使用完整原始表、隔离存档和构造战斗报告。普通胜利不携带目标或成就统计，基础目标和实际奖励仍生效；困难保留类别5目标报告但省略成就报告；无畏报告附加目标位4，原始基础目标位1合并后为5。实际货币、物品、任务、目标和成就绑定记录须经重连及两次正常H2重启保持。最终全量及独立源码验证以 build/local-legend-winner-targets5131-verification.json 为准。

这10个基础目标胜利叶和10个困难/无畏成就叶是目前执行的明确原始子集。其他目标的动作计数、状态条件、完整目标Ability、事件20037、实际游戏输入与UI仍待补齐。胜利状态仍来自构造的结算报告；正常重启不证明跨文档崩溃原子性。玩法状态保持 partial。
