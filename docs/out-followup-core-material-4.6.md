# 4.6 遐蝶退场、非池日常、以太芯片和材料提交批次

本批完成四个有限玩法分支，完整战斗、剧情、宇宙、活动与真实游戏客户端仍继续开发。核心继承协议保持 4.2.0，4.6 动态线协议的功能增加没有替代游戏客户端验收。

| 分支 | 本批行为 | 实际 KCP 请求 / 检查 | 正常停服 / 同 UID 重启 | 独立实际 Player 读取 |
|---|---|---|---|---|
| 遐蝶 Free OUT | 原释放身份及 140704 寿命绑定，原移除叶，零秘技点退场 | 290 / 319 | 3 / 2 | 9，另有 9 次 Avatar 读取 |
| 日常 3000604 | 父 3000603 原电话分支后接取非池后续，原列车往返与 G85 授权对话 | 141 / 171 | 7 / 6 | 28 |
| 以太芯片 | 原灵晋阶及 1 基类型槽，真实库存空槽装备，显式卸下后装备另一灵 | 48 / 319 | 2 / 1 | 4 |
| 材料活动 30014 | 301 至 307 原始扣料、顺序提交与一次性原奖励 | 74 / 271 | 2 / 1 | 4 |
| 合计 | 四个相互隔离的实际流程 | 553 / 1080 | 14 / 10 | 45 |

上述四份成功报告对应同一个生产包，没有生产类覆盖。统一构建 `scripts/build.ps1` 完成 516 个套件、14,106 项测试，失败、错误和跳过均为零。2573 个源码、生成协议、资源和构建输入在完整构建窗口保持稳定。完整测试日志与 XML 的证明保存在 `build/out-followup-core-material-full-verification.json`；候选和最终 `build/out-followup-core-material-runtime.jar` 的 SHA256 均为 `39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab`。

功能清单随后生成至 492 项，并执行 `scripts/build.ps1 -SkipTests` 重打包。`build/out-followup-core-material-final-runtime.jar` 的 SHA256 为 `4a8e6dab5db944619081e669585f9f8f5a2c6ea8bbfe76c3717ad8805e04ae2b`；全部 23,709 个条目的内容核对中只改变 `features.json` 和 `feature-backlog.json`，22,434 个类文件及其余载荷均与39601包逐字相同，构建输入窗口稳定。本次没有把目录包称为另跑四次Native，代码等同性证明在 `build/out-followup-core-material-catalog-verification.json`。最终目录包的 `scripts/smoke-server.py` 已实际通过两次 Dispatch 启动、异常认证请求和同账号重启恢复，见 `build/out-followup-core-material-http-verification.json`；其协议基线为4.2.0，不计为4.6游戏客户端验收。

| 成功报告 | SHA256 |
|---|---|
| `build/maze-castorice-out-current-native.json` | `08feceb9582f2e69029ef3e2f32b1f530a0907c26ebeb95c46a351313f5c2658` |
| `build/daily-followup-3000604-candidate-native.json` | `97d900db8a231ce19e0e525653e82b87e045b7ae32e1c2c1f39fe22c867a1ab2` |
| `build/aether-divide-skill-core-current-native-v2.json` | `720f00d6b35a653468270e3253bbb10888a9ad7b746365cba0460bcee6ce9533` |
| `build/material-submit-activity-current-native.json` | `af1c7914f703f02d3ac8f2c3ccc68ae3aaad58bc2f7c7283771a5d1e7df6b7d4` |

四个新专项共 152 项测试，分别为 40、34、31、47 项。回归曾在前批冻结 f586 包上实际失败，再在各自隔离候选上变绿；没有把缺类、无法编译、对象键顺序或错误来源夹具计作行为失败。旧 Input 的“OUT 尚未支持”预期被新的正式分支替代；以太旧编队测试只调整与真实芯片来源和类型槽不符的库存夹具，其余行为断言保留。

以太第一次 Native 在实际卸下成功后被观察工具误判：普通 `Player.save` 同时写入登录已在内存更新的 `lastActiveTime`。原完整差异与报文保留在 `build/smoke/aether-divide-core-75plspb3`；该失败尝试只完成首次正常停服和 H2 检查，重启进程由该脚本的隔离清理路径终止，不计入上表成功总数。第二次在卸下前执行显式夹具检查点，记录完整保存前后及唯一的已知登录字段差异；这一次真实保存不计作芯片行为、只读检查或保存故障注入。观察工具先保存完整快照再判定，通过独立 ERROR 标记失败，避免夹具异常结束服务器控制台循环。生产控制台行为未在本批修改。

以太和材料的保存失败回退由专项单元回归验证，Native 未注入故障。材料扣费开始后的不确定状态保留 RESERVED 并拒绝重复扣费，没有自动退款、自动对账或跨文档崩溃原子性。以太不支持已占用槽覆盖或不经卸下的跨灵转移。材料查询没有把历史活动自动开启，提交没有伪造对应主线和演出完成。

来源保持 LunarCore AGPL 归属。原资源取自固定 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，4.6 协议参考 March7thHoney-OpenSource `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，并与本机安装客户端完整 Writer、消费者及构造器对照。没有将参考仓库变成运行依赖，也没有手写生成协议类。原资源、完整 JSON、完整函数字节和来源 SHA 的范围分别见四份专项说明；Git LF 原件与安装 CRLF 文件按完整 JSON 内容核对，并分别记录原字节 SHA。

运营文件审计 `build/out-followup-core-material-final-operator.json` 核对 70,624 个文件全部未变，根目录 `data/` 不存在。实际流程只写各自隔离目录的 H2、夹具配置和复制资源。安装客户端、本机运营资源、现有 `config.json` 和生产存档没有被覆盖。

专项说明：[遐蝶退场](maze-castorice-out-4.6.md)、[日常后续](daily-followup-3000604-4.6.md)、[以太芯片](aether-divide-skill-core-4.6.md)、[材料提交](material-submit-activity-4.6.md)。原始客户端请求序列、完整召唤和动作、客户端 CustomString 演出、自然跨日、以太捕获/升级/挑战/战斗、其它材料提交及完整小游戏仍需要后续实现与实际客户端验证。
