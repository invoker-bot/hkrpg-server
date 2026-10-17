# 4.6 启动资源警告修复

2026-10-18 补齐启动实际读取的资源：`P20561_F20561001`、`P40561_F40561001`、`P30146_F30146001` 三个楼层及其 231/27/17 个地图组，以及 `LocalPlayer_Pearl_00_Ability.json`。共 279 份本机国服配置，完整读取每条原始记录；楼层保留维度、入口、组属性与保存变量，技能保留动作、修饰器及定点字面值。

另从 TurnBasedGameData 固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b` 读取 `TextMapEN.json`、`TextMapCHS.json`。原稀疏配置指定的 `TextMapCN.json` 在该提交不存在，因此没有取到文本资源。本次没有改写参考仓库的工作区或既有运营资源。

安装工具是 `scripts/recover-client-startup-resources.py`。类型、分支、函数边界摘要和逐条原件/结果 SHA256 保存在 `scripts/data/client46-startup-resources.json`，离线回归原件保存在 `scripts/tests/fixtures/client46-startup-records.json`。使用仓库已有的固定客户端读取器；没有运行参考项目的程序，也没有引入参考实现作为服务端依赖。

```powershell
python scripts/recover-client-startup-resources.py `
  --client-dir "$env:LOCALAPPDATA\hoyo\hkrpg\versions\current" `
  --destination resources/4.6 --include-textmap
```

写入前检查全部冲突；已有不同文件或越界路径会拒绝安装。281 份资源另加独立补充清单，不覆盖原清单、配置或数据库。

验证记录：

- 5 项 Python 回归通过，覆盖 279 条完整原件、楼层/组引用、Pearl 技能、截断/尾随字节、安装冲突、重复安装及路径越界。
- `scripts/build.ps1` 通过；Java 代码未改，测试任务复用了此前通过的结果，本批没有重新执行全量 Java 测试。
- Dispatch 两轮持久化检查通过；隔离完整初始化两轮各 58 请求/58 响应通过，两次正常停服后同一账号使用不同密码恢复。
- 两轮启动日志均无资源警告或异常，只保留 4.6 实验协议提示。原有自动建号、不校验密码行为未改。
- 运营服务正常停服备份后重启。主动附加诊断的 JVM 使用 `-XX:+EnableDynamicAgentLoading` 明确启用动态代理；重新附加现有元数据诊断后没有动态加载警告。

本次在用运行目录为 `build/startup-warning-fix-20261018`，日志为其 `server.log`。证据包括 `build/startup-resource-install-20261018.json`、`build/startup-resource-initial-20261018.json` 和运行目录中的 `warning-fix-proof.json`。原配置、数据库、现有客户端文件及桌面入口均保留。

这里只补资源和启动诊断。新增地图的完整任务演出、Pearl 全部技能执行，以及真实游戏登录、3011014 演出与重连验收继续由实际客户端结果确认；不把完整解码或合成连接当作游戏验收。
