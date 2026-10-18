# 本地客户端补丁的持久启动

登录补丁直接保存在安装目录中的账号 SDK、SDK 资源包和 Dispatch 配置，不需要 Codex 会话或转发进程。2026-10-18 重新核对安装清单的五个文件，摘要全部一致；安装前原桌面 `StarRail.exe.lnk` 直接指向原安装 EXE；本轮已备份原入口并安装下述固定检查脚本，游戏 EXE 和图标保持原路径。

当前调试窗口由独立日志读取进程显示，只读取客户端 `Player.log`。13:17 的真实启动随后发生原生访问冲突；虽然尚不能证明旧调试 DLL 是根因，本轮已撤回进程内加载方式以排除其影响。启动器不再恢复 `version.dll`；已知旧 DLL 只保留副本，未知文件不移动。具体错误证据、迁移与验证见 [客户端独立日志窗口](client-external-log-console.md)。

## 安装资料

稳定目录使用仓库的 `.local/client/`，不要将唯一备份放在可清理的 `build/`。该目录需要：

- `patch-manifest.json`：下面的清单。
- `version.dll`：保留的旧调试 DLL 备份，不再复制到客户端；SHA256 为 `323299a6320f30fd9ad5f5595878d8efd49fc5444d4f51521c679cb59f63b9d2`。
- 安装入口后自动保存 `original-StarRail.exe.lnk`，保留原快捷方式字节内容。

清单结构如下，所有摘要必须来自已核对安装记录；不能用当前未知文件自动生成并信任：

```json
{
  "schema": 2,
  "clientRoot": "C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current",
  "clientFiles": {
    "StarRail.exe": "<已核对 SHA256>",
    "UnityPlayer.dll": "<已核对 SHA256>",
    "GameAssembly.dll": "<已核对 SHA256>",
    "StarRail_Data/Plugins/x86_64/AccountPlatNative.dll": "<已核对 SHA256>",
    "StarRail_Data/StreamingAssets/MiHoYoSDKRes/PC/mihoyo_sdk_res": "<已核对 SHA256>",
    "StarRail_Data/StreamingAssets/ClientConfig.bytes": "<已核对 SHA256>",
    "StarRail_Data/il2cpp_data/Metadata/global-metadata.dat": "<已核对 SHA256>"
  },
  "debugConsole": {
    "mode": "external",
    "sha256": "323299a6320f30fd9ad5f5595878d8efd49fc5444d4f51521c679cb59f63b9d2",
    "backupPath": "version.dll"
  },
  "service": {
    "readyUrl": "http://127.0.0.1:18080/health/ready",
    "startCommand": "cd D:\\Projects\\Experiment\\hkrpg-server; docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml up -d --wait"
  }
}
```

`clientFiles` 必须恰好包含七项。五项来源是 `build/login-datagram-20261017/installed-files-before.json`，EXE 和 UnityPlayer 来源是 `scripts/build-client-console.py` 的已核对摘要。服务必须返回 HTTP 200 和 JSON `ready: true`；启动器不使用系统代理、不跟随健康检查重定向。

## 检查与安装

在仓库根目录使用已安装的 Python 3.13：

```powershell
$python = 'C:\Users\InvokerBot\.pyenv\pyenv-win\versions\3.13.13\python.exe'
& $python scripts/start-local-client.py --dry-run
& $python scripts/start-local-client.py --repair-only
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-client.ps1 -PythonExecutable $python -InstallShortcut -DryRun
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-client.ps1 -PythonExecutable $python -InstallShortcut
```

第一步只读检查；第二步仅撤回客户端根目录中摘要匹配的旧调试 DLL，不启动游戏或日志窗口。后两步分别预览和安装原桌面快捷方式。安装保留原路径、名称及游戏图标，将目标改为隐藏的 PowerShell 启动检查；失败会显示错误窗口并写入 `%LOCALAPPDATA%\HKRPG\client-launcher.log`。安装快捷方式自身只执行只读检查，不移动 DLL 或启动客户端。原快捷方式备份不可覆盖；发现其他目标或不一致备份时会拒绝修改。

之后仍双击原桌面图标。服务器未就绪时会提示 Compose 启动命令；七项文件任一变化时拒绝启动并保留现状。旧 `version.dll` 若不存在，不再从备份或数字后缀恢复；若存在且摘要匹配，则同目录改名保存为 `version.dll.hkrpg-retired-<随机标识>`。客户端运行时拒绝修改，不移动其他摘要的文件。已存在的数字后缀副本保留。版本 1 清单拒绝启动，防止旧默认行为再次安装 DLL。

启动前记录客户端日志的位置、文件身份和摘要；启动后独立窗口核对游戏路径与创建时间，避免遗漏本次启动日志或跟随复用的 PID。日志窗口与游戏是不同进程，关闭日志窗口不终止游戏；游戏退出后读取器结束。窗口显示客户端日志并过滤敏感行，不读取服务端日志。启动器等待窗口就绪握手最多 8 秒；读取器失败只记录 `consoleWarning`，不会把已启动的游戏判为启动失败或关闭它。

用户实际启动曾报告 `WinError 740`。已核对原 EXE 的 SHA256 及内嵌 manifest：它声明 `requireAdministrator`、`uiAccess=false`。启动器改用 Windows `ShellExecuteExW` 的正常 `open` 动作，由系统依据该声明处理 UAC；用户取消确认时显示明确提示且不会重试。补丁检查脚本本身不请求提升权限。启动调用取得游戏 PID 后关闭自身持有的句柄，不等待游戏退出。结构和标志依据 [Microsoft ShellExecuteEx 文档](https://learn.microsoft.com/en-us/windows/win32/api/shellapi/ns-shellapi-shellexecuteinfow)。

撤回入口时先退出游戏，将 `.local/client/original-StarRail.exe.lnk` 复制回原桌面 `StarRail.exe.lnk`。这仅恢复启动方式；登录补丁和客户端文件不随入口撤回改变。

## 验证边界

先保留 13 项隔离回归中的 6 项失败，再加入恢复流程；另用 Windows PowerShell 5.1.26100.7019 的真实 WScript 快捷方式读写复现并修复短路径/长路径比较以及备份文件扩展名问题。上一轮 16 项检查通过，覆盖重复改名、只读预览、仅修复、并发不覆盖、运行中拒写、错误文件摘要、健康状态、快捷方式连续安装及省略 Manifest 参数的默认入口。默认清单路径在脚本正文中计算，避免 PowerShell 5.1 参数初始化期间脚本目录为空。证据位于 `build/client-launcher-persistence-20261018/`。

本轮先保留模拟 `CreateProcess` 返回 740 的四项失败，再修复启动方式；最终 21 项通过，包含 UAC 取消、保留系统错误码和进程句柄释放。另用本机编译的临时无界面程序走真实 `ShellExecuteExW`，核对 PID、关闭启动句柄后子进程仍运行、无额外控制台以及正常退出码 0。证据为同目录 `elevation-red.txt`、`elevation-native-green.txt` 和 `elevation-manifest-proof.json`。

上述测试使用临时目录中的文本伪客户端、本地健康检查替身及本机编译的无界面程序，未由测试启动真实游戏或触发游戏 UAC。用户随后两次通过原入口成功启动，确认 740 已解除；13:17 的组件异常与新的独立窗口验证另见上述迁移记录。进图与完整演出继续以真实客户端为准。
