# 客户端调试窗口

2026-10-18 增加 `HKRPG Client Debug`：由 `StarRail.exe` 内部的线程调用 Windows `AllocConsole` 创建。继续双击原桌面 StarRail 图标；不需要额外启动器或服务端日志窗口。

窗口输出当前客户端进程编号、Unity/游戏/SDK/KCP 模块加载情况，以及新产生的 Unity `Player.log` 行。日志同时保存在 `%LOCALAPPDATA%\HKRPG\client-debug-日期-时间-进程号.log`。账号、密码、Token、Cookie 等敏感行及包含查询参数的 URL 不显示、不复制到新日志；原 Unity 日志不改写。

窗口跟随游戏退出。Ctrl+C/Ctrl+Break 不终止游戏；直接关闭控制台窗口可能一并结束游戏，退出时使用游戏的正常退出入口。

## 构建和安装

```powershell
python scripts/build-client-console.py `
  --output-dir build/client-debug-20261018 `
  --install "$env:LOCALAPPDATA\hoyo\hkrpg\versions\current"
```

需要 Windows x64、本机 MinGW GCC，以及仓库 `.tools/client-analysis-libs` 中已有的 `pefile`。DLL 只依赖 Windows 系统库。

已核对的 `UnityPlayer.dll` 静态导入 `VERSION.dll`，因此在游戏根目录新建 `version.dll`，可由原游戏入口自动加载。系统原有 17 个接口及其序号均转发到本机 System32 的真实实现。游戏 EXE、UnityPlayer、GameAssembly、账号 SDK、资源包和桌面快捷方式保持原样。DLL 只在进程名为 StarRail.exe 时开控制台，其他加载者仅获得系统接口转发。

安装工具锁定已核对的客户端 SHA256，拒绝运行中的客户端、测试版 DLL、发生变化的构建产物及已有其他 `version.dll`。安装记录在构建目录的 `installation.json`。要撤回时，退出游戏，核对该记录的 DLL 哈希后移走游戏根目录新增的 `version.dll`；无需恢复其他游戏文件。

本次为自有 C 实现，遵循项目 AGPL；LunaGC 仅用于核对所需的窗口行为，没有运行、复制或移植其补丁二进制/源码。这个窗口不改变登录或网络行为。

## 验证边界

`python -m unittest scripts.tests.test_client_console` 的 3 项原生集成检查通过：

- 17 个系统接口的实际地址与原 Windows 实现一致；普通 Python 进程加载 DLL 不开窗口。
- 正式构建通过与 Unity 相同的静态 DLL 导入方式加载；GUI 测试宿主进程拥有控制台。
- 中文带空格目录、实时中文日志、首次新建日志、跨读块敏感字段、较大内容重写和超长行处理通过；宿主正常退出。

测试宿主是仓库自己编译的微型 GUI 程序，没有启动或操纵真实 StarRail。正式 DLL 已安装到原客户端目录，原五份客户端文件和桌面快捷方式的校验和未变。真实游戏中的窗口显示和登录进入地图仍需用户启动后确认。
