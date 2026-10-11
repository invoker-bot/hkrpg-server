# 4.6 遐蝶、日常NPC、宇宙资格与以太编队

本批接入遐蝶1407已核原始Free/Input战斗路径、3000601初段NPC生命周期、损坏宇宙资格拒绝，以及以太战线资料/六槽编队。统一专项51类/1308项、全量512类/13954项均通过。本机KCP/H2分别验证四条服务端流程，真实游戏客户端GUI和全部玩法仍待完成。

| 本批流程 | 实际请求 | 检查 | 正常停服 | 同UID进程重启 | 独立实际Player读取 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 遐蝶原始Free/Input | 294 | 330 | 3 | 2 | 9 |
| 3000601两日settled短信/NPC | 212 | 255 | 8 | 7 | 32 |
| 损坏资格拒绝/合法首次Start | 22 | 67 | 1 | 0 | 2 |
| 以太库存资料/六槽编队 | 59 | 212 | 2 | 1 | 4 |
| 合计 | 587 | 864 | 14 | 10 | 47 |

这些是四条有限流程的实际计数，未合并旧批网络矩阵。日常Effects检查复用Phone的16次Player读取，与G61的16次合计32；没有额外计作48。遐蝶另有9次实际Avatar读取，未混入Player列。资格的两次Player读取没有进程重启。

## 行为与来源

- [遐蝶Free/Input](maze-castorice-manual-ability-4.6.md)：按完整原始来源约束1407/14071/140704的施放身份、状态和入战清理；实际失败后相同输入可重试，旧ID/会话/场景拒绝。本批未补完整召唤生命周期或其他手动技能。
- [日常初段NPC](daily-origin-effects-4.6.md)：在已验证初段对话结算后，保存有任务身份与日期的来源收据并移除两原NPC，3个道具保留；重新进入与实际H2恢复不重建旧NPC，主任务CLOSED整组卸载，次日生成新任务身份。只有D0 reply102到D1 reply104的settled分支取得本轮网络验收。
- [宇宙资格](chess-rogue-qualification-integrity-4.6.md)：Query和首次Start共同核对本服完整完成记录，损坏记录不创建局或支付奖励；合法记录可以进入原source201。原资格奖励与首奖分别验证，109011未支付。
- [以太资料/编队](aether-divide-roster-4.6.md)：持有灵与碎片库存分开，查询读取实际材料数量，六槽使用四个明确成员位置；短数组完整替换补0是本服规则。持有灵来自隔离存档夹具，捕获、升级、装备、挑战和战斗未验收。

所有原始来源取自固定提交或本机客户端只读解析，没有执行参考仓库的二进制或将参考源码加入运行时依赖。内部协议基线仍为4.2.0，4.6线协议仍处于实验阶段。遐蝶所需的原始LocalPlayer配置在运营目录仍缺失；仅在隔离网络夹具内复制固定原件，没有安装到运营资源。完整GameAssembly/metadata/协议Writer及消费方证据见各模块来源夹具，静态消费证据不等于客户端接受。

## 构建与实际证据

冻结运行包：`build/castorice-daily-chess-aether-runtime.jar`，SHA256 `f586b2f1476ff564ef206cbf580dfbd8a71fcd2906b81c67e9b3f05dd99b6155`。统一专项实际耗时99.556秒，全量759.339秒。全量2037个`src`编译/资源输入及构建配置前后SHA稳定，排除`src/generated`；这不表示忽略目录、全部文档及后续目录更新均未变。全量新鲜XML逐份哈希在 `build/castorice-daily-chess-aether-full-verification.json`，专项在 `build/castorice-daily-chess-aether-focused-attempt2-verification.json`。

| 实际网络报告 | SHA256 |
| --- | --- |
| `build/maze-castorice-ability-current-native.json` | `1e4ebaa85b577f42a8ae2b2b661647e3cd32efbfaca182cf8eb0a2b41c49c984` |
| `build/daily-origin-effects-current-native.json` | `88a776792343775d05ffbba2b7fe4bffb512eaec21f535feaf290491f2be750e` |
| `build/chess-rogue-qualification-current-native-v2.json` | `1a836e1a8a8c252f3110c7f6a9d121cd01f9895c7bdaf9b54ee5604b064cbd4b` |
| `build/aether-divide-roster-current-native.json` | `fa058b2720b14c5b15035a27f7bd83868d38e3d79b4eb0cc9d9ac716c5789279` |

每条网络流程使用独占目录、完整来源守卫、新包生产CodeSource及正常控制台stop；检查物理H2和实际Player恢复。遐蝶、资格和以太晋级工具的Java保持与实际运行字节相等，Python仅调整文件布局；日常两Java完整字节仅替换独立类名，与旧执行名的差异和两份SHA分别封存，新的类名只做编译和描述符预检，未冒称再次执行Native。正式布局的46项Python守卫通过；预检没有启动游戏服，不计入上表。

可重跑入口为 `scripts/smoke-maze-castorice-ability.py`、`scripts/smoke-daily-origin-effects.py`、`scripts/smoke-chess-rogue-qualification.py` 和 `scripts/smoke-aether-divide-roster.py`。每条入口必须传入新冻结包和独占输出路径，支持预检但不把预检计作网络验收。日常 `scripts/verify-daily-origin-effects-native.py` 另外只读复核实际旧类名日志，恢复32次Player计数和四次原NPC删除通知顺序。

原配置及4.6运营资源共70624文件的SHA核对通过，`build/castorice-daily-chess-aether-final-operator.json` 保留完整比较。根`data/`未创建，运营配置和资源未安装或覆盖。

目录生成后最终包 SHA256 为 `90aae8aaf0f07b81dd922eb7ffa5c830dc6c18a2dfe594cdd5f2ec7d77273f40`；与冻结f586包逐项核对，仅`features.json`和`feature-backlog.json`不同，22423个Java class逐字相同，未新增或丢失Jar条目。没有把目录更新称作重新执行全量Java。39个功能目录和488个唯一入口的证据路径已校验；新增SwitchAetherDivideLineUpSlot仍为partial，所有client46均为false。目录证明见 `build/castorice-daily-chess-aether-catalog-artifact-proof.json`。

最终包按现有 `scripts/smoke-server.py` 在独占目录运行两次Dispatch/auth/持久化烟测，账户UID恢复与健康、目录和令牌检查通过，见 `build/castorice-daily-chess-aether-http-summary.json`。该工具按原策略terminate，不把这两次退出加到上表正常stop。最终构建、网络、目录和运营输入的完整文件SHA汇总在 `build/castorice-daily-chess-aether-final-verification.json`；同批Git提交保存正式源码、工具、夹具和文档。

全部完整剧情演出、其他日常分支、召唤生命周期、其他战斗动作、宇宙首奖/结束流程、剩余祝福奇物、活动捕获/装备/战斗/挑战以及真实4.6客户端验收继续进行。当前结果不标为全部完成。
