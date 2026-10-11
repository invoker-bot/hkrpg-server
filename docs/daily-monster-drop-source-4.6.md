# 4.6 击怪日常的普通掉落来源边界

独立脚本夹具 `scripts/fixtures/daily-killmonster-drops-4.6.json` 保留 NPCMonster 8012010、8012020、8012030 对应世界等级 0–6 的 21 条原始 MonsterDrop 行，每行记录完整安装文件 SHA256、固定 Git blob SHA256 和原数组索引。它与原有 102 行/37 文件的 Java 任务夹具分开，未修改 34 项 Java 场景回归或运行时代码。

固定来源是 TurnBasedGameData `724b139d8c9c32d12552eb95745a4fee72bfe48b`，标题 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。完整 `ExcelOutput/MonsterDrop.json` 安装 SHA256 为 `63f251cd7970ef1297ff3e2104a5036c13f6720344676b991f0c17f2311948a5`；检查还逐字节计算 Git blob SHA256，再比较完整 JSON 内容与每条保留行。

三种原怪的世界等级 0 行均声明 `AvatarExpReward=36`，`DisplayItemList` 原样为 `[{"ItemID":2},{"ItemID":111011},{"ItemID":181003}]`。货币展示项未声明数量，也没有 CoinReward 或 ScoinReward=36 的字段。不能把 AvatarExpReward 的名字或该展示项解释为已经证实的官方 36 信用奖励或官方掉率。

本服已有 `emu/lunarcore/data/excel/MonsterDropExcel.onLoad` 在展示货币项 2 没有正数量时，以 `getAvatarExpReward()` 创建固定 DropParam；`EntityMonster.calculateDrops` 和 `DropService.calculateDrops` 在已接受战斗胜利后实际入账。冻结产物 `build/parallel-gameplay-second-runtime.jar` 的 SHA256 为 `02e5a8c622c785fe126a28789f8d7055a0e280ceaa618d0e2b06c71cdd890ddd`，其编译代码检查保留在 `build/daily-killmonster-core-drop-bytecode.txt`。原生及正常停止后的只读 H2 已证明此产物在上述来源世界等级0实际产生36信用。此事实描述当前 Java 算法，不宣称官方掉率已验收。

普通掉落独立于主委托 Reward231 的每轮5000信用和活跃目标2100003的每轮200点：两轮正常交付末钱包10072，未交付旧轮到期后只完成新轮末钱包5072；旧轮未付的是主委托奖励，普通战斗掉落仍可能已经取得。两份实际报告为 `build/daily-killmonster-settled-second-fixed-v2.json` 和 `build/daily-killmonster-expired-g95-second-fixed-v2.json`，不重算它们的请求或通过数量。

以下两项来源检查已实际通过：完整固定 blob/每行索引证明，以及原字段与货币展示数量边界。它们只读本地资源与参考 Git 内容，不执行参考仓库的二进制，不更改配置或数据库。

```powershell
python -m unittest discover -s scripts/tests -p test_daily_killmonster_drops_sources.py -v
```

来源数据树没有代码 LICENSE 声明，本批夹具只保留必要数据证明，Python 与 Java 验证均为本项目独立编写，保留 LunarCore/AGPL 归属。客户端战斗模拟和真实墙钟跨日均未因此获得新增验收。
