# 4.6 战斗测试关卡的兼容加载

Java 的 `StageExcel` 以前只登记 `StageConfig.json`。现有原始 `PlaneEvent` 引用了另外 26 个关卡 ID：100、166..190。这些 ID 在常规表中不存在，却全部位于 `StageTestConfig.json`，因此事件到关卡的实际查找无法取得它们。

现在继续要求常规表存在，同时登记可选的测试关卡表。缺少可选表的旧资源包仍能加载；已存在但损坏的可选表会报告解析错误。只使用操作员已有资源，不生成测试表、覆盖资源或修改配置。两个表中的关卡 ID 当前互不重复。

固定本机客户端的 `StageExcelTable` 初始化分别引用 `BakedConfig/ExcelOutputGameCore/StageConfig.bytes`、`StageTestConfig.bytes` 和 `StageConfigLD.bytes`；对应字符串槽为 `97186f8`、`9718700`、`9718708`。只读研究读取结果如下：

| 表 | 本机记录键 | 字节数 | 本机行数 | 与现有 JSON 核对 |
|---|---|---:|---:|---|
| StageConfig | 4efae292630058fd | 4,361,268 | 29,515 | 全部字段一致；2,899 行仅 MonsterWarningRatio 的 float32 文本精度不同 |
| StageTestConfig | 02e3900fff97c0cf | 100,383 | 829 | 原始行完全一致 |
| StageConfigLD | 1480f80c46f4c751 | 1 | 0 | 现有另一区域资源有 51 行，不登记或覆盖常规表 |

研究中曾把 `SubLevelGraphs` 的字段顺序解错；核对原生读取函数 `101745 / 1d063260` 的目标偏移后纠正，最终核对结果才用于上表。现已将三个固定表的完整读取整理为独立工具，见 [关卡表核对说明](client-stage-tables-4.6.md)，不扩展为任意版本的通用解码器。

30 项 Java 回归使用 27 条原始关卡及对应事件夹具，覆盖全部 26 个引用、等级、类型、怪物槽位顺序、重复怪物、常规表保留、列表及字典格式、旧资源包和损坏文件。修复前 28 项真实失败，其余两项为兼容性对照。夹具及原表 SHA 位于 `src/test/resources/fixtures/stage-test-events-4.6.json`，来源提交见同目录说明。

```powershell
.\gradlew.bat test --tests '*StageTestResource46Test' --no-daemon
.\scripts\build.ps1
```

此修复补齐关卡查找和既有怪物波次缓存。`StageAbilityConfig`、`StageConfigData`、子关卡脚本、演出及这些事件的客户端输入仍需接通；没有把 829 条数据全部可加载宣称为 829 个完整可玩关卡，也没有修改协议、版本号或功能完成状态。
