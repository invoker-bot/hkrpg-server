# 回响菜单恢复与客户端能量字段链

宇宙仍为 `partial`。本批补的是已取得祝福之后的菜单恢复和静态字段证据，不是完整宇宙、完整回响战斗效果或国服 UI 验收。

此前奇物选择先保存取得的普通祝福、清空奇物待选，再在通知阶段建立回响菜单。进程若在两次保存之间中断，存档会达到阈值却没有应得菜单。现在奇物选择在首次 Player 保存前建立菜单；成功重入同一局时也按保存的回响计划补回遗漏菜单。基础/强化仍需真实选择请求取得，重入本身不发放祝福或补充能量。

重入恢复保持原局 UUID、所选命途和已保存能量；已有普通祝福双选或奇物菜单优先，不覆盖、不改批次。场景进入失败、区域不符、缺少角色或终局不建立菜单、不保存；旧局无回响计划、未知政策、异命途和不足阈值不借当前目录制造菜单。负数或耗尽的批次不创建不可使用的选项；已有强化的错误等级不计作有效回响取得。

新增 20 项 Java 回归，八项先以实际行为断言失败，其中包括原来的首次保存窗口、四个阈值的恢复、重复重入、批次溢出和错误强化等级。负批次最初由测试的换局 setter 自动提升，夹具现已明确模拟读取后的存档损坏；该早期夹具误判不计入行为 RED。测试中的火漆限定一个原始普通祝福为可取得，是明确资格夹具；原始回响和奇物定义保留，不代表官方候选池。首次保存后主动抛出异常仅证明该保存快照已经包含菜单，不证明真实断电写入原子性。

最终报告为 `build/rogue-resonance-recovery-verification.json`：完整构建 8425 项 Java（346 类）通过，Python 198 项通过，源码 2609 文件与 151 份冻结协议核对通过。最终 jar SHA-256 为 `91184e3a05fa579fbab69c338160096d742f4449a8781810ee5904aea0b671d9`；对应产物通过 HTTP 账户恢复，以及 56 次实际 KCP 请求、64 项断言、六次正常停止、五次同 UID 正常 H2 重启和十二次实际 Player 读取。四次明确资格写入之后，由真实重入请求建立四个回响菜单并选择原始基础/三强化；2254 份来源资源哈希不变。

本机 `GameAssembly.dll` SHA-256 为 `8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`，元数据 SHA-256 为 `09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。十三个完整 PE 异常表范围、共 19078 字节、五个类型及参数证据固定在 `src/test/resources/fixtures/rogue-resonance-wire-4.6.json`。以下链条由完整方法字节交叉核对：

- `GamePlayStatic.CreateBattleStatsProto` 将 `BattleEventBattleInfoListFillPrimary` 结果放入统计对象偏移 `0x10` 的列表；统计 `WriteTo` 使用同一偏移和同一 codec，codec 的 tag `0x82` 对应字段 16，parser 静态位置与事件消息初始化相符。
- 事件序列化字段 1/2 是事件编号和属性；属性字段 2 是能量条。能量条偏移 `0x18/0x1c` 分别编码为 wire 字段 1/2。
- `CreateBattleEventEntity` 读取这两个数值到创建参数 `0x48/0x50`。初始化回调按同一顺序传给 `ResetBattleEventProperty`；它用 `MaxSP × 第一个数值 ÷ 第二个数值` 写入 CurrentSP。`AbilityProperty` 原始枚举值 MaxSP=11、CurrentSP=12 与属性修改位置相符。

这独立支持现有 `BattleStatistics.BDCHEJKPOAF=16`、`sp_bar=2`、`cur_sp=1`、`max_sp=2`；没有改动生成类、命令号或 151 份冻结协议快照。来源注释中的对象偏移与本机布局不同，不能直接把注释偏移当字段证据。复验命令：

```powershell
python scripts/verify-rogue-resonance-wire.py --client-dir C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current
python scripts/smoke-rogue-resonance.py --recover-missing-menus
```

第二条使用独立、带标记的 H2 和资源镜像，明确种入达到 3/6/10/14 个普通祝福却没有菜单的记录，随后由实际 4.6 请求重入、查询、选择并检验重复拒绝。能量 2500 和首房 306 仍是资格夹具；只核对正常停止后的同 UID 恢复，原运营数据不改。报告中的 56 请求/64 断言均来自上述最终 SHA，不能视作实际战斗赢得这些祝福、官方能量报告或游戏 UI 验收。

后续已补自有探针对原始怪物遭遇的能量报告及正常 H2 恢复，见 [网络证据](rogue-energy-network-4.6.md)；它不等于官方客户端战斗报告或技能验收。商店/骰子/大乐透的首次保存另见 [交易说明](rogue-resonance-transactions-4.6.md)。全部回响能力脚本/强化组合、官方初始能量和阈值、真实战斗获得祝福、首次命途对话、未知奇物、完整事件/剧情/活动及国服游戏 UI 仍需补齐。
