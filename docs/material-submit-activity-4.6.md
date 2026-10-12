# 4.6 翁法罗斯材料提交

Java 已接通实验 4.6 的材料状态查询、按原表扣料和一次领奖。范围限原 `MaterialSubmitterGroup` 的活动30014、提交项301..307；另外21个提交项尚未接通。实际可用性取运营配置中的对应模块窗口、原面板的21级条件及前一项提交成功；查询既有记录不打开活动，也不创建记录。

| 请求 / 响应 | 命令 | 已核字段 |
| --- | --- | --- |
| GetMaterialSubmitActivityData | 2621 / 2612 | 空请求；retcode2；状态列表8 |
| SubmitMaterialSubmitActivityMaterial | 2611 / 2617 | 请求提交项8；retcode6；状态8 |
| TakeMaterialSubmitActivityReward | 2638 / 2678 | 请求提交项7；retcode7；提交项3；非空ItemList15 |
| KPMAKCHCDMH | 状态 | submitted8；taken13；提交项ID15 |

状态field9的含义仍未闭合，因此没有填入面板ID或猜测值。客户端真实消费者按field15缓存提交项，随后以submitted解锁下一项，不要求先领奖；taken来自独立奖励凭据。相同已提交项返回成功但不重复扣料，这是本服重试策略。已领或处于未完成支付状态的奖励不再次支付。

## 来源

来源夹具 `src/test/resources/fixtures/material-submit-activity-4.6.json`，SHA256 `455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64`，保存固定 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 的六份完整原文件：MaterialSubmitter28行、MaterialSubmitterGroup1行、ActivityConfig525行、ActivityPanel270行、RewardData9515行和ItemConfig2941行。运营资源与该固定提交检出目录的六份文件逐字相同；Git blob采用LF、检出与运营文件采用CRLF，六个完整JSON对象相等，物理字节及SHA分别核对；运行时每次请求复核完整SHA及本次加载的奖励、物品类型与叠加上限，缺失或改变时拒绝，绝不从references回退。

同一夹具包含58个完整PE函数、12661字节，保存完整异常表边界、完整机器码和逐指令解码。原生发送器、请求/状态/响应Writer、消费者、调度查询及顺序解锁链共同支持上述字段。GameAssembly SHA256为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，metadata为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。仅静态读取，没有启动或修改客户端。

协议命令同时交叉核对 March7thHoney-OpenSource 的AGPL来源提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`；没有引入其运行时代码。参考协议的部分内存偏移注释与真实Writer不同，以完整客户端证据为准。

七项合计按原奖励表支付500星琼、280000信用点、21个213及七种原表礼物。这里实现材料与奖励链；未把Main8031401..8031407标为完成，也未模拟对应Act或WaitCustomString。

## 保存与验证边界

先验证真实持有库存、物品归属、锁定与装备状态，再复制保存RESERVED记录，逐项调用真实Inventory.removeItem，最后保存SUBMITTED。首个保存失败恢复原对象且不扣料；扣料开始后的异常保留RESERVED，后续返回忙碌，避免不明确状态的自动重扣。该行为不是跨玩家文档与材料文档的全局事务，也尚无自动异常对账功能。奖励沿用原RewardLedger保存后支付机制；部分支付停在未完成状态，不能声称崩溃原子性。

47项新回归在不可变旧f586运行包上产生46个纯行为断言失败及1个完整来源控制通过，排除Gradle main输出且全部CodeSource合法；多个失败主要是同一个入口缺失，不计为46种缺陷。随后扩大存档损坏检查，在独立实现中锁定null phase与三种奖励凭据完整性缺陷，修复后47项全绿。真实内存Mongo/Morphia保存Player并独立读取两次，核对完整Gameplay和BSON；这不等于物理H2或真实客户端验收。

首次扩展中的一条JSON字符串比较受对象键顺序影响，规范对象完全相等，已归类为夹具失败并改用完整JSON语义比较；它不计行为RED，其余损坏记录失败及原始输出保留。不可变基线报告 `build/material-submit-activity-next-source/immutable-f586-red-v2/verification.json`，最终独立绿色报告 `build/material-submit-activity-next-source/owned-green-v4/verification.json`。真实KCP随后使用冻结候选包通过74次请求、271项检查、两次正常停服和一次同UID重启。两次物理只读H2各独立恢复两个实际Player，复核玩家奖励、独立材料文档、钱包及完整Gameplay。七项实际支付500星琼、280000信用点和21个213；共享成本180005耗尽后真实材料文档不存在。查询、拒绝与同项重试的完整live/BSON/库存/钱包均无变化。窗口和每种成本4份库存为显式隔离夹具，材料获取及完整任务不是该验收范围。

当前协议基线仍为LunarCore lite4.2.0，设计目标为4.6；没有通过更改版本常量声称客户端接受。整个活动、小游戏、剧情及全游戏均仍未完成。

运行包 `build/out-followup-core-material-candidate-runtime.jar`，SHA256 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`；报告 `build/material-submit-activity-current-native.json`，SHA256 `af1c7914f703f02d3ac8f2c3ccc68ae3aaad58bc2f7c7283771a5d1e7df6b7d4`。9个被核对生产类均来自该唯一jar，实际运行无production overlay。Native 执行时全量构建尚在运行；随后根代理已核对516套件/14,106项测试全绿、2573输入稳定，最终冻结运行包与候选SHA逐字一致。目录资源重打包和全批验证边界见[统一批次说明](out-followup-core-material-4.6.md)。

正式重跑工具为 `scripts/smoke-material-submit-activity.py`、四个 `scripts/fixtures/java` 下的MaterialSubmit helper及16项离线隔离守卫。五个运行工具逐字等同该次Native已执行来源；守卫仅按正式布局调整加载路径。纯结构/codec预检不启动游戏服，不能计入KCP/H2读取。先前工具预检把LF Git blob与CRLF检出文件误要求逐字相等、以及假设不存在Player setter导致编译失败，均已单列为准备错误，未计行为RED或Native成功；原失败目录留存。
