钟表小子分支追加：原始检查点通过后保存单骰子分支，1101/2101/3101分别指向102/202/302；下一章携带属性、先等剧情确认，再进入原始普通回合。扁平历史与抵达凭据保存后复算；36项回归含六种各两次实际Player/Morphia重载，合成KCP/H2验证两剧本的分支、下一章剧情和骰子恢复。官方抽牌分布、更多分支/结局/小游戏与真实UI仍待完成，详见docs/clock-park-branch-4.6.md。

钟表小子检查点追加：首三章末轮已按实际骰子/原始牌效果计算并保存111/211/311通过或失败；23项检查含初始8项先失败及六种各两次Morphia恢复。确认操作13保留结果至分支/结局实现，第4/5章仍等待原始随机小游戏；当前牌组不增C且201的B最多24。完整玩法和真实UI继续待完成，见docs/clock-park-checkpoint-4.6.md。

# 时钟乐园原始剧情对象与目录查询

时钟小子普通骰子回合追加：五个故事确认进入保存后的三个服务端骰子，固定原始1001/1002/1003为明确本服牌组；原始行动/效果、面值数量与回合去重、失败回退和回执复算接入。66项新增Java含49项先失败及十种各两次Player/Morphia恢复；候选204次KCP请求、18次真实推进和五局正常H2恢复，最终双源码产物以build/clock-park-dice-verification.json为准。此前7411封存范围保留各章末轮；现首三章已进入检查点，后两章仍保留骰子等待。官方抽牌分布、检查点/分支/小游戏/其他牌/Buff/结局/奖励/无尽/完整剧情和真实UI仍待完成，见docs/clock-park-dice-4.6.md。

本批恢复本机国服 4.6 客户端中的全部 27 份 `ClockParkStoryConfig` 及它们引用的 27 份 `LevelGraphConfig`。资源读取和目录查询已实现；后续五个普通剧本的开场、退出及持久化追加见 [开场流程](clock-park-opening-4.6.md)。回合操作、骰子、分支选择、拍照、射击、电影小游戏、Buff、结算、奖励仍待实现，真实游戏界面尚未验收。

## 固定来源与读取边界

客户端 DLL、元数据和设计索引身份沿用 [只读设计数据说明](client-design-data-4.6.md)。新增 [字段证据](../scripts/data/client46-clock-park.json) 固定 14 类继承字段、14 个读取方法、10 个任务工厂分支与 25 个完整原生函数的 PE 边界和 SHA-256。任务编号来自实际工厂数组赋值，不能按方法排列顺序推算。

原始 54 条记录共 19,942 字节，逐条保存索引键、数据块、位置、长度和 SHA-256。九类时钟乐园任务及通用 `FinishLevelGraph` 已核对，原有 `WaitSecond` 等类型复用已验证的基础剧情读取器。每条记录必须完整消费；未知掩码、类型、任务、非法数值、截断或剩余字节均停止。保留省略字段、动画、等待、原始 TextID 编号与无符号 64 位哈希，不补中文台词或默认执行效果。

输出为本服专用 `ServerData/ClockParkStories.json` 原始对象包，保留元数据短类型名、字段掩码与偏移，**没有重建 Unity JSON，也没有执行剧情**。原始 `Config/ConfigActivityClockPark/*.json` 文件仍不安装。本服对象包的 SHA-256 为 `92840d9743b0708323171b6e8389851f8a47fe29d7e6d6c9f235bec1cdcc01c3`；补充清单注明运营者本机 CN 客户端来源，不改原始 OS 资源清单。

```powershell
python scripts/recover-client-clock-park.py --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' --destination resources/4.6 --output build/clock-park-recovery-installed.json
python -X utf8 -m unittest discover -s scripts/tests -p test_client46_clock_park.py
python scripts/audit-resources.py --output build/clock-park-resource-audit.json
```

全部对象和目标冲突先检查再写入；相同内容复用并保持修改时间，不同内容拒绝覆盖。客户端目录及越界目标不得接收输出。资源安装只增加原始对象包和对应补充清单，配置、数据库、已有资源保持原字节。安装后完整审计 70,605 份资源通过，698 份楼层的地图组引用无缺失。

四份原始 Excel 表来自 `DimbreathBot/TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b`，分别为 `ClockParkScriptConfig`、`ClockParkChapterConfig`、`ClockParkRound` 和 `ClockParkStory`。Java `ClockParkCatalog` 先核对这四份完整文件及本服对象包的 SHA，再检查六个剧本、28 个章节、169 个回合、27 个故事及全部引用；任一输入缺失或变化，整份目录不可用。返回的原始对象为独立副本，调用者不能改写目录。

## 原生查询的有限范围

新增 `ClockParkGetInfoCsReq` 7234 → `ClockParkGetInfoScRsp` 7206，仅接受无字段请求。全部原始资源有效时按编号返回六个剧本；未知请求字段及资源损坏返回错误且不暴露部分目录。

客户端原生接收方法、脚本列表同步和单剧本同步见 [静态证据](client46-clock-park-info-evidence.json)。响应字段 6 是剧本记录，内层字段 14 为剧本编号，字段 9 是 **已到达章节**，起始章节可用不代表已到达。初始账号的目录保持空到达记录，进度、当前剧本、最高分和天赋保持默认值；后续开场追加只按实际已保存进入记录返回章节和当前剧本。这些字段不能当作小游戏完整可玩或全活动解锁。

## 验证

新增 13 项 Python 检查：全部 54 条原件、所有截断位置、尾部字节、未知任务/掩码、原始动画/台词/结束动作、冲突覆盖、原生字段/工厂/完整函数及其变更拒绝。初始基础读取器对全部 54 条原件均无法读取；原始锚点为 `A`，先前测试误写 `AnchorA` 的夹具断言已修正，不能作为读取器问题。

新增 44 项 Java 检查：真实入口三项先失败，全部 27 对故事、五种缺失与五种变更输入、完整目录和拓扑、未知回合类型省略、返回对象隔离、重复读取、玩家状态及资源不变、旧协议和其他命令边界。完整源码与打包产物验证以 `build/clock-park-catalog-verification.json` 的完成状态及同 SHA 报告为准。

`scripts/smoke-clock-park-catalog.py` 使用合成 UDP/KCP，在隔离目录测试有效对象包、仅自有副本损坏、恢复原字节三种情况；同 UID 普通重连及两次 `stop` 正常关服/H2 重启后重复查询。每份产物预计 24 次请求、三次正常关服、三份只读 H2 检查，核对剧情状态、任务、钱包、场景、命途和正式编队不变。候选产物已通过；最终两份源码产物复验另列在完成报告中。没有设定前置完成状态或开放活动窗口，也没有证明官方玩法、自然剧情推进或断电原子性。
