# 控制台单条命令失败后继续读取

实际旧控制台把读取循环和命令调用放在同一个外层异常边界。命令处理器抛出普通异常后，循环直接结束，服务器进程仍可能运行，后续停服命令却不再执行。以太芯片第一失败尝试留下了这个问题的实际控制台日志；该尝试没有被计为成功或正常停服。

修复把普通命令异常限制在一次调用内，记录异常后读取下一条命令。不输出整条输入，避免把命令参数额外写入日志。终端初始化与读取错误保持原处理；`UserInterruptException` 和 `EndOfFileException` 继续交给原退出分支，来自处理器的同类控制信号也不能被普通异常边界吞掉。

回归启动独立 Java 子进程，调用真正的 `LunarCore.startConsole`、JLine 与 `CommandManager.invoke`，使用两个受控命令处理器验证连续一次或两次异常后的下一条命令。空行、未知命令与 stdin EOF 是正常控制；另外两例由受控处理器明确抛出原 JLine 中断/EOF 类型，核对原退出边界，没有称为物理键盘按键验收。每个子进程核对实际 LunarCore CodeSource，工作目录与输出均隔离，不启动 HTTP、KCP、数据库或游戏客户端。

最终五项回归在前批冻结 `build/out-followup-core-material-final-runtime.jar`（SHA256 `4a8e6dab5db944619081e669585f9f8f5a2c6ea8bbfe76c3717ad8805e04ae2b`）上实际为两项普通断言失败、三项控制成功；两项失败属于同一个控制台问题。第一版候选通过原三例后，在新增的两个处理器控制信号例中实际失败，保留在 `build/console-continuation-next-source/signal-guard-owned-red-v2`。准确保留控制信号的第二版候选五项全部通过，输入前后 SHA 与所有项目 CodeSource 核对通过。旧三例源码及第一候选另存 `first-three-case-source/`，没有把后续修改当作旧证据。

最终基线和绿色报告分别为 `build/console-continuation-next-source/immutable-4a8e-five-case-red-v2/verification.json` 与 `owned-green-v2/verification.json`。这两轮子进程回归使用独立候选类覆盖，没有写入 Gradle 的 main 输出。随后正式项目完整构建的 520 个套件、14,226 项测试全部通过，其中本问题五项；2,584 份输入前后 SHA 一致。完整构建证据及收尾汇总脚本失败后的只读恢复边界见 `build/feixiao-selected-item-blessing-console-full-verification.json`，全部 XML 已独立归档。没有宣称处理所有终端故障、插件严重 Error、真实游戏输入或完整玩法。

另以同一冻结旧包实际启动隔离 Dispatch、内置 H2、HTTP 和真实命令扫描，受控命令在核对 LunarCore、HkrpgServer、CommandManager 全部来自该 jar 后抛出异常。已观察到一次异常后 HTTP 仍 ready、进程仍活着，但下一观察命令未执行；该尝试为失败，零次正常停服、一次所属进程终止清理，不计入成功重启。原报告在 `build/console-continuation-next-source/native/immutable-4a8e-actual-console-red-v1.json`。

`scripts/smoke-console-continuation.py` 随后对正式新包 `build/feixiao-selected-item-blessing-console-candidate-runtime.jar`（SHA256 `3eeeab41ec750f60b92c2ab3a536760afab14eb53c71581d34e18bafb02fa05c`）实际核对两轮各连续两次异常后仍执行下一条观察命令、HTTP ready、正常 stop，以及同一账号重启恢复。两次正常停服、零次强制终止；三个生产入口的实际 CodeSource 均来自上述 jar。报告为 `build/feixiao-selected-item-blessing-console-console-native.json`（SHA256 `72a6b1b23910648ac990b1720ac8d67e8aee16c850f322954befe2ea9da3ea9e`）。探针只附加受控命令类，不覆盖生产类；本检查没有启动 GameServer、读取 Player 存档、执行游戏客户端或模拟物理终端信号。
