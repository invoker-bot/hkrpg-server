# 客户端调试窗口（历史进程内实现）

2026-10-18 后续状态：此 DLL 加载方案已撤回，当前原桌面入口改用 [独立客户端日志窗口](client-external-log-console.md)。下面保留原实现及栈溢出修复的历史证据，不再按这些步骤安装到实际客户端。

2026-10-18 增加 `HKRPG Client Debug`：由 `StarRail.exe` 内部的线程调用 Windows `AllocConsole` 创建。继续双击原桌面 StarRail 图标；不需要额外启动器或服务端日志窗口。

首版在真实游戏中出现秒退，Windows 记录故障模块为新增的 `VERSION.dll`，异常为 `0xc00000fd`，偏移 `0x2cd6` 对应栈探测函数。已经用保留栈为 64 KiB 的原生线程复现同一异常：`DllMain` 的长路径自动数组导致每次线程通知需要 65,600 字节栈空间。现将该数组移到进程堆，入口栈占用降至 80 字节。首版已撤回；用户确认撤回后能停留在登录界面，修正版已重新安装，真实游戏复测继续待确认。

窗口输出当前客户端进程编号、Unity/游戏/SDK/KCP 模块加载情况，以及新产生的 Unity `Player.log` 行。日志同时保存在 `%LOCALAPPDATA%\HKRPG\client-debug-日期-时间-进程号.log`。账号、密码、Token、Cookie 等敏感行及包含查询参数的 URL 不显示、不复制到新日志；原 Unity 日志不改写。

窗口跟随游戏退出。Ctrl+C/Ctrl+Break 不终止游戏；直接关闭控制台窗口可能一并结束游戏，退出时使用游戏的正常退出入口。

## 构建和安装

```powershell
python scripts/build-client-console.py `
  --output-dir build/client-debug-20261018-stackfix `
  --install "$env:LOCALAPPDATA\hoyo\hkrpg\versions\current"
```

需要 Windows x64、本机 MinGW GCC，以及仓库 `.tools/client-analysis-libs` 中已有的 `pefile`。DLL 只依赖 Windows 系统库。

已核对的 `UnityPlayer.dll` 静态导入 `VERSION.dll`，因此在游戏根目录新建 `version.dll`，可由原游戏入口自动加载。系统原有 17 个接口及其序号均转发到本机 System32 的真实实现。游戏 EXE、UnityPlayer、GameAssembly、账号 SDK、资源包和桌面快捷方式保持原样。DLL 只在进程名为 StarRail.exe 时开控制台，其他加载者仅获得系统接口转发。

安装工具锁定已核对的客户端 SHA256，拒绝运行中的客户端、测试版 DLL、发生变化的构建产物及已有其他 `version.dll`。安装记录在构建目录的 `installation.json`。要撤回时，退出游戏，核对该记录的 DLL 哈希后移走游戏根目录新增的 `version.dll`；无需恢复其他游戏文件。

本次为自有 C 实现，遵循项目 AGPL；LunaGC 仅用于核对所需的窗口行为，没有运行、复制或移植其补丁二进制/源码。这个窗口不改变登录或网络行为。

## 验证边界

`python -m unittest scripts.tests.test_client_console` 的 4 项原生集成检查通过：

- 17 个系统接口的实际地址与原 Windows 实现一致；普通 Python 进程加载 DLL 不开窗口。
- 正式构建通过与 Unity 相同的静态 DLL 导入方式加载；GUI 测试宿主进程拥有控制台。
- 中文带空格目录、实时中文日志、首次新建日志、跨读块敏感字段、较大内容重写和超长行处理通过；宿主正常退出。
- 64 KiB 保留栈的线程创建、DLL 线程通知和正常退出通过；修复前该回归以 `0xc00000fd` 真实失败。

测试宿主是仓库自己编译的微型 GUI 程序，没有启动或操纵真实 StarRail。正式 DLL 已安装到原客户端目录，原五份客户端文件和桌面快捷方式的校验和未变。真实游戏中的窗口显示和登录进入地图仍需用户启动后确认。

本次崩溃、先失败的回归、入口栈占用和通过记录保存在 `build/client-console-crash-20261018`；修正版构建及安装记录在 `build/client-debug-20261018-stackfix`。Java 代码与服务端运行包未改，统一构建通过，Java 测试任务使用已有通过结果。
