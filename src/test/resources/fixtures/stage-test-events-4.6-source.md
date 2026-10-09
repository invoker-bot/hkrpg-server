# 战斗测试关卡与事件原始夹具

来源为本地 `resources/4.6/ExcelOutput/StageTestConfig.json`、`StageConfig.json` 与 `PlaneEvent.json`，资源包来自 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。每条夹具保留原始字段，并记录原表 SHA256。

26 个测试关卡 ID 均被原始 PlaneEvent 引用，且不在常规 StageConfig 中；另保留常规关卡 201342210 作为兼容性对照。只在临时资源目录加载，不修改运行资源。客户端三份关卡表的读取路径已在本地元数据和原生读取函数中定位，其中 StageTestConfig 的 829 行与现有原始 JSON 完全一致；本机 StageConfigLD 为空，未把另一区域资源中的 51 行作为本机客户端事实。

测试覆盖资源注册、事件到关卡查找、等级、类型及怪物槽位顺序，不代表 StageAbilityConfig、关卡脚本、战斗演出或客户端验收已经完成。
