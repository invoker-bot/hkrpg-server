# 开拓等级奖励领取

截图中的 3 级奖励按钮已经发送请求。正式运行日志连续记录四次 `Unhandled 4.6.0 inbound command: 19`；服务端缺少原生领取入口，客户端因此等不到结果。已领取列表此前只回空占位、没有读取真实收据，`PlayerLevelExcel` 还遗漏了 `LevelRewardID`，不是鼠标或按钮命中问题。

原生协议沿用仓库已锁定的 March7thHoney `fe00bdbda3c28ca33d01f171566aa35b5ab95083`：`GetLevelRewardCsReq` 19 → 85，请求等级字段 7，交互实体字段 1；回复等级字段 9、错误码字段 10、奖励字段 15。`GetLevelRewardTakenListCsReq` 78 → 4，回复错误码字段 9、已领取等级列表字段 15。没有手改生成协议。参考项目为 AGPL-3.0，本次 Java 实现独立复用现有背包和收据结构；交互实体不作为等级、数量或物品的依据。

奖励数据来自 `TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b` 的 `PlayerLevelConfig` 与 `RewardData`，2～70 级共 69 档。3 级按原始记录发放星琼 50、角色经验材料 211 共 5 件、信用点 10,000；不凭截图修改奖励。所有资源文件保持原内容。

`PlayerLevelRewards` 在玩家锁内检查实际开拓等级、奖励定义、余额上限、材料叠加上限、食物/光锥/遗器容量和物品编号容量，然后保存 `player-level:<等级>` 收据，再向真实背包投递，完整到账且再次保存成功才报告领取成功。重复请求返回 2107，等级不满足返回 2108；不合规报文也返回相应原生失败包，保持会话。列表只包含实际完整领取记录，不按当前等级伪造历史领取。

首次保存失败时尚未投递，可重试。投递中断、部分到账或最终保存失败保留待处理收据，阻止自动重复发奖；本项目多文档存储仍不提供断电跨文档原子事务，也没有增加自动对账或补发。老存档缺少该前缀时视作未领取，本轮不会根据历史余额猜测已领取等级。

验证使用 `PlayerLevelReward46Test`、`PlayerLevelRewardsTest`、`scripts/audit-player-level-rewards.py` 与 `scripts/smoke-player-level-rewards.py`。原始先失败证据保存在 `build/evidence/level-rewards/protocol-red.xml`，请求分派回归 11 项中 10 项真实失败。资源审计逐项比较固定提交原件和 32 条夹具记录，并检查全部 69 档奖励。领取单元测试的投递明确使用替身；实际背包、网络回包和同 UID 正常 H2 重启由独立探针验证。真实游戏画面由用户操作验收，全程不使用 computer use。

两类专项单元回归共 35 项通过。固定源码副本的原生探针完成 81 次请求，实际领取全部 69 档，并逐项核对原始奖励、钱包、材料、光锥和遗器；同 UID 重连和正常 H2 重启后的领取记录、防重、物品编号与词条一致。原生请求带异步包编号，回复关联正确，通知不借用请求编号。两次正常停服通过，配置和八份原始资源摘要不变。`GetBag` 的重复行按整行内容排序后比较，保留所有字段，未忽略遗器词条或唯一编号。

本轮固定源码副本位于 `build/level-rewards-source/`，由基线 `d366279` 加本次修改构成，未纳入共享目录中同时进行的活动复刻、好友页等其他功能。该副本的产物 SHA256 为 `3985357131f8815d79fbf81a78de16e3cf8df40932d7b0f25942b7ad5810d2bb`；原生证据在 `build/level-rewards-source/build/player-level-rewards-native.json`，资源审计在同目录的 `player-level-rewards-resources.json`。额外两轮 Dispatch 冒烟及账号正常重启恢复通过，完整游戏客户端验收仍待用户确认。

Windows 完整门禁 `gradlew.bat test shadowJar -PtestForks=2 --no-daemon` 通过 655 类 / 16,127 项，失败、错误、跳过均为 0。JVM 使用既有 TCP loopback 回退及 `-XX:ActiveProcessorCount=2`；首次 8 路尝试发生系统原生内存分配失败，降低本轮并行度后完成全部回归。3,045 个冻结编译/资源输入的摘要前后一致，JAR 摘要与原生探针一致。完整结果见 `build/evidence/level-rewards/full-final.json`，日志为 `build/level-rewards-final-full.log`；不将其他对话的未提交修改或中途终止的容器尝试计入本轮完整通过范围。

发布前检测到正式服务已被另一轮部署更新为猫箱修复产物 `3053ee27…b2a8`，原候选包因版本保护被拦下，没有覆盖服务。主分支随后以 `21add6c` 合并已部署的 `3081e07`，保留等级奖励、猫箱、初始列车房间及主分支已有 GM 功能；好友页等未提交修改继续留在工作区，没有打入本次包。

最终组合产物 SHA256 为 `70e1eaf13b65020bcdfd7cd1050d29eb14a273de7b60355988bb3cf26239c1af`。组合后另跑 7 类 / 129 项定向回归，零失败、错误或跳过；同一产物再通过等级奖励 81 次原生请求、猫箱 45 次原生请求及各两次正常 H2 停服恢复。组合证据位于 `build/evidence/level-rewards/integration-final.json` 与 `build/level-rewards-integrated-source/build/`；上述 16,127 项全量结果对应合并前的领奖修复产物，未将其写成组合包再次全量通过。

本地服务已正常备份并更新，运行 JAR 摘要与组合产物一致，31 份现有配置及凭据摘要保持不变；部署清单同步为当前产物，旧清单一同备份。回退与备份记录见 `build/evidence/level-rewards/deployment-final.json`，只读 Compose 复查见 `compose-after-metadata.json`，未在正式账号上代领任何奖励。用户需要重新登录、重新打开等级奖励页面验收真实领取画面。
